package com.github.prepushchecker;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class PrePushSnapshotGuard {
    private static final Logger LOG = Logger.getInstance(PrePushSnapshotGuard.class);
    private static final int MAX_SNAPSHOT_OUTPUT_LINES = 25;
    private static final long WAIT_SLICE_MILLIS = 250L;
    private static final long GIT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60);
    private static final long SNAPSHOT_BUILD_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private PrePushSnapshotGuard() {
    }

    static @NotNull SnapshotValidationResult validateHeadSnapshotIfNeeded(
        @NotNull Project project,
        @NotNull Collection<String> pushedPaths,
        @Nullable ProgressIndicator indicator
    ) {
        if (!PrePushCheckerSettings.isStrictSnapshotGuardEnabled(project)) {
            return SnapshotValidationResult.notChecked();
        }

        PushSnapshotRisk risk = analyzeSnapshotRisk(project, pushedPaths);
        if (!risk.shouldValidateSnapshot()) {
            return SnapshotValidationResult.notChecked();
        }
        LOG.info("Strict A/B dependency check escalated to clean snapshot validation: " + risk.reason());

        indicatorCheckCanceled(indicator);

        List<String> unpushedPaths = new ArrayList<>(risk.localChanges().size());
        for (LocalRelevantChange change : risk.localChanges()) {
            unpushedPaths.add(change.path());
        }
        List<String> symbolicMatches = SymbolicAbCheck.detect(project, risk.pushedPaths(), unpushedPaths);
        if (!symbolicMatches.isEmpty()) {
            LOG.info("Strict A/B symbolic check found " + symbolicMatches.size()
                + " reference(s) to unpushed-only declarations.");
            return SnapshotValidationResult.checked(symbolicMatches);
        }

        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return SnapshotValidationResult.checked(List.of(
                "[snapshot] Strict A/B dependency check could not resolve the project base path."));
        }

        Path tempRoot = null;
        Path worktree = null;
        try {
            Path projectRoot = Path.of(basePath);
            tempRoot = Files.createTempDirectory("prepushchecker-snapshot-");
            worktree = tempRoot.resolve("worktree");

            CommandResult checkout = runCommand(
                projectRoot,
                List.of("git", "worktree", "add", "--detach", worktree.toString(), "HEAD"),
                GIT_TIMEOUT_MILLIS,
                indicator,
                tempRoot.resolve("worktree-add.log")
            );
            if (!checkout.succeeded()) {
                if (PrePushCheckerSettings.isStashSnapshotFallbackEnabled(project)) {
                    LOG.info("Strict A/B worktree validation unavailable; using explicit stash fallback.");
                    return validateWithStashFallback(
                        project, projectRoot, risk.pushedPaths(), indicator, tempRoot);
                }
                LOG.info("Strict A/B dependency check skipped because a clean worktree could not be created.");
                return SnapshotValidationResult.notChecked();
            }

            CommandResult build = runSnapshotBuild(project, worktree, risk.pushedPaths(), indicator, tempRoot);
            if (build.skipped()) {
                LOG.info("Strict A/B dependency check skipped because no runnable snapshot build command was found.");
                return SnapshotValidationResult.notChecked();
            }
            if (build.succeeded()) {
                LOG.info("Strict A/B dependency check passed against a clean HEAD snapshot.");
                return SnapshotValidationResult.checked(List.of());
            }

            List<String> failureMessages = snapshotBuildFailureMessages(
                "Strict A/B dependency check failed when compiling HEAD without local changes.",
                build,
                project,
                worktree,
                risk.pushedPaths()
            );
            if (failureMessages.isEmpty()) {
                LOG.info("Strict A/B snapshot compile failed, but no parsed errors matched pushed files.");
                return SnapshotValidationResult.notChecked();
            }
            return SnapshotValidationResult.checked(failureMessages);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Strict A/B dependency check failed to run", e);
            return SnapshotValidationResult.checked(List.of(
                "[snapshot] Strict A/B dependency check could not run: " + e.getMessage()));
        } finally {
            cleanupWorktree(basePath, worktree, tempRoot);
        }
    }

    static @NotNull PushSnapshotRisk analyzeSnapshotRisk(
        @NotNull Project project,
        @NotNull Collection<String> pushedPaths
    ) {
        List<String> relevantPushedPaths = collectRelevantPushedPaths(project, pushedPaths);
        List<LocalRelevantChange> localChanges = collectRelevantLocalChanges(project);

        if (localChanges.isEmpty()) {
            return PushSnapshotRisk.notRisky(relevantPushedPaths, localChanges, "no local source/build changes");
        }
        if (!pushedPaths.isEmpty() && relevantPushedPaths.isEmpty()) {
            return PushSnapshotRisk.notRisky(relevantPushedPaths, localChanges, "no pushed source/build changes");
        }
        if (relevantPushedPaths.isEmpty()) {
            return PushSnapshotRisk.risky(relevantPushedPaths, localChanges, "pushed file list is unavailable");
        }

        List<LocalRelevantChange> unpushedLocalChanges =
            excludePushedLocalChanges(localChanges, relevantPushedPaths);
        if (unpushedLocalChanges.isEmpty()) {
            return PushSnapshotRisk.notRisky(
                relevantPushedPaths,
                List.of(),
                "all local source/build changes are included in the pushed snapshot"
            );
        }

        if (containsBuildFile(relevantPushedPaths)) {
            return PushSnapshotRisk.risky(
                relevantPushedPaths,
                unpushedLocalChanges,
                "pushed build file changes affect compile graph while local changes remain unpushed"
            );
        }
        for (LocalRelevantChange localChange : unpushedLocalChanges) {
            if (localChange.isBuildFile()) {
                return PushSnapshotRisk.risky(relevantPushedPaths, unpushedLocalChanges, "local build file changes can mask pushed snapshot failures");
            }
            if (localChange.isDeleteOrMove()) {
                return PushSnapshotRisk.risky(relevantPushedPaths, unpushedLocalChanges, "local delete/move changes can mask pushed snapshot failures");
            }
        }

        ModuleRisk moduleRisk = computeModuleRisk(project, relevantPushedPaths, unpushedLocalChanges);
        return moduleRisk.risky()
            ? PushSnapshotRisk.risky(relevantPushedPaths, unpushedLocalChanges, moduleRisk.reason())
            : PushSnapshotRisk.notRisky(relevantPushedPaths, unpushedLocalChanges, moduleRisk.reason());
    }

    private static @NotNull List<String> collectRelevantPushedPaths(
        @NotNull Project project,
        @NotNull Collection<String> pushedPaths
    ) {
        Set<String> relevantPushedPaths = new LinkedHashSet<>();
        for (String path : pushedPaths) {
            String displayPath = toProjectRelativePath(project, path == null ? "" : path);
            if (!displayPath.isEmpty() && PushValidationPaths.isRelevantPath(displayPath)) {
                relevantPushedPaths.add(displayPath);
            }
        }
        return List.copyOf(relevantPushedPaths);
    }

    private static @NotNull List<LocalRelevantChange> collectRelevantLocalChanges(@NotNull Project project) {
        Set<LocalRelevantChange> relevantLocalChanges = new LinkedHashSet<>();
        ChangeListManager changeListManager = ChangeListManager.getInstance(project);
        for (Change change : changeListManager.getAllChanges()) {
            addRelevantChange(project, change, relevantLocalChanges);
        }

        return List.copyOf(relevantLocalChanges);
    }

    private static @NotNull List<LocalRelevantChange> excludePushedLocalChanges(
        @NotNull List<LocalRelevantChange> localChanges,
        @NotNull Collection<String> relevantPushedPaths
    ) {
        List<LocalRelevantChange> unpushed = new ArrayList<>();
        for (LocalRelevantChange localChange : localChanges) {
            if (!isCoveredByPushedPaths(localChange.path(), relevantPushedPaths)) {
                unpushed.add(localChange);
            }
        }
        return List.copyOf(unpushed);
    }

    static boolean isCoveredByPushedPaths(
        @NotNull String localPath,
        @NotNull Collection<String> relevantPushedPaths
    ) {
        String normalizedLocalPath = PushValidationPaths.normalizePath(localPath);
        for (String pushedPath : relevantPushedPaths) {
            String normalizedPushedPath = PushValidationPaths.normalizePath(pushedPath);
            if (normalizedLocalPath.equals(normalizedPushedPath)
                || normalizedLocalPath.endsWith("/" + normalizedPushedPath)
                || normalizedPushedPath.endsWith("/" + normalizedLocalPath)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBuildFile(@NotNull Collection<String> paths) {
        for (String path : paths) {
            if (PushValidationPaths.isBuildFile(path)) {
                return true;
            }
        }
        return false;
    }

    private static @NotNull ModuleRisk computeModuleRisk(
        @NotNull Project project,
        @NotNull List<String> pushedPaths,
        @NotNull List<LocalRelevantChange> localChanges
    ) {
        return ApplicationManager.getApplication().runReadAction((Computable<ModuleRisk>) () -> {
            ProjectFileIndex index = ProjectFileIndex.getInstance(project);
            ModuleManager moduleManager = ModuleManager.getInstance(project);
            Set<Module> pushedModules = new LinkedHashSet<>();
            Set<Module> localModules = new LinkedHashSet<>();

            for (String pushedPath : pushedPaths) {
                if (!PushValidationPaths.isCompilableSource(pushedPath)) {
                    continue;
                }
                Module module = moduleForPath(project, index, pushedPath);
                if (module == null) {
                    return ModuleRisk.risky("pushed source module could not be resolved");
                }
                pushedModules.add(module);
            }

            for (LocalRelevantChange localChange : localChanges) {
                if (!localChange.isCompilableSource()) {
                    continue;
                }
                Module module = moduleForPath(project, index, localChange.path());
                if (module == null) {
                    return ModuleRisk.risky("local source module could not be resolved");
                }
                localModules.add(module);
            }

            if (pushedModules.isEmpty() || localModules.isEmpty()) {
                return ModuleRisk.notRisky("no source module overlap");
            }

            Set<Module> connectedModules = connectedModules(pushedModules, moduleManager);
            for (Module localModule : localModules) {
                if (connectedModules.contains(localModule)) {
                    return ModuleRisk.risky("local source changes are connected to pushed modules");
                }
            }
            return ModuleRisk.notRisky("local source changes are outside the pushed module graph");
        });
    }

    private static @NotNull Set<Module> connectedModules(
        @NotNull Set<Module> seedModules,
        @NotNull ModuleManager moduleManager
    ) {
        Set<Module> visited = new LinkedHashSet<>(seedModules);
        Deque<Module> queue = new ArrayDeque<>(seedModules);
        while (!queue.isEmpty()) {
            Module module = queue.removeFirst();
            for (Module dependency : ModuleRootManager.getInstance(module).getDependencies()) {
                if (visited.add(dependency)) {
                    queue.addLast(dependency);
                }
            }
            for (Module dependent : moduleManager.getModuleDependentModules(module)) {
                if (visited.add(dependent)) {
                    queue.addLast(dependent);
                }
            }
        }
        return visited;
    }

    @Nullable
    private static Module moduleForPath(
        @NotNull Project project,
        @NotNull ProjectFileIndex index,
        @NotNull String path
    ) {
        VirtualFile file = findVirtualFile(project, path);
        return file == null ? null : index.getModuleForFile(file, false);
    }

    @Nullable
    private static VirtualFile findVirtualFile(@NotNull Project project, @NotNull String path) {
        String normalized = PushValidationPaths.normalizePath(path);
        LocalFileSystem localFileSystem = LocalFileSystem.getInstance();
        VirtualFile file = localFileSystem.findFileByPath(normalized);
        if (file == null && project.getBasePath() != null) {
            file = localFileSystem.findFileByPath(project.getBasePath() + "/" + normalized);
        }
        return file != null && !file.isDirectory() ? file : null;
    }

    private static @NotNull CommandResult runSnapshotBuild(
        @NotNull Project project,
        @NotNull Path worktree,
        @NotNull Collection<String> pushedPaths,
        @Nullable ProgressIndicator indicator,
        @NotNull Path outputRoot
    ) throws IOException, InterruptedException {
        String override = System.getenv("PRE_PUSH_CHECKER_COMMAND");
        List<String> command;
        if (override != null && !override.isBlank()) {
            command = List.of("/bin/sh", "-c", override);
        } else {
            GitHookInstaller.BuildTool buildTool = GitHookInstaller.detectBuildTool(worktree.toString());
            command = resolveBuildCommand(worktree, buildCommand(buildTool, pushedPaths));
            if (command.isEmpty()) {
                return CommandResult.skippedResult();
            }
        }

        return runCommand(
            worktree,
            command,
            SNAPSHOT_BUILD_TIMEOUT_MILLIS,
            indicator,
            outputRoot.resolve("snapshot-build.log")
        );
    }

    private static @NotNull SnapshotValidationResult validateWithStashFallback(
        @NotNull Project project,
        @NotNull Path projectRoot,
        @NotNull Collection<String> pushedPaths,
        @Nullable ProgressIndicator indicator,
        @NotNull Path outputRoot
    ) throws IOException, InterruptedException {
        CommandResult stash = runCommand(
            projectRoot,
            List.of(
                "git",
                "stash",
                "push",
                "--include-untracked",
                "--message",
                "prepushchecker-strict-ab-snapshot"
            ),
            GIT_TIMEOUT_MILLIS,
            indicator,
            outputRoot.resolve("stash-push.log")
        );
        if (!stash.succeeded()) {
            return SnapshotValidationResult.checked(commandFailure(
                "Strict A/B stash fallback could not save local changes.",
                stash,
                project,
                projectRoot
            ));
        }
        if (!createdStash(stash.outputLines())) {
            return SnapshotValidationResult.notChecked();
        }

        CommandResult build = null;
        CommandResult restore = null;
        Exception restoreFailure = null;
        try {
            build = runSnapshotBuild(project, projectRoot, pushedPaths, indicator, outputRoot);
        } finally {
            try {
                restore = runCommand(
                    projectRoot,
                    List.of("git", "stash", "pop", "--index"),
                    GIT_TIMEOUT_MILLIS,
                    null,
                    outputRoot.resolve("stash-pop.log")
                );
            } catch (IOException | InterruptedException e) {
                restoreFailure = e;
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        if (restoreFailure != null) {
            return SnapshotValidationResult.checked(List.of(
                "[snapshot] Strict A/B stash fallback could not restore local changes: "
                    + restoreFailure.getMessage()));
        }
        if (restore != null && !restore.succeeded()) {
            return SnapshotValidationResult.checked(commandFailure(
                "Strict A/B stash fallback could not restore local changes.",
                restore,
                project,
                projectRoot
            ));
        }
        if (build == null || build.skipped()) {
            return SnapshotValidationResult.notChecked();
        }
        if (build.succeeded()) {
            LOG.info("Strict A/B stash fallback check passed against HEAD.");
            return SnapshotValidationResult.checked(List.of());
        }
        List<String> failureMessages = snapshotBuildFailureMessages(
            "Strict A/B stash fallback failed when compiling HEAD without local changes.",
            build,
            project,
            projectRoot,
            pushedPaths
        );
        if (failureMessages.isEmpty()) {
            LOG.info("Strict A/B stash fallback compile failed, but no parsed errors matched pushed files.");
            return SnapshotValidationResult.notChecked();
        }
        return SnapshotValidationResult.checked(failureMessages);
    }

    private static boolean createdStash(@NotNull List<String> outputLines) {
        for (String line : outputLines) {
            if (line != null && line.contains("Saved working directory")) {
                return true;
            }
        }
        return false;
    }

    static @NotNull List<String> resolveBuildCommand(
        @NotNull Path worktree,
        @NotNull List<String> command
    ) {
        if (command.isEmpty()) {
            return List.of();
        }

        String executable = command.get(0);
        List<String> args = command.subList(1, command.size());
        if (executable.startsWith("./")) {
            Path script = worktree.resolve(executable.substring(2));
            if (!Files.isRegularFile(script)) {
                return List.of();
            }

            List<String> resolved = new ArrayList<>(args.size() + 2);
            resolved.add("/bin/sh");
            resolved.add(script.toString());
            resolved.addAll(args);
            return resolved;
        }

        if (executable.contains("/") || executable.contains("\\")) {
            Path path = Path.of(executable);
            return Files.isExecutable(path) ? command : List.of();
        }

        Path resolvedExecutable = findExecutable(executable);
        if (resolvedExecutable == null) {
            return List.of();
        }

        List<String> resolved = new ArrayList<>(command.size());
        resolved.add(resolvedExecutable.toString());
        resolved.addAll(args);
        return resolved;
    }

    @Nullable
    private static Path findExecutable(@NotNull String executable) {
        Set<Path> searchDirs = new LinkedHashSet<>();
        addPathEntries(searchDirs, System.getenv("PATH"));
        addToolHome(searchDirs, "MAVEN_HOME");
        addToolHome(searchDirs, "M2_HOME");
        addToolHome(searchDirs, "GRADLE_HOME");

        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank()) {
            searchDirs.add(Path.of(userHome, ".sdkman", "candidates", "maven", "current", "bin"));
            searchDirs.add(Path.of(userHome, ".sdkman", "candidates", "gradle", "current", "bin"));
        }

        searchDirs.add(Path.of("/opt/homebrew/bin"));
        searchDirs.add(Path.of("/usr/local/bin"));
        searchDirs.add(Path.of("/opt/local/bin"));
        searchDirs.add(Path.of("/usr/bin"));
        searchDirs.add(Path.of("/bin"));

        for (Path dir : searchDirs) {
            Path candidate = dir.resolve(executable);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static void addToolHome(@NotNull Set<Path> searchDirs, @NotNull String envName) {
        String toolHome = System.getenv(envName);
        if (toolHome != null && !toolHome.isBlank()) {
            searchDirs.add(Path.of(toolHome, "bin"));
        }
    }

    private static void addPathEntries(@NotNull Set<Path> searchDirs, @Nullable String pathValue) {
        if (pathValue == null || pathValue.isBlank()) {
            return;
        }

        for (String entry : pathValue.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                searchDirs.add(Path.of(entry));
            }
        }
    }

    static @NotNull List<String> buildCommand(
        @NotNull GitHookInstaller.BuildTool buildTool,
        @NotNull Collection<String> pushedPaths
    ) {
        boolean fullCompile = pushedPaths.isEmpty();
        boolean testChanged = false;
        for (String path : pushedPaths) {
            String normalized = PushValidationPaths.normalizePath(path);
            if (PushValidationPaths.isBuildFile(normalized)) {
                fullCompile = true;
            }
            if (isTestPath(normalized)) {
                testChanged = true;
            }
        }

        return switch (buildTool) {
            case GRADLE_WRAPPER -> gradleCommand("./gradlew", fullCompile, testChanged);
            case GRADLE -> gradleCommand("gradle", fullCompile, testChanged);
            case MAVEN_WRAPPER -> mavenCommand("./mvnw", fullCompile || testChanged);
            case MAVEN -> mavenCommand("mvn", fullCompile || testChanged);
            case UNKNOWN -> List.of();
        };
    }

    private static @NotNull List<String> gradleCommand(
        @NotNull String executable,
        boolean fullCompile,
        boolean testChanged
    ) {
        List<String> command = new ArrayList<>(List.of(
            executable, "--console=plain", "--quiet", "--parallel", "--build-cache"));
        if (fullCompile) {
            command.add("classes");
            command.add("testClasses");
        } else {
            command.add(testChanged ? "testClasses" : "classes");
        }
        return command;
    }

    private static @NotNull List<String> mavenCommand(@NotNull String executable, boolean includeTests) {
        return List.of(
            executable,
            "-q",
            "-T1C",
            "-Dmaven.javadoc.skip=true",
            includeTests ? "test-compile" : "compile"
        );
    }

    private static boolean isTestPath(@NotNull String path) {
        return path.contains("/src/test/")
            || path.contains("/src/testFixtures/")
            || path.contains("/src/integrationTest/")
            || path.endsWith("Test.java")
            || path.endsWith("Test.kt")
            || path.endsWith("Tests.java")
            || path.endsWith("Tests.kt")
            || path.endsWith("IT.java")
            || path.endsWith("IT.kt");
    }

    private static @NotNull CommandResult runCommand(
        @NotNull Path directory,
        @NotNull List<String> command,
        long timeoutMillis,
        @Nullable ProgressIndicator indicator,
        @NotNull Path outputFile
    ) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(outputFile.toFile())
            .start();

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            indicatorCheckCanceled(indicator);
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                process.destroyForcibly();
                return new CommandResult(false, -1, true, false, readOutputLines(outputFile));
            }
            long waitMillis = Math.min(WAIT_SLICE_MILLIS, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                return new CommandResult(
                    process.exitValue() == 0,
                    process.exitValue(),
                    false,
                    false,
                    readOutputLines(outputFile)
                );
            }
        }
    }

    private static @NotNull List<String> readOutputLines(@NotNull Path outputFile) throws IOException {
        if (!Files.isRegularFile(outputFile)) {
            return List.of();
        }

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(outputFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static @NotNull List<String> commandFailure(
        @NotNull String summary,
        @NotNull CommandResult result,
        @NotNull Project project,
        @NotNull Path worktree
    ) {
        List<String> normalizedOutput = normalizeSnapshotOutput(project, worktree, result.outputLines());
        List<String> parsedErrors = ExternalPushErrorLoader.parseErrors(project, normalizedOutput);
        if (!parsedErrors.isEmpty()) {
            return parsedErrors;
        }

        List<String> messages = new ArrayList<>();
        String suffix = result.timedOut()
            ? " timed out."
            : " failed with exit code " + result.exitCode() + ".";
        messages.add("[snapshot] " + summary + suffix);

        int emitted = 0;
        for (String line : normalizedOutput) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            messages.add(CompilationErrorService.compactError("[snapshot] " + trimmed));
            emitted++;
            if (emitted >= MAX_SNAPSHOT_OUTPUT_LINES) {
                break;
            }
        }
        if (normalizedOutput.size() > emitted) {
            messages.add(CompilationErrorService.omittedErrorsMessage(normalizedOutput.size() - emitted));
        }
        return List.copyOf(messages);
    }

    private static @NotNull List<String> snapshotBuildFailureMessages(
        @NotNull String summary,
        @NotNull CommandResult result,
        @NotNull Project project,
        @NotNull Path worktree,
        @NotNull Collection<String> relevantPaths
    ) {
        List<String> normalizedOutput = normalizeSnapshotOutput(project, worktree, result.outputLines());
        List<String> parsedErrors = ExternalPushErrorLoader.parseErrors(project, normalizedOutput);
        if (!parsedErrors.isEmpty()) {
            return filterSnapshotErrors(parsedErrors, relevantPaths);
        }
        return commandFailure(summary, result, project, worktree);
    }

    static @NotNull List<String> filterSnapshotErrors(
        @NotNull List<String> parsedErrors,
        @NotNull Collection<String> relevantPaths
    ) {
        if (parsedErrors.isEmpty() || relevantPaths.isEmpty() || containsBuildFile(relevantPaths)) {
            return List.copyOf(parsedErrors);
        }

        Set<String> relevantSourcePaths = new LinkedHashSet<>();
        for (String path : relevantPaths) {
            if (PushValidationPaths.isCompilableSource(path)) {
                relevantSourcePaths.add(PushValidationPaths.normalizePath(path));
            }
        }
        if (relevantSourcePaths.isEmpty()) {
            return List.copyOf(parsedErrors);
        }

        List<String> filtered = new ArrayList<>();
        for (String error : parsedErrors) {
            String errorPath = CompilationEntryRenderer.extractPath(error);
            if (errorPath != null && isRelevantErrorPath(errorPath, relevantSourcePaths)) {
                filtered.add(error);
            }
        }
        return List.copyOf(filtered);
    }

    private static boolean isRelevantErrorPath(
        @NotNull String errorPath,
        @NotNull Set<String> relevantSourcePaths
    ) {
        String normalizedErrorPath = PushValidationPaths.normalizePath(errorPath);
        for (String relevantPath : relevantSourcePaths) {
            if (normalizedErrorPath.equals(relevantPath)
                || normalizedErrorPath.endsWith("/" + relevantPath)
                || relevantPath.endsWith("/" + normalizedErrorPath)) {
                return true;
            }
        }
        return false;
    }

    private static @NotNull List<String> normalizeSnapshotOutput(
        @NotNull Project project,
        @NotNull Path worktree,
        @NotNull List<String> lines
    ) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank() || lines.isEmpty()) {
            return lines;
        }

        String snapshotPath = PushValidationPaths.normalizePath(worktree.toString());
        String projectPath = PushValidationPaths.normalizePath(basePath);
        // macOS canonicalizes /var/folders/... → /private/var/folders/...; javac/maven may print either form.
        String privateSnapshotPath = snapshotPath.startsWith("/private") ? null : "/private" + snapshotPath;
        List<String> normalized = new ArrayList<>(lines.size());
        for (String line : lines) {
            String value = line == null ? "" : PushValidationPaths.normalizePath(line);
            if (privateSnapshotPath != null) {
                value = value.replace(privateSnapshotPath, projectPath);
            }
            normalized.add(value.replace(snapshotPath, projectPath));
        }
        return normalized;
    }

    private static void cleanupWorktree(
        @NotNull String basePath,
        @Nullable Path worktree,
        @Nullable Path tempRoot
    ) {
        if (worktree != null) {
            try {
                runCommand(
                    Path.of(basePath),
                    List.of("git", "worktree", "remove", "--force", worktree.toString()),
                    GIT_TIMEOUT_MILLIS,
                    null,
                    tempRoot != null
                        ? tempRoot.resolve("worktree-remove.log")
                        : Files.createTempFile("prepushchecker-worktree-remove-", ".log")
                );
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.warn("Failed to remove strict A/B snapshot worktree " + worktree, e);
            }
        }
        if (tempRoot != null) {
            deleteRecursively(tempRoot);
        }
    }

    private static void deleteRecursively(@NotNull Path path) {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static void indicatorCheckCanceled(@Nullable ProgressIndicator indicator) {
        if (indicator != null) {
            indicator.checkCanceled();
        }
    }

    private static void addRelevantChange(
        @NotNull Project project,
        @NotNull Change change,
        @NotNull Set<LocalRelevantChange> relevantChanges
    ) {
        String displayPath = toProjectRelativePath(project, extractPath(change));
        if (!displayPath.isEmpty() && PushValidationPaths.isRelevantPath(displayPath)) {
            relevantChanges.add(new LocalRelevantChange(displayPath, change.getType()));
        }
    }

    static @NotNull String toProjectRelativePath(@NotNull Project project, @NotNull String path) {
        String normalizedPath = PushValidationPaths.normalizePath(path);
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return normalizedPath;
        }

        String normalizedBase = PushValidationPaths.normalizePath(basePath);
        String relativePath = FileUtil.getRelativePath(normalizedBase, normalizedPath, '/');
        return relativePath != null && !relativePath.startsWith("../") ? relativePath : normalizedPath;
    }

    private static @NotNull String extractPath(@NotNull Change change) {
        ContentRevision afterRevision = change.getAfterRevision();
        if (afterRevision != null) {
            return afterRevision.getFile().getPath();
        }

        ContentRevision beforeRevision = change.getBeforeRevision();
        return beforeRevision != null ? beforeRevision.getFile().getPath() : "";
    }

    static final class SnapshotValidationResult {
        private final boolean checked;
        private final List<String> errors;

        private SnapshotValidationResult(boolean checked, @NotNull List<String> errors) {
            this.checked = checked;
            this.errors = List.copyOf(errors);
        }

        private static @NotNull SnapshotValidationResult notChecked() {
            return new SnapshotValidationResult(false, List.of());
        }

        private static @NotNull SnapshotValidationResult checked(@NotNull List<String> errors) {
            return new SnapshotValidationResult(true, errors);
        }

        boolean wasChecked() {
            return checked;
        }

        @NotNull List<String> errors() {
            return errors;
        }
    }

    static final class PushSnapshotRisk {
        private final boolean shouldValidateSnapshot;
        private final List<String> pushedPaths;
        private final List<LocalRelevantChange> localChanges;
        private final String reason;

        private PushSnapshotRisk(
            boolean shouldValidateSnapshot,
            @NotNull List<String> pushedPaths,
            @NotNull List<LocalRelevantChange> localChanges,
            @NotNull String reason
        ) {
            this.shouldValidateSnapshot = shouldValidateSnapshot;
            this.pushedPaths = List.copyOf(pushedPaths);
            this.localChanges = List.copyOf(localChanges);
            this.reason = reason;
        }

        private static @NotNull PushSnapshotRisk risky(
            @NotNull List<String> pushedPaths,
            @NotNull List<LocalRelevantChange> localChanges,
            @NotNull String reason
        ) {
            return new PushSnapshotRisk(true, pushedPaths, localChanges, reason);
        }

        private static @NotNull PushSnapshotRisk notRisky(
            @NotNull List<String> pushedPaths,
            @NotNull List<LocalRelevantChange> localChanges,
            @NotNull String reason
        ) {
            return new PushSnapshotRisk(false, pushedPaths, localChanges, reason);
        }

        boolean shouldValidateSnapshot() {
            return shouldValidateSnapshot;
        }

        @NotNull List<String> pushedPaths() {
            return pushedPaths;
        }

        @NotNull List<LocalRelevantChange> localChanges() {
            return localChanges;
        }

        @NotNull String reason() {
            return reason;
        }
    }

    private record LocalRelevantChange(@NotNull String path, @NotNull Change.Type type) {
        private boolean isBuildFile() {
            return PushValidationPaths.isBuildFile(path);
        }

        private boolean isCompilableSource() {
            return PushValidationPaths.isCompilableSource(path);
        }

        private boolean isDeleteOrMove() {
            return type == Change.Type.DELETED || type == Change.Type.MOVED;
        }
    }

    private record ModuleRisk(boolean risky, @NotNull String reason) {
        private static @NotNull ModuleRisk risky(@NotNull String reason) {
            return new ModuleRisk(true, reason);
        }

        private static @NotNull ModuleRisk notRisky(@NotNull String reason) {
            return new ModuleRisk(false, reason);
        }
    }

    private record CommandResult(
        boolean succeeded,
        int exitCode,
        boolean timedOut,
        boolean skipped,
        List<String> outputLines
    ) {
        private static @NotNull CommandResult skippedResult() {
            return new CommandResult(false, -1, false, true, List.of());
        }
    }
}
