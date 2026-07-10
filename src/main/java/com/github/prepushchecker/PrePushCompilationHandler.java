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
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.DialogWrapper;
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

import java.awt.datatransfer.StringSelection;
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

    enum DialogOutcome { RESOLVED, ABORT, PUSH_ANYWAY }

    /** Outcome of the optional pre-compile rebase advisory in {@link #handle}. */
    private enum RebasePrecheckOutcome { PROCEED, REBASED, CANCEL }

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
                return okWithClipboard(project, pushDetails);
            }

            // Optional: fetch each root and prompt to rebase if the remote is ahead,
            // so compilation runs against the tree that will actually be pushed
            // rather than wasting cycles on a stale local snapshot. Off by default.
            RebasePrecheckOutcome precheck = runRebasePrecheckIfEnabled(project, pushDetails, indicator);
            if (precheck == RebasePrecheckOutcome.CANCEL) {
                return Result.ABORT;
            }
            if (precheck == RebasePrecheckOutcome.REBASED) {
                // The user chose to rebase; we launched it as a background task that
                // will recompile and push the integrated tree itself. Abort this
                // push attempt cleanly.
                return Result.ABORT;
            }

            CompilationErrorService errorService = CompilationErrorService.getInstance(project);
            Runnable abortCommitAction = buildAbortCommitAction(project, pushDetails);
            String prePushKey = changeSet.cacheKey();

            List<String> problemFiles = collectKnownProblemFiles(project, changeSet.getSourceFiles());
            if (!problemFiles.isEmpty()) {
                errorService.setErrors(problemFiles);
                DialogOutcome outcome = showDialog(
                    project,
                    indicator.getModalityState(),
                    "Push Blocked - IDE Problems Found",
                    "IntelliJ already reports problems in files included in this push. Fix them before pushing:",
                    problemFiles,
                    _ind -> collectKnownProblemFiles(project, changeSet.getSourceFiles()),
                    abortCommitAction
                );
                if (outcome == DialogOutcome.PUSH_ANYWAY) {
                    PrePushCheckerSettings.setForcePushBypass(project);
                    return okWithClipboard(project, pushDetails);
                }
                if (outcome != DialogOutcome.RESOLVED) return Result.ABORT;
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
                DialogOutcome outcome = showDialog(
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
                if (outcome == DialogOutcome.PUSH_ANYWAY) {
                    PrePushCheckerSettings.setForcePushBypass(project);
                    return okWithClipboard(project, pushDetails);
                }
                if (outcome == DialogOutcome.RESOLVED) errorService.setErrors(Collections.emptyList());
                return outcome == DialogOutcome.RESOLVED ? okWithClipboard(project, pushDetails) : Result.ABORT;
            }

            errorService.setErrors(Collections.emptyList());
            return okWithClipboard(project, pushDetails);
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
        List<PushInfo> pushDetails,
        Map<String, String> preCompileSnapshots,
        CompilationErrorService.CompileScopeKind scope
    ) {
        if (errors.isEmpty()) {
            recordCleanCommitsIfStillValid(project, preCompileSnapshots, scope);
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

    /**
     * Snapshot HEAD SHA per push root, but only for roots whose working tree is
     * fully clean. Roots with dirty trees, ongoing operations, or missing SHAs
     * are excluded — they will simply not contribute a ledger entry later.
     */
    private static Map<String, String> captureCleanRootSnapshots(List<PushInfo> pushDetails) {
        java.util.LinkedHashSet<String> roots = new java.util.LinkedHashSet<>();
        for (PushInfo info : pushDetails) {
            for (VcsFullCommitDetails commit : info.getCommits()) {
                VirtualFile root = commit.getRoot();
                if (root != null) roots.add(root.getPath());
            }
        }
        if (roots.isEmpty()) return Collections.emptyMap();
        Map<String, String> snapshots = new java.util.LinkedHashMap<>(roots.size() * 2);
        for (String root : roots) {
            if (!GitOperations.isWorkingTreeFullyClean(root)) continue;
            String sha = GitOperations.headSha(root);
            if (sha != null) snapshots.put(root, sha);
        }
        return snapshots;
    }

    /**
     * Record clean ledger entries for every captured pre-snapshot whose root
     * is still at the same HEAD with a clean working tree. Any root that
     * mutated during compile is silently dropped — better to lose a cache
     * entry than to record a stale one.
     */
    private static void recordCleanCommitsIfStillValid(
        Project project,
        Map<String, String> preSnapshots,
        CompilationErrorService.CompileScopeKind scope
    ) {
        if (preSnapshots.isEmpty()) return;
        CompilationErrorService service = CompilationErrorService.getInstance(project);
        for (Map.Entry<String, String> entry : preSnapshots.entrySet()) {
            String root = entry.getKey();
            String preSha = entry.getValue();
            String postSha = GitOperations.headSha(root);
            if (postSha != null && postSha.equals(preSha)
                && GitOperations.isWorkingTreeFullyClean(root)) {
                service.recordCleanCommit(root, preSha, scope);
            }
        }
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
            case 1 -> {
                PrePushCheckerSettings.setForcePushBypass(project);
                executeAutoPush(project, pushDetails);
            }
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
            project, "Auto-Retrying Push", true
        ) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                java.util.List<String> otherFailures = new java.util.ArrayList<>();
                java.util.List<String> successes = new java.util.ArrayList<>();
                // roots that failed because the remote is ahead (rebase/merge needed)
                java.util.LinkedHashMap<String, String> rebaseNeeded = new java.util.LinkedHashMap<>();
                for (String root : roots) {
                    indicator.setText("git push: " + root);
                    String result = runGitPush(root);
                    if (result == null) {
                        successes.add(root);
                    } else if (isRebaseRequired(result)) {
                        rebaseNeeded.put(root, result);
                    } else {
                        otherFailures.add(root + ": " + result);
                    }
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;

                    // Show error notification for non-rebase failures
                    if (!otherFailures.isEmpty()) {
                        PrePushCompilationHandler.notify(
                            project,
                            "Auto-retry push failed",
                            String.join("\n", otherFailures)
                                + (successes.isEmpty() ? "" : "\nSucceeded: " + String.join(", ", successes)),
                            com.intellij.notification.NotificationType.ERROR
                        );
                    } else if (rebaseNeeded.isEmpty()) {
                        PrePushCompilationHandler.notify(
                            project,
                            "Push succeeded",
                            "Auto-retry pushed " + successes.size() + " repository/repositories.",
                            com.intellij.notification.NotificationType.INFORMATION
                        );
                    }

                    // Prompt user per root that needs rebase/merge
                    for (var entry : rebaseNeeded.entrySet()) {
                        showRebaseOrMergeDialog(project, entry.getKey(), entry.getValue());
                    }
                });
            }
        });
    }

    /**
     * Detects whether any push root has incoming remote commits and, if so,
     * offers the user the choice to rebase (and recompile) before this push
     * proceeds. Cheap when no roots are behind (one fetch + one rev-list per
     * root), but a network round-trip on every push — hence opt-in via
     * {@link PrePushCheckerSettings#isRebasePrecheckEnabled}.
     *
     * <p>Returns {@link RebasePrecheckOutcome#PROCEED} when the setting is
     * disabled, every root is up-to-date, or the user chose to compile
     * against the local tree anyway. Returns {@link RebasePrecheckOutcome#REBASED}
     * after launching the existing rebase-and-push flow for each behind root.
     * Returns {@link RebasePrecheckOutcome#CANCEL} when the user cancelled.
     */
    private static RebasePrecheckOutcome runRebasePrecheckIfEnabled(
        Project project,
        List<PushInfo> pushDetails,
        ProgressIndicator indicator
    ) {
        if (!PrePushCheckerSettings.isRebasePrecheckEnabled(project)) {
            return RebasePrecheckOutcome.PROCEED;
        }

        java.util.LinkedHashSet<String> roots = new java.util.LinkedHashSet<>();
        for (PushInfo info : pushDetails) {
            for (VcsFullCommitDetails commit : info.getCommits()) {
                VirtualFile r = commit.getRoot();
                if (r != null) roots.add(r.getPath());
            }
        }
        if (roots.isEmpty()) return RebasePrecheckOutcome.PROCEED;

        // (root -> remote-ahead count). Roots that fail to fetch or have no upstream
        // contribute 0, which keeps this purely advisory.
        Map<String, Integer> behind = new LinkedHashMap<>();
        for (String root : roots) {
            indicator.checkCanceled();
            indicator.setText("Pre-push: fetching " + root);
            GitOperations.fetchQuiet(root);
            int ahead = GitOperations.remoteAheadCount(root);
            if (ahead > 0) behind.put(root, ahead);
        }
        if (behind.isEmpty()) return RebasePrecheckOutcome.PROCEED;

        StringBuilder msg = new StringBuilder();
        msg.append("The remote has new commit(s) on the following root(s):\n\n");
        behind.forEach((root, count) ->
            msg.append("  • ").append(count).append(" commit(s) in ").append(root).append('\n'));
        msg.append("\nCompiling against the current tree may miss bugs caused by the remote\n");
        msg.append("commits (semantic merge conflicts). Rebase now?\n\n");
        msg.append("• Rebase & Push — pull --rebase per root, then compile the integrated\n");
        msg.append("    tree, then push. Recommended.\n");
        msg.append("• Compile As-Is — skip the rebase and compile the current local tree.\n");
        msg.append("• Cancel — abort this push.");

        int[] choiceHolder = {-1};
        ModalityState modality = indicator.getModalityState();
        if (modality == null) modality = ModalityState.defaultModalityState();
        ApplicationManager.getApplication().invokeAndWait(() -> {
            choiceHolder[0] = com.intellij.openapi.ui.Messages.showDialog(
                project,
                msg.toString(),
                "Remote Has New Commits",
                new String[]{"Rebase & Push", "Compile As-Is", "Cancel"},
                0,
                com.intellij.openapi.ui.Messages.getWarningIcon()
            );
        }, modality);

        int choice = choiceHolder[0];
        if (choice == 0) {
            // Launch rebase-and-push per behind root. recompileThenPush re-validates
            // the integrated tree and pushes. The current PrePushHandler invocation
            // aborts so the IDE-driven push does not race with the rebase flow.
            for (String root : behind.keySet()) {
                executeRebaseAndPush(project, root);
            }
            return RebasePrecheckOutcome.REBASED;
        }
        if (choice == 1) {
            return RebasePrecheckOutcome.PROCEED;
        }
        return RebasePrecheckOutcome.CANCEL;
    }

    private static boolean isRebaseRequired(String gitOutput) {
        if (gitOutput == null) return false;
        String lower = gitOutput.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("non-fast-forward")
            || lower.contains("fetch first")
            || lower.contains("the tip of your current branch is behind")
            || lower.contains("the remote contains work that you do not have locally");
    }

    private static void showRebaseOrMergeDialog(Project project, String root, String errorOutput) {
        String shortRoot = new java.io.File(root).getName();
        String message = "Push to '" + shortRoot + "' was rejected because the remote contains newer commits.\n\n"
            + "How would you like to resolve this?\n\n"
            + "• Rebase & Push — rebase your commits on top of the remote, then push.\n"
            + "• Merge & Push — merge remote changes into your branch, then push.\n"
            + "• Force Push — force-push with lease (overwrites remote; use with caution).\n"
            + "• Cancel — do nothing.\n\n"
            + "Git output:\n" + errorOutput;
        String[] options = {"Rebase & Push", "Merge & Push", "Force Push", "Cancel"};
        int choice = com.intellij.openapi.ui.Messages.showDialog(
            project,
            message,
            "Push Rejected — Remote Has Newer Commits",
            options,
            0,
            com.intellij.openapi.ui.Messages.getWarningIcon()
        );
        switch (choice) {
            case 0 -> executeRebaseAndPush(project, root);
            case 1 -> executeMergeAndPush(project, root);
            case 2 -> executeForcePush(project, root);
            default -> { /* cancel / dialog dismissed */ }
        }
    }

    private static void executeRebaseAndPush(Project project, String root) {
        ProgressManager.getInstance().run(new Task.Backgroundable(
            project, "Rebasing & Pushing", true
        ) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("git pull --rebase: " + root);
                String pullResult = runGitCommand(root, "git", "pull", "--rebase");
                if (pullResult != null) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (project.isDisposed()) return;
                        PrePushCompilationHandler.notify(project, "Rebase failed",
                            root + ": " + pullResult + "\n\nResolve conflicts manually and push again.",
                            com.intellij.notification.NotificationType.ERROR);
                    });
                    refreshVfs(root);
                    return;
                }
                refreshVfs(root);
                recompileThenPush(project, root, "rebase", indicator);
            }
        });
    }

    private static void executeMergeAndPush(Project project, String root) {
        ProgressManager.getInstance().run(new Task.Backgroundable(
            project, "Merging & Pushing", true
        ) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("git pull --no-rebase: " + root);
                // --no-edit + GIT_MERGE_AUTOEDIT=no (set in runGitCommand env) keep the
                // merge non-interactive when git would otherwise open an editor.
                String pullResult = runGitCommand(root, "git", "pull", "--no-rebase", "--no-edit");
                if (pullResult != null) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (project.isDisposed()) return;
                        PrePushCompilationHandler.notify(project, "Merge failed",
                            root + ": " + pullResult + "\n\nResolve conflicts manually and push again.",
                            com.intellij.notification.NotificationType.ERROR);
                    });
                    refreshVfs(root);
                    return;
                }
                refreshVfs(root);
                recompileThenPush(project, root, "merge", indicator);
            }
        });
    }

    /**
     * After a successful {@code git pull --rebase} / {@code --no-rebase}, validate
     * the integrated tree before pushing. The original pre-push compile is now
     * stale because the post-pull HEAD may include remote commits that interact
     * with local changes in ways individual side-by-side compiles cannot detect
     * (semantic merge conflicts).
     *
     * <p>Flow:
     * <ol>
     *   <li>Resolve post-pull HEAD SHA and verify the working tree is fully clean.
     *       If the WT is dirty after pull, refuse to auto-push — a passing compile
     *       could be due to uncommitted local edits that are not part of the tree
     *       being pushed.</li>
     *   <li>If the clean-commit ledger already has a {@code PROJECT}-scope clean
     *       entry for this SHA, skip recompile and push immediately.</li>
     *   <li>Otherwise run a project-scope compile against the integrated tree.
     *       Project scope is the safest default — file-scope reconstruction from
     *       {@code @{u}..HEAD} can miss files that broke only because of the
     *       remote-side change.</li>
     *   <li>On clean compile, verify HEAD did not move and the WT is still clean,
     *       then push. On any errors or state drift, surface a notification and
     *       leave the user to retry.</li>
     * </ol>
     */
    private static void recompileThenPush(
        Project project,
        String root,
        String operationLabel,
        ProgressIndicator indicator
    ) {
        indicator.setText("Validating post-" + operationLabel + " tree: " + root);
        String preSha = GitOperations.headSha(root);
        if (preSha == null) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                PrePushCompilationHandler.notify(project, "Push aborted",
                    root + ": could not resolve HEAD after " + operationLabel
                        + ". Push manually after verifying repository state.",
                    com.intellij.notification.NotificationType.ERROR);
            });
            return;
        }
        if (!GitOperations.isWorkingTreeFullyClean(root)) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                PrePushCompilationHandler.notify(project, "Push aborted — working tree not clean",
                    root + ": the working tree is not clean after " + operationLabel
                        + " (uncommitted changes, untracked files, or an in-progress operation). "
                        + "Commit/stash/resolve and push again.",
                    com.intellij.notification.NotificationType.WARNING);
            });
            return;
        }

        CompilationErrorService errorService = CompilationErrorService.getInstance(project);
        CompilationErrorService.CompileScopeKind ledgerHit =
            errorService.tryReuseCleanCommit(root, preSha);
        if (ledgerHit == CompilationErrorService.CompileScopeKind.PROJECT) {
            LOG.info("Post-" + operationLabel + " ledger hit for " + root + " @ " + preSha
                + " — skipping recompile.");
            pushAfterStateVerify(project, root, preSha, operationLabel, indicator);
            return;
        }

        // Save any open documents so the compile sees the same content as the
        // recorded ledger entry (which is gated on a clean WT).
        ApplicationManager.getApplication().invokeAndWait(
            () -> FileDocumentManager.getInstance().saveAllDocuments(),
            ModalityState.defaultModalityState()
        );

        indicator.setText("Compiling integrated tree: " + root);
        List<String> errors;
        try {
            errors = compileProject(project, indicator);
        } catch (ProcessCanceledException pce) {
            throw pce;
        } catch (Throwable t) {
            LOG.warn("Post-" + operationLabel + " compile failed", t);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                PrePushCompilationHandler.notify(project, "Post-" + operationLabel + " compile failed",
                    root + ": " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()),
                    com.intellij.notification.NotificationType.ERROR);
            });
            return;
        }

        if (!errors.isEmpty()) {
            errorService.setErrors(errors);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                PrePushCompilationHandler.notify(project,
                    "Push blocked — integrated tree has compile errors",
                    root + ": " + errors.size() + " error(s) after " + operationLabel + ". "
                        + "See the Pre-Push Compilation Checker tool window. Fix and push again.",
                    com.intellij.notification.NotificationType.ERROR);
            });
            return;
        }

        // Verify state did not drift during the compile.
        String postSha = GitOperations.headSha(root);
        if (postSha == null || !postSha.equals(preSha)
            || !GitOperations.isWorkingTreeFullyClean(root)) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                PrePushCompilationHandler.notify(project, "Push aborted — repository state changed",
                    root + ": HEAD or working tree changed during compile. Push manually after verifying.",
                    com.intellij.notification.NotificationType.WARNING);
            });
            return;
        }

        errorService.recordCleanCommit(root, preSha,
            CompilationErrorService.CompileScopeKind.PROJECT);
        pushAfterStateVerify(project, root, preSha, operationLabel, indicator);
    }

    /**
     * Final pre-push guard: re-verify HEAD/WT immediately before pushing so a
     * local mutation between compile and push cannot smuggle an unvalidated
     * tree out. Remote-side races (a teammate pushing between our compile and
     * our push) are still possible, but {@code git push} will reject those and
     * the user re-enters the existing rebase/merge dialog.
     */
    private static void pushAfterStateVerify(
        Project project,
        String root,
        String expectedSha,
        String operationLabel,
        ProgressIndicator indicator
    ) {
        String currentSha = GitOperations.headSha(root);
        if (currentSha == null || !currentSha.equals(expectedSha)
            || !GitOperations.isWorkingTreeFullyClean(root)) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                PrePushCompilationHandler.notify(project, "Push aborted — repository state changed",
                    root + ": HEAD or working tree changed before push. Verify and push manually.",
                    com.intellij.notification.NotificationType.WARNING);
            });
            return;
        }
        indicator.setText("git push: " + root);
        String pushResult = runGitPush(root);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            if (pushResult == null) {
                PrePushCompilationHandler.notify(project, "Push succeeded",
                    "Validated and pushed " + root + " after " + operationLabel + ".",
                    com.intellij.notification.NotificationType.INFORMATION);
            } else {
                PrePushCompilationHandler.notify(project, "Push failed after " + operationLabel,
                    root + ": " + pushResult,
                    com.intellij.notification.NotificationType.ERROR);
            }
        });
    }

    private static void executeForcePush(Project project, String root) {
        ProgressManager.getInstance().run(new Task.Backgroundable(
            project, "Force Pushing", true
        ) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("git push --force-with-lease: " + root);
                PrePushCheckerSettings.setForcePushBypass(project);
                String result = runGitCommand(root, "git", "push", "--force-with-lease");
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    if (result == null) {
                        PrePushCompilationHandler.notify(project, "Force push succeeded",
                            "Force-pushed " + root + " successfully.",
                            com.intellij.notification.NotificationType.INFORMATION);
                    } else {
                        PrePushCompilationHandler.notify(project, "Force push failed",
                            root + ": " + result,
                            com.intellij.notification.NotificationType.ERROR);
                    }
                });
            }
        });
    }

    /**
     * Runs an arbitrary git command and returns null on success or the combined output on failure.
     * Mirrors the same timeout/credential-guard behaviour as {@link #runGitPush}.
     */
    private static @org.jetbrains.annotations.Nullable String runGitCommand(String repoRoot, String... command) {
        long startNanos = System.nanoTime();
        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                .directory(new java.io.File(repoRoot))
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
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
            LOG.info(String.join(" ", command) + " at " + repoRoot
                + " exit=" + p.exitValue() + " elapsedMs=" + elapsedMs);
            return p.exitValue() == 0 ? null : out.toString().trim();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private static void refreshVfs(String repoRoot) {
        // Synchronous refresh: the integrated post-pull tree must be visible to
        // the IDE before we hand it to the compiler. An async refresh would let
        // the compile start against a stale VFS snapshot.
        LocalFileSystem.getInstance().refreshIoFiles(
            java.util.Collections.singletonList(new java.io.File(repoRoot)), false, true, null);
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
                true
            ) {
                private List<String> result = Collections.emptyList();
                private Map<String, String> preCompileSnapshots = Collections.emptyMap();
                private CompilationErrorService.CompileScopeKind scopeKind =
                    CompilationErrorService.CompileScopeKind.FILE_SCOPE;

                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    // Capture pre-compile per-root snapshot so we can decide later
                    // whether the clean ledger may be updated. Only roots with a
                    // clean WT at this moment are eligible.
                    preCompileSnapshots = captureCleanRootSnapshots(pushDetails);
                    scopeKind = changeSet.requiresProjectBuild()
                        ? CompilationErrorService.CompileScopeKind.PROJECT
                        : CompilationErrorService.CompileScopeKind.FILE_SCOPE;
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
                    handleBackgroundCompletion(project, result, pushDetails,
                        preCompileSnapshots, scopeKind);
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

    private static Result okWithClipboard(
        @NotNull Project project,
        @NotNull List<PushInfo> pushDetails
    ) {
        copyHeadShaToClipboard(project, pushDetails);
        return Result.OK;
    }

    private static void copyHeadShaToClipboard(
        @NotNull Project project,
        @NotNull List<PushInfo> pushDetails
    ) {
        if (!PrePushCheckerSettings.isCopyCommitShaEnabled(project)) return;
        if (PrePushCheckerSettings.getCopyCommitShaTrigger(project)
                != PrePushCheckerSettings.ShaTrigger.AFTER_PUSH) return;

        PrePushCheckerSettings.ShaFormat format = PrePushCheckerSettings.getCopyCommitShaFormat(project);
        // Resolve the pushed tip directly from the push payload first. This is the
        // authoritative SHA for the push and avoids relying on any repository-root
        // mapping when the push spans nested repos or multiple repositories.
        String pushedSha = pushedTipSha(pushDetails);

        // Resolve the git repository roots that own the pushed commits using IntelliJ's
        // own Git integration on the EDT/model thread BEFORE going to the pooled thread.
        // Mapping each push root through GitRepositoryManager keeps the fallback rooted
        // at the actual repository the user pushed, not an enclosing parent repo.
        List<String> pushRepoRoots = resolvePushRepositoryRoots(project, pushDetails);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            // Primary: use the pushed tip reported by the VCS log. This is the exact
            // commit id that git4idea says was pushed, so it stays correct even when
            // multiple repos are involved or a repository-root lookup is ambiguous.
            String sha = pushedSha;

            // Fallback: read HEAD from the resolved repository roots only if the push
            // payload could not be reduced to a valid commit id.
            if (sha == null) {
                for (String root : pushRepoRoots) {
                    sha = GitOperations.headSha(root);
                    if (sha != null) break;
                }
            }
            // Final fallback: project base path (single-repo projects where the push
            // root could not be resolved from the push details / repository model).
            if (sha == null) {
                String basePath = project.getBasePath();
                if (basePath != null) sha = GitOperations.headSha(basePath);
            }
            if (sha == null) return;
            final String displaySha = format == PrePushCheckerSettings.ShaFormat.SHORT
                ? sha.substring(0, 7) : sha;
            ApplicationManager.getApplication().invokeLater(() -> {
                CopyPasteManager.getInstance().setContents(new StringSelection(displaySha));
                notify(project, "Commit SHA Copied", displaySha,
                    com.intellij.notification.NotificationType.INFORMATION);
            });
        });
    }

    /**
     * Resolves the git repository root path(s) that own the pushed commits, using
     * IntelliJ's own Git integration ({@link git4idea.repo.GitRepositoryManager}) rather
     * than trusting {@link VcsFullCommitDetails#getRoot()} directly.
     *
     * <p>{@code commit.getRoot()} can point at a submodule directory (for submodule-update
     * commits), whose {@code HEAD} is the submodule tip — not the repository the user
     * pushed. We therefore use the exact repository root when git4idea can map it,
     * preserving push order and de-duplicating repeated roots. Falls back to the raw
     * {@code commit.getRoot()} path when the model cannot map a root, keeping behaviour
     * robust and repository-agnostic.</p>
     */
    @NotNull
    private static List<String> resolvePushRepositoryRoots(
        @NotNull Project project,
        @NotNull List<PushInfo> pushDetails
    ) {
        git4idea.repo.GitRepositoryManager repoManager =
            git4idea.repo.GitRepositoryManager.getInstance(project);
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        for (PushInfo info : pushDetails) {
            for (VcsFullCommitDetails commit : info.getCommits()) {
                VirtualFile root = commit.getRoot();
                if (root == null) continue;
                String repoRoot = CommitShaClipboardCheckinHandler.resolveRepositoryRoot(repoManager, root);
                if (repoRoot != null) {
                    roots.add(repoRoot);
                }
            }
        }
        return new ArrayList<>(roots);
    }

    /**
     * Returns the SHA of the <em>actual pushed tip</em> across all {@code pushDetails}
     * by reading {@link VcsFullCommitDetails#getId()} directly — no git subprocess and
     * no dependency on which root is returned by {@link VcsFullCommitDetails#getRoot()}.
     *
     * <p>The tip is selected <b>topologically</b>: the commit whose id is not listed as
     * a parent of any other commit in the push set. This is the true branch head being
     * pushed. The previous implementation picked the commit with the highest
     * {@link VcsFullCommitDetails#getCommitTime() committer timestamp}, which is <em>not</em>
     * guaranteed to be the tip — rebases, amends, cherry-picks and clock skew can give an
     * ancestor a later commit time, causing the wrong (non-tip) SHA to be copied.
     *
     * <p>Falls back to the highest-timestamp commit only when topology cannot single out a
     * unique tip (e.g. the push set is a disjoint/partial view). Returns {@code null} when
     * the list is empty or no valid 40-char hex SHA is found.
     */
    @org.jetbrains.annotations.Nullable
    static String pushedTipSha(@NotNull List<PushInfo> pushDetails) {
        List<CommitNode> nodes = new ArrayList<>();
        for (PushInfo info : pushDetails) {
            for (VcsFullCommitDetails commit : info.getCommits()) {
                List<String> parents = new ArrayList<>();
                for (var parent : commit.getParents()) {
                    parents.add(parent.asString());
                }
                nodes.add(new CommitNode(commit.getId().asString(), parents, commit.getCommitTime()));
            }
        }
        return normalizeSha(selectTipSha(nodes));
    }

    /** Minimal DAG node used by {@link #selectTipSha} — decouples tip selection from git4idea. */
    record CommitNode(@NotNull String id, @NotNull List<String> parentIds, long commitTime) {}

    /**
     * Selects the topological tip from {@code nodes}: the commit whose id is not a parent
     * of any other commit in the set. Falls back to the highest-timestamp commit when
     * topology does not single out exactly one tip. Returns {@code null} for an empty set.
     */
    @org.jetbrains.annotations.Nullable
    static String selectTipSha(@NotNull List<CommitNode> nodes) {
        if (nodes.isEmpty()) return null;

        Set<String> parentIds = new java.util.HashSet<>();
        for (CommitNode n : nodes) parentIds.addAll(n.parentIds());

        CommitNode tip = null;
        int tipCount = 0;
        for (CommitNode n : nodes) {
            if (!parentIds.contains(n.id())) {
                tip = n;
                tipCount++;
            }
        }

        if (tipCount != 1) {
            tip = null;
            for (CommitNode n : nodes) {
                if (tip == null || n.commitTime() > tip.commitTime()) {
                    tip = n;
                }
            }
        }
        return tip == null ? null : tip.id();
    }

    /** Returns {@code sha} when it is a valid 40-char lower/upper hex string, else {@code null}. */
    @org.jetbrains.annotations.Nullable
    private static String normalizeSha(@org.jetbrains.annotations.Nullable String sha) {
        if (sha == null || sha.length() != 40) return null;
        for (int i = 0; i < sha.length(); i++) {
            char c = sha.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return null;
            }
        }
        return sha;
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

    private static DialogOutcome showDialog(
        Project project,
        ModalityState modalityState,
        String title,
        String header,
        List<String> items,
        Function<ProgressIndicator, List<String>> refreshAction,
        @org.jetbrains.annotations.Nullable Runnable abortCommitAction
    ) {
        DialogOutcome[] result = {DialogOutcome.ABORT};
        ApplicationManager.getApplication().invokeAndWait(
            () -> {
                CompilationReportDialog dialog = new CompilationReportDialog(
                    project, title, header, items, refreshAction, abortCommitAction
                );
                dialog.show();
                int exitCode = dialog.getExitCode();
                if (exitCode == DialogWrapper.OK_EXIT_CODE) {
                    result[0] = DialogOutcome.RESOLVED;
                } else if (exitCode == CompilationReportDialog.PUSH_ANYWAY_EXIT_CODE) {
                    result[0] = DialogOutcome.PUSH_ANYWAY;
                } else {
                    result[0] = DialogOutcome.ABORT;
                }
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
