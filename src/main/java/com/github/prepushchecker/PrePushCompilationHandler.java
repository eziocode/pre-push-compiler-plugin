package com.github.prepushchecker;

import com.intellij.dvcs.push.PrePushHandler;
import com.intellij.dvcs.push.PushInfo;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompileStatusNotification;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessage;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.PerformInBackgroundOption;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.problems.WolfTheProblemSolver;
import com.intellij.vcs.log.VcsFullCommitDetails;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.TreeSet;

public final class PrePushCompilationHandler implements PrePushHandler {
    private static final Logger LOG = Logger.getInstance(PrePushCompilationHandler.class);
    private static final long WAIT_SLICE_MILLIS = 250L;

    @Override
    public @NotNull String getPresentableName() {
        return "Pre-Push Compilation Checker";
    }

    @Override
    public @NotNull Result handle(
        @NotNull Project project,
        @NotNull List<PushInfo> pushDetails,
        @NotNull ProgressIndicator indicator
    ) {
        if (project.isDisposed()) {
            return Result.OK;
        }

        try {
            PushChangeSet changeSet = collectRelevantChanges(pushDetails, indicator);
            if (!changeSet.hasRelevantChanges()) {
                LOG.info("Skipping pre-push compilation check because no source/build files are affected.");
                return Result.OK;
            }

            CompilationErrorService errorService = CompilationErrorService.getInstance(project);
            Runnable abortCommitAction = buildAbortCommitAction(project, pushDetails);
            String prePushKey = changeSet.cacheKey();

            List<String> problemFiles = collectKnownProblemFiles(project, changeSet.getSourceFiles());
            if (!problemFiles.isEmpty()) {
                errorService.setErrors(problemFiles);
                boolean resolved = showDialog(
                    project,
                    indicator.getModalityState(),
                    "Push Blocked - IDE Problems Found",
                    "IntelliJ already reports problems in files included in this push. Fix them before pushing:",
                    problemFiles,
                    _ind -> collectKnownProblemFiles(project, changeSet.getSourceFiles()),
                    abortCommitAction
                );
                if (!resolved) return Result.ABORT;
            }

            // Reuse a recent compile verdict when nothing has moved on disk since it ran.
            // This skips a redundant full rebuild when e.g. the user just ran the manual
            // "Run Compilation Check" and is now pushing without edits.
            List<String> cached = errorService.tryReusePrePushResult(prePushKey);
            if (cached == null && !changeSet.requiresProjectBuild() && !requiresStrictSnapshotCheck(project, changeSet)) {
                cached = errorService.tryReuse(changeSet.getSourceFiles());
            }
            List<String> errors;
            if (cached != null) {
                LOG.info("Reusing cached compilation result (" + cached.size() + " error(s)).");
                errors = cached;
            } else {
                scheduleBackgroundPrePushCheck(project, changeSet, prePushKey, pushDetails);
                return Result.ABORT;
            }

            if (!errors.isEmpty()) {
                errorService.setErrors(errors);
                boolean resolved = showDialog(
                    project,
                    indicator.getModalityState(),
                    "Push Blocked - Compilation Errors Found",
                    "Compilation failed for this push. Fix the following errors before retrying:",
                    errors,
                    freshInd -> changeSet.requiresProjectBuild()
                        ? compileProject(project, freshInd)
                        : compileFiles(project, changeSet.getSourceFiles(), freshInd),
                    abortCommitAction
                );
                if (resolved) errorService.setErrors(Collections.emptyList());
                return resolved ? Result.OK : Result.ABORT;
            }

            errorService.setErrors(Collections.emptyList());
            return Result.OK;
        } catch (ProcessCanceledException ignored) {
            LOG.info("Pre-push compilation check canceled.");
            return Result.ABORT;
        }
    }

    static boolean requiresProjectBuild(Change change, String path) {
        if (PushValidationPaths.isBuildFile(path)) {
            return true;
        }

        Change.Type type = change.getType();
        return type == Change.Type.DELETED || type == Change.Type.MOVED;
    }

    private static PushChangeSet collectRelevantChanges(List<PushInfo> pushDetails, ProgressIndicator indicator) {
        Map<String, VirtualFile> sourceFiles = new LinkedHashMap<>();
        Set<String> relevantPaths = new LinkedHashSet<>();
        Set<String> commitIds = new LinkedHashSet<>();
        boolean requiresProjectBuild = false;

        for (PushInfo pushInfo : pushDetails) {
            for (VcsFullCommitDetails commit : pushInfo.getCommits()) {
                indicator.checkCanceled();
                commitIds.add(commit.getId().asString());
                for (Change change : commit.getChanges()) {
                    String path = extractPath(change);
                    if (!PushValidationPaths.isRelevantPath(path)) {
                        continue;
                    }

                    relevantPaths.add(path);
                    if (requiresProjectBuild(change, path)) {
                        requiresProjectBuild = true;
                    }

                    if (PushValidationPaths.isCompilableSource(path)) {
                        VirtualFile file = findVirtualFile(change);
                        if (file != null) {
                            sourceFiles.putIfAbsent(file.getPath(), file);
                        }
                    }
                }
            }
        }

        return new PushChangeSet(
            new ArrayList<>(sourceFiles.values()),
            new ArrayList<>(relevantPaths),
            new ArrayList<>(commitIds),
            !relevantPaths.isEmpty(),
            requiresProjectBuild
        );
    }

    private static String extractPath(Change change) {
        ContentRevision afterRevision = change.getAfterRevision();
        if (afterRevision != null) {
            return afterRevision.getFile().getPath();
        }

        ContentRevision beforeRevision = change.getBeforeRevision();
        return beforeRevision != null ? beforeRevision.getFile().getPath() : "";
    }

    private static VirtualFile findVirtualFile(Change change) {
        ContentRevision afterRevision = change.getAfterRevision();
        if (afterRevision == null) {
            return null;
        }

        String path = FileUtil.toSystemIndependentName(afterRevision.getFile().getPath());
        LocalFileSystem localFileSystem = LocalFileSystem.getInstance();
        VirtualFile file = localFileSystem.findFileByPath(path);
        return file != null ? file : localFileSystem.refreshAndFindFileByPath(path);
    }

    private static List<String> collectKnownProblemFiles(Project project, Collection<VirtualFile> sourceFiles) {
        if (sourceFiles.isEmpty()) {
            return Collections.emptyList();
        }

        WolfTheProblemSolver problemSolver = WolfTheProblemSolver.getInstance(project);
        List<String> problemFiles = new ArrayList<>();
        for (VirtualFile sourceFile : sourceFiles) {
            if (problemSolver.isProblemFile(sourceFile)) {
                problemFiles.add(toDisplayPath(project, sourceFile));
            }
        }
        return problemFiles;
    }

    private static List<String> compileFiles(Project project, Collection<VirtualFile> sourceFiles, ProgressIndicator indicator) {
        if (sourceFiles.isEmpty()) {
            return Collections.emptyList();
        }
        CompilerManager compilerManager = CompilerManager.getInstance(project);
        CompileScope scope = ApplicationManager.getApplication().runReadAction(
            (Computable<CompileScope>) () -> buildPushScope(project, sourceFiles, compilerManager)
        );
        Map<String, Long> stamps = CompilationErrorService.snapshotStamps(sourceFiles);
        return runCompilation(
            project,
            indicator,
            false,
            stamps,
            notification -> compilerManager.make(scope, notification)
        );
    }

    /**
     * Picks the smallest {@link CompileScope} that still guarantees A-depends-on-B coverage.
     *
     * <ul>
     *   <li>Resolve each pushed file's module.</li>
     *   <li>Union in every module that <em>depends on</em> that module
     *       ({@link ModuleManager#getModuleDependentModules}) — these are the potential callers.
     *       The lookup is a pure module-graph query, no file iteration.</li>
     *   <li>{@code make} that union. JPS runs incrementally, so only actually-stale files in
     *       those modules are recompiled; warm files are skipped. Because the caller modules
     *       are in the scope explicitly, JPS cannot "forget" to recompile a caller the way a
     *       stale dep-graph sometimes does with a file-only scope.</li>
     *   <li>Safety cap ({@code prepushchecker.scope.modules.cap}, default 50): if a pushed
     *       file lives in a very widely-used utility module and would drag most of the project
     *       in, fall back to the narrower file scope rather than churning the world.</li>
     *   <li>If no module can be resolved (file outside content roots), use file scope.</li>
     * </ul>
     *
     * Must be called under a read action.
     */
    /** Package-private helper so the socket server can reuse the adaptive scope policy. */
    static CompileScope buildPushScopeForExternal(Project project,
                                                  Collection<VirtualFile> files,
                                                  CompilerManager cm) {
        return ApplicationManager.getApplication().runReadAction(
            (Computable<CompileScope>) () -> buildPushScope(project, files, cm)
        );
    }

    private static CompileScope buildPushScope(Project project,
                                               Collection<VirtualFile> files,
                                               CompilerManager cm) {
        ProjectFileIndex idx = ProjectFileIndex.getInstance(project);
        ModuleManager mm = ModuleManager.getInstance(project);
        Set<Module> modules = new LinkedHashSet<>();
        for (VirtualFile f : files) {
            Module m = idx.getModuleForFile(f, false);
            if (m == null) continue;
            modules.add(m);
            modules.addAll(mm.getModuleDependentModules(m));
        }
        VirtualFile[] fileArr = files.toArray(VirtualFile.EMPTY_ARRAY);
        if (modules.isEmpty()) {
            LOG.info("Pre-push: no module resolved for pushed files, using file scope.");
            return cm.createFilesCompileScope(fileArr);
        }
        int cap = Registry.intValue("prepushchecker.scope.modules.cap", 50);
        if (modules.size() > cap) {
            LOG.info("Pre-push: dependent-module count " + modules.size()
                + " exceeds cap " + cap + "; falling back to file scope.");
            return cm.createFilesCompileScope(fileArr);
        }
        LOG.info("Pre-push: make scope = " + modules.size()
            + " module(s) (pushed + dependents), incremental.");
        return cm.createModulesCompileScope(modules.toArray(Module.EMPTY_ARRAY), false);
    }

    private static List<String> compileProject(Project project, ProgressIndicator indicator) {
        CompilerManager compilerManager = CompilerManager.getInstance(project);
        CompileScope scope = compilerManager.createProjectCompileScope(project);
        return runCompilation(
            project,
            indicator,
            true,
            Collections.emptyMap(),
            notification -> compilerManager.make(scope, notification)
        );
    }

    private static List<String> runCompilation(
        Project project,
        ProgressIndicator indicator,
        boolean projectScope,
        Map<String, Long> stamps,
        CompilationStarter compilationStarter
    ) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<String>> errors = new AtomicReference<>(Collections.emptyList());

        Runnable startCompilation = () -> compilationStarter.start((aborted, errorCount, warnings, compileContext) -> {
            List<String> result;
            if (aborted) {
                result = Collections.singletonList("Compilation was aborted.");
            } else if (errorCount > 0) {
                result = formatCompilerMessages(project, compileContext.getMessages(CompilerMessageCategory.ERROR));
            } else {
                result = Collections.emptyList();
            }
            errors.set(result);
            CompilationErrorService errorService = CompilationErrorService.getInstance(project);
            if (aborted) {
                errorService.setErrors(result);
            } else {
                errorService.recordCompletion(projectScope, stamps, result);
            }
            latch.countDown();
        });

        Application application = ApplicationManager.getApplication();
        if (application.isDispatchThread()) {
            startCompilation.run();
        } else {
            ModalityState modality = indicator.getModalityState();
            if (modality == null) {
                modality = ModalityState.defaultModalityState();
            }
            application.invokeAndWait(startCompilation, modality);
        }

        try {
            while (!latch.await(WAIT_SLICE_MILLIS, TimeUnit.MILLISECONDS)) {
                indicator.checkCanceled();
            }
            return errors.get();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return Collections.singletonList("Compilation check was interrupted.");
        }
    }

    private static void handleBackgroundCompletion(
        Project project,
        List<String> errors,
        List<PushInfo> pushDetails
    ) {
        if (errors.isEmpty()) {
            notify(
                project,
                "Pre-push compilation finished",
                "Background compilation passed. Auto-retrying the push...",
                com.intellij.notification.NotificationType.INFORMATION
            );
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                executeAutoPush(project, pushDetails);
            });
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            showFailureChoiceDialog(project, errors, pushDetails);
        });
    }

    private static void showFailureChoiceDialog(
        Project project,
        List<String> errors,
        List<PushInfo> pushDetails
    ) {
        String message = "Background compilation found " + errors.size()
            + " error(s). What do you want to do?\n\n"
            + "• Reset Commit — soft-reset the pushed commits, keep changes in the working tree.\n"
            + "• Push Anyway — ignore errors and run git push now.\n"
            + "• Leave Commit — keep the commit, do not push (you can retry later).\n"
            + "• Cancel — close this dialog with no action.";
        String[] options = {"Reset Commit", "Push Anyway", "Leave Commit", "Cancel"};
        int choice = com.intellij.openapi.ui.Messages.showDialog(
            project,
            message,
            "Pre-Push Compilation Errors",
            options,
            3,
            com.intellij.openapi.ui.Messages.getErrorIcon()
        );
        switch (choice) {
            case 0 -> {
                Runnable reset = buildAbortCommitAction(project, pushDetails);
                if (reset != null) reset.run();
            }
            case 1 -> executeAutoPush(project, pushDetails);
            case 2, 3 -> { /* no-op */ }
            default -> { /* dialog dismissed */ }
        }
    }

    private static void executeAutoPush(Project project, List<PushInfo> pushDetails) {
        java.util.LinkedHashSet<String> roots = new java.util.LinkedHashSet<>();
        for (PushInfo info : pushDetails) {
            for (VcsFullCommitDetails commit : info.getCommits()) {
                VirtualFile root = commit.getRoot();
                if (root != null) roots.add(root.getPath());
            }
        }
        if (roots.isEmpty()) {
            notify(
                project,
                "Auto-retry push skipped",
                "No repository root resolved from push details.",
                com.intellij.notification.NotificationType.WARNING
            );
            return;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(
            project, "Auto-Retrying Push", true, PerformInBackgroundOption.ALWAYS_BACKGROUND
        ) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                java.util.List<String> failures = new java.util.ArrayList<>();
                java.util.List<String> successes = new java.util.ArrayList<>();
                for (String root : roots) {
                    indicator.setText("git push: " + root);
                    String result = runGitPush(root);
                    if (result == null) successes.add(root);
                    else failures.add(root + ": " + result);
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    if (failures.isEmpty()) {
                        PrePushCompilationHandler.notify(
                            project,
                            "Push succeeded",
                            "Auto-retry pushed " + successes.size() + " repository/repositories.",
                            com.intellij.notification.NotificationType.INFORMATION
                        );
                    } else {
                        PrePushCompilationHandler.notify(
                            project,
                            "Auto-retry push failed",
                            String.join("\n", failures)
                                + (successes.isEmpty() ? "" : "\nSucceeded: " + String.join(", ", successes)),
                            com.intellij.notification.NotificationType.ERROR
                        );
                    }
                });
            }
        });
    }

    private static @org.jetbrains.annotations.Nullable String runGitPush(String repoRoot) {
        long startNanos = System.nanoTime();
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "push")
                .directory(new java.io.File(repoRoot))
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
            // Fail fast on credential prompt instead of hanging until timeout.
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("GIT_ASKPASS", "");
            pb.environment().put("SSH_ASKPASS", "");
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (out.length() > 0) out.append('\n');
                    out.append(line);
                }
            }
            if (!p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "timed out after 120s";
            }
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            LOG.info("git push at " + repoRoot + " exit=" + p.exitValue() + " elapsedMs=" + elapsedMs);
            return p.exitValue() == 0 ? null : out.toString().trim();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private static boolean requiresStrictSnapshotCheck(Project project, PushChangeSet changeSet) {
        if (!PrePushCheckerSettings.isStrictSnapshotGuardEnabled(project)) {
            return false;
        }
        return PrePushSnapshotGuard
            .analyzeSnapshotRisk(project, changeSet.getRelevantPaths())
            .shouldValidateSnapshot();
    }

    private static void scheduleBackgroundPrePushCheck(Project project, PushChangeSet changeSet, String key, List<PushInfo> pushDetails) {
        CompilationErrorService errorService = CompilationErrorService.getInstance(project);
        if (!errorService.markPrePushCheckRunning(key)) {
            if (errorService.isPrePushCheckRunning(key)) {
                notify(
                    project,
                    "Pre-push compilation already running",
                    "The current push was stopped while the background check finishes. Retry push after the notification appears.",
                    com.intellij.notification.NotificationType.INFORMATION
                );
            }
            return;
        }

        ProgressManager.getInstance().run(
            new Task.Backgroundable(
                project,
                "Pre-Push Compilation Checker",
                true,
                PerformInBackgroundOption.ALWAYS_BACKGROUND
            ) {
                private List<String> result = Collections.emptyList();

                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    try {
                        result = runFullPrePushCheck(project, changeSet, indicator);
                        errorService.recordPrePushResult(key, result);
                    } catch (ProcessCanceledException canceled) {
                        errorService.finishPrePushCheck(key);
                        throw canceled;
                    } catch (Throwable t) {
                        LOG.warn("Background pre-push compilation check failed", t);
                        result = Collections.singletonList("Pre-push compilation check failed: " + t.getMessage());
                        errorService.recordPrePushResult(key, result);
                    }
                }

                @Override
                public void onSuccess() {
                    handleBackgroundCompletion(project, result, pushDetails);
                }

                @Override
                public void onCancel() {
                    errorService.finishPrePushCheck(key);
                    PrePushCompilationHandler.notify(
                        project,
                        "Pre-push compilation canceled",
                        "The background pre-push compilation check was canceled. Retry push to start it again.",
                        com.intellij.notification.NotificationType.WARNING
                    );
                }
            }
        );
        notify(
            project,
            "Pre-push compilation started",
            "The current push was stopped so compilation can run in the IDE background instead of blocking the editor. Retry push after it finishes.",
            com.intellij.notification.NotificationType.INFORMATION
        );
    }

    private static List<String> runFullPrePushCheck(Project project, PushChangeSet changeSet, ProgressIndicator indicator) {
        indicator.setIndeterminate(true);
        indicator.setText("Checking pushed snapshot");
        PrePushSnapshotGuard.SnapshotValidationResult strictSnapshot =
            PrePushSnapshotGuard.validateHeadSnapshotIfNeeded(project, changeSet.getRelevantPaths(), indicator);
        if (strictSnapshot.wasChecked()) {
            return strictSnapshot.errors();
        }

        indicator.setText("Checking IDE problem cache");
        List<String> problemFiles = collectKnownProblemFiles(project, changeSet.getSourceFiles());
        if (!problemFiles.isEmpty()) {
            CompilationErrorService.getInstance(project).setErrors(problemFiles);
            return problemFiles;
        }

        indicator.setText(changeSet.requiresProjectBuild()
            ? "Compiling project"
            : "Compiling pushed modules");
        return changeSet.requiresProjectBuild()
            ? compileProject(project, indicator)
            : compileFiles(project, changeSet.getSourceFiles(), indicator);
    }

    private static void notify(
        Project project,
        String title,
        String message,
        com.intellij.notification.NotificationType type
    ) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("Pre-Push Compilation Checker")
                .createNotification(title, message, type)
                .notify(project);
        });
    }

    static List<String> formatCompilerMessages(Project project, CompilerMessage[] messages) {
        if (messages == null || messages.length == 0) {
            return Collections.singletonList("Compilation failed with an unknown compiler error.");
        }

        List<String> formattedMessages = new ArrayList<>(
            Math.min(messages.length, CompilationErrorService.MAX_RETAINED_ERRORS) + 1);
        StringBuilder builder = new StringBuilder(128);
        int omittedMessages = 0;
        for (CompilerMessage message : messages) {
            if (message == null) {
                continue;
            }

            if (formattedMessages.size() >= CompilationErrorService.MAX_RETAINED_ERRORS) {
                omittedMessages++;
                continue;
            }

            builder.setLength(0);
            VirtualFile file = message.getVirtualFile();
            builder.append('[');
            builder.append(file != null ? toDisplayPath(project, file) : "unknown");
            String prefix = message.getRenderTextPrefix();
            if (prefix != null && !prefix.isBlank()) {
                builder.append(' ').append(prefix.trim());
            }
            builder.append("] ");
            String msg = message.getMessage();
            builder.append(msg != null ? msg : "");
            formattedMessages.add(CompilationErrorService.compactError(builder.toString()));
        }

        if (omittedMessages > 0) {
            formattedMessages.add(CompilationErrorService.omittedErrorsMessage(omittedMessages));
        }

        if (formattedMessages.isEmpty()) {
            return Collections.singletonList("Compilation failed with an unknown compiler error.");
        }
        return formattedMessages;
    }

    static String toDisplayPath(Project project, VirtualFile file) {
        String projectBasePath = project.getBasePath();
        if (projectBasePath == null) {
            return file.getPath();
        }

        String relativePath = FileUtil.getRelativePath(projectBasePath, file.getPath(), '/');
        return relativePath != null ? relativePath : file.getPath();
    }

    private static boolean showDialog(
        Project project,
        ModalityState modalityState,
        String title,
        String header,
        List<String> items,
        Function<ProgressIndicator, List<String>> refreshAction,
        @org.jetbrains.annotations.Nullable Runnable abortCommitAction
    ) {
        boolean[] result = {false};
        ApplicationManager.getApplication().invokeAndWait(
            () -> {
                CompilationReportDialog dialog = new CompilationReportDialog(
                    project, title, header, items, refreshAction, abortCommitAction
                );
                result[0] = dialog.showAndGet();
            },
            modalityState
        );
        return result[0];
    }

    /**
     * Builds a runnable that soft-resets the commits being pushed, per repository.
     * Shows a confirmation dialog first. Changes stay in the working tree / index so
     * the user can fix the errors and re-commit (or amend).
     */
    private static Runnable buildAbortCommitAction(Project project, List<PushInfo> pushDetails) {
        // Count commits per repo root (system-dependent path).
        java.util.LinkedHashMap<String, Integer> perRoot = new java.util.LinkedHashMap<>();
        for (PushInfo pushInfo : pushDetails) {
            for (VcsFullCommitDetails commit : pushInfo.getCommits()) {
                VirtualFile root = commit.getRoot();
                if (root == null) continue;
                perRoot.merge(root.getPath(), 1, Integer::sum);
            }
        }
        if (perRoot.isEmpty()) return null;

        return () -> {
            String summary = perRoot.entrySet().stream()
                .map(e -> "  • " + e.getValue() + " commit(s) in "
                    + com.intellij.openapi.util.io.FileUtil.getLocationRelativeToUserHome(e.getKey()))
                .collect(java.util.stream.Collectors.joining("\n"));
            int choice = com.intellij.openapi.ui.Messages.showYesNoDialog(
                project,
                "Soft-reset the following commits?\n\n" + summary
                    + "\n\nYour changes will stay in the working tree so you can fix and re-commit.",
                "Abort Commit",
                "Abort Commit",
                "Cancel",
                com.intellij.openapi.ui.Messages.getWarningIcon()
            );
            if (choice != com.intellij.openapi.ui.Messages.YES) return;

            java.util.List<String> failures = new java.util.ArrayList<>();
            for (var entry : perRoot.entrySet()) {
                String root = entry.getKey();
                int count = entry.getValue();
                try {
                    Process p = new ProcessBuilder("git", "reset", "--soft", "HEAD~" + count)
                        .directory(new java.io.File(root))
                        .redirectErrorStream(true)
                        .start();
                    StringBuilder out = new StringBuilder();
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (out.length() > 0) out.append('\n');
                            out.append(line);
                        }
                    }
                    if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                        failures.add(root + ": timed out");
                        continue;
                    }
                    if (p.exitValue() != 0) {
                        failures.add(root + ": " + out.toString().trim());
                    } else {
                        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(root);
                        if (vf != null) vf.refresh(true, true);
                    }
                } catch (Exception ex) {
                    failures.add(root + ": " + ex.getMessage());
                }
            }

            String groupId = "Pre-Push Compilation Checker";
            com.intellij.notification.NotificationType type = failures.isEmpty()
                ? com.intellij.notification.NotificationType.INFORMATION
                : com.intellij.notification.NotificationType.ERROR;
            String message = failures.isEmpty()
                ? "Soft-reset complete. Your changes are back in the working tree."
                : "Some repositories could not be reset:\n" + String.join("\n", failures);
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup(groupId)
                .createNotification("Abort Commit", message, type)
                .notify(project);
        };
    }

    @FunctionalInterface
    private interface CompilationStarter {
        void start(CompileStatusNotification notification);
    }

    private static final class PushChangeSet {
        private final List<VirtualFile> sourceFiles;
        private final List<String> relevantPaths;
        private final List<String> commitIds;
        private final boolean hasRelevantChanges;
        private final boolean requiresProjectBuild;

        private PushChangeSet(
            List<VirtualFile> sourceFiles,
            List<String> relevantPaths,
            List<String> commitIds,
            boolean hasRelevantChanges,
            boolean requiresProjectBuild
        ) {
            this.sourceFiles = sourceFiles;
            this.relevantPaths = relevantPaths;
            this.commitIds = commitIds;
            this.hasRelevantChanges = hasRelevantChanges;
            this.requiresProjectBuild = requiresProjectBuild;
        }

        private List<VirtualFile> getSourceFiles() {
            return sourceFiles;
        }

        private List<String> getRelevantPaths() {
            return relevantPaths;
        }

        private boolean hasRelevantChanges() {
            return hasRelevantChanges;
        }

        private boolean requiresProjectBuild() {
            return requiresProjectBuild;
        }

        private String cacheKey() {
            StringBuilder builder = new StringBuilder();
            builder.append("projectBuild=").append(requiresProjectBuild).append('\n');
            new TreeSet<>(commitIds).forEach(id -> builder.append("commit=").append(id).append('\n'));
            new TreeSet<>(relevantPaths).forEach(path -> builder.append("path=").append(path).append('\n'));
            sourceFiles.stream()
                .filter(file -> file != null && file.isValid())
                .sorted(java.util.Comparator.comparing(VirtualFile::getPath))
                .forEach(file -> builder
                    .append("stamp=")
                    .append(file.getPath())
                    .append('@')
                    .append(file.getTimeStamp())
                    .append('\n'));
            return builder.toString();
        }
    }
}
