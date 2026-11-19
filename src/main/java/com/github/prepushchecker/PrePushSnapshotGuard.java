package com.github.prepushchecker;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
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
import java.util.regex.Pattern;

final class PrePushSnapshotGuard {
    private static final Logger LOG = Logger.getInstance(PrePushSnapshotGuard.class);
    private static final int MAX_SNAPSHOT_OUTPUT_LINES = 25;
    private static final long WAIT_SLICE_MILLIS = 250L;
    private static final long GIT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60);
    private static final long SNAPSHOT_BUILD_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(3);

    private static final Pattern LOMBOK_GENERATED_SYMBOL = Pattern.compile(
        "symbol:\\s+(method\\s+(get|set|is)[A-Za-z0-9_]*|class\\s+[A-Za-z0-9_.$]*Builder)",
        Pattern.CASE_INSENSITIVE
    );

    private PrePushSnapshotGuard() {
    }

    static @NotNull SnapshotValidationResult validateHeadSnapshotIfNeeded(
        @NotNull Project project,
        @NotNull String repositoryRoot,
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
        // The symbolic scan is a heuristic. Its matches only decide the outcome when the
        // authoritative clean-snapshot build below cannot run.
        List<String> symbolicMatches = SymbolicAbCheck.detect(project, risk.pushedPaths(), unpushedPaths);
        if (!symbolicMatches.isEmpty()) {
            LOG.info("Strict A/B symbolic check found " + symbolicMatches.size()
                + " reference(s) to unpushed-only declarations; confirming with a snapshot build.");
        }
        SnapshotValidationResult unavailable = symbolicMatches.isEmpty()
            ? SnapshotValidationResult.notChecked()
            : SnapshotValidationResult.checked(symbolicMatches.stream().map(line -> "[snapshot] " + line).toList());

        String basePath = repositoryRoot;
        if (basePath.isBlank()) {
            return SnapshotValidationResult.checked(List.of(
                "[snapshot] Strict A/B dependency check could not resolve the project base path."));
        }

        Path tempRoot = null;
        Path worktree = null;
        try {
            Path projectRoot = GitHookInstaller.resolveWorkTreeRoot(basePath);
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
                // Never stash or otherwise touch the user's working tree during a push.
                LOG.info("Strict A/B snapshot build skipped because a clean worktree could not be created.");
                return unavailable;
            }

            var roots = new ExternalPushErrorLoader.SnapshotRoots(worktree, projectRoot);
            CommandResult build = runSnapshotBuild(project, roots, risk.pushedPaths(), indicator, tempRoot);
            if (build.skipped()) {
                LOG.info("Strict A/B dependency check skipped because no runnable snapshot build command was found.");
                return unavailable;
            }
            if (build.succeeded()) {
                LOG.info("Strict A/B dependency check passed against a clean HEAD snapshot.");
                return SnapshotValidationResult.checked(List.of());
            }

            return snapshotFailure(project, roots, risk.pushedPaths(), build.outputLines(),
                build.exitCode(), build.timedOut(), hasCustomCommand());
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
        return ReadActions.compute(() -> {
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
        @NotNull ExternalPushErrorLoader.SnapshotRoots roots,
        @NotNull Collection<String> pushedPaths,
        @Nullable ProgressIndicator indicator,
        @NotNull Path outputRoot
    ) throws IOException, InterruptedException {
        Path worktree = roots.worktree();
        String override = System.getenv("PRE_PUSH_CHECKER_COMMAND");
        BuildCommandPlan commandPlan;
        if (override != null && !override.isBlank()) {
            commandPlan = new BuildCommandPlan(
                List.of("/bin/sh", "-c", override),
                List.of());
        } else {
            GitHookInstaller.BuildTool buildTool = GitHookInstaller.detectBuildTool(worktree.toString());
            commandPlan = buildCommandPlan(buildTool, pushedPaths);
            if (commandPlan.primary().isEmpty()) {
                return CommandResult.skippedResult();
            }
        }

        List<String> primary = resolveBuildCommand(worktree, commandPlan.primary());
        if (primary.isEmpty()) {
            return CommandResult.skippedResult();
        }
        long deadlineNanos = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(SNAPSHOT_BUILD_TIMEOUT_MILLIS);
        CommandResult result = runCommand(
            worktree,
            primary,
            remainingTimeoutMillis(deadlineNanos),
            indicator,
            outputRoot.resolve("snapshot-build.log")
        );
        if (result.succeeded()
                || result.timedOut()
                || commandPlan.recovery().isEmpty()) {
            return result;
        }

        CompilationFailureClassifier.RecoveryDecision decision =
            CompilationFailureClassifier.classify(result.outputLines());
        if (!decision.shouldRecover()) {
            return result;
        }

        if (!IgnoredCompilationFiles.getInstance(project).enabledPaths().isEmpty()
                && result.exitCode() == 1) {
            var output = ExternalPushErrorLoader.parseBuildOutput(project, result.outputLines(), roots);
            if (output.unexplained() == null && !output.errors().isEmpty()
                    && DiagnosticPathMatcher.filterIgnored(project, output.errors()).isEmpty()) {
                return result;
            }
        }

        List<String> recovery = resolveBuildCommand(worktree, commandPlan.recovery());
        if (recovery.isEmpty()) {
            return result;
        }
        LOG.info("Snapshot Maven build reported likely stale/parallel compiler state ("
            + decision.reason() + "); retrying once sequentially from a clean output state.");
        return runCommand(
            worktree,
            recovery,
            remainingTimeoutMillis(deadlineNanos),
            indicator,
            outputRoot.resolve("snapshot-build-recovery.log")
        );
    }

    private static boolean hasCustomCommand() {
        String command = System.getenv("PRE_PUSH_CHECKER_COMMAND");
        return command != null && !command.isBlank();
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
        return buildCommandPlan(buildTool, pushedPaths).primary();
    }

    static @NotNull List<String> buildRecoveryCommand(
        @NotNull GitHookInstaller.BuildTool buildTool,
        @NotNull Collection<String> pushedPaths
    ) {
        return buildCommandPlan(buildTool, pushedPaths).recovery();
    }

    private static @NotNull BuildCommandPlan buildCommandPlan(
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
            case GRADLE_WRAPPER -> new BuildCommandPlan(
                gradleCommand("./gradlew", fullCompile, testChanged), List.of());
            case GRADLE -> new BuildCommandPlan(
                gradleCommand("gradle", fullCompile, testChanged), List.of());
            case MAVEN_WRAPPER -> mavenCommandPlan("./mvnw", fullCompile || testChanged);
            case MAVEN -> mavenCommandPlan("mvn", fullCompile || testChanged);
            case UNKNOWN -> new BuildCommandPlan(List.of(), List.of());
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

    private static @NotNull BuildCommandPlan mavenCommandPlan(
        @NotNull String executable,
        boolean includeTests
    ) {
        String goal = includeTests ? "test-compile" : "compile";
        return new BuildCommandPlan(
            List.of(
                executable,
                "-q",
                "-T1C",
                "-Dmaven.javadoc.skip=true",
                goal
            ),
            List.of(
                executable,
                "-q",
                "-Dmaven.javadoc.skip=true",
                "clean",
                goal
            )
        );
    }

    private static long remainingTimeoutMillis(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
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
        try {
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
        } catch (Throwable t) {
            // Includes ProcessCanceledException from the indicator and any other failure
            // while waiting for the subprocess. Make sure we do not leak the process.
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            throw t;
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

    static SnapshotValidationResult snapshotFailure(Project project, ExternalPushErrorLoader.SnapshotRoots roots,
            Collection<String> pushedPaths, List<String> lines, int exitCode,
            boolean timedOut, boolean customCommand) {
        var output = ExternalPushErrorLoader.parseBuildOutput(project, lines, roots);
        List<String> errors = output.errors();
        String reason = null;
        if (timedOut) reason = "Compilation timed out.";
        else if (customCommand || exitCode != 1 || errors.isEmpty()) {
            reason = "Build failed with exit code " + exitCode + ".";
        } else if (output.unexplained() != null) {
            reason = "Unattributed build output: " + output.unexplained();
        }
        if (reason != null) {
            List<String> blocking = new ArrayList<>();
            blocking.add(CompilationErrorService.compactError("[snapshot] " + reason));
            if (!errors.isEmpty()) blocking.addAll(errors);
            else lines.stream().filter(line -> !line.isBlank()).limit(MAX_SNAPSHOT_OUTPUT_LINES)
                .map(line -> CompilationErrorService.compactError("[snapshot] " + line)).forEach(blocking::add);
            return SnapshotValidationResult.checked(blocking);
        }
        // Keep the complete verdict when an exclusion participates. The caller applies current
        // settings after publishing raw diagnostics, including errors outside the pushed paths.
        if (output.hasIgnored()) {
            return SnapshotValidationResult.checked(errors);
        }
        List<String> filtered = filterSnapshotErrors(errors, pushedPaths);
        if (filtered.isEmpty() || (allLombokGeneratedSymbolErrors(filtered) && projectUsesLombok(roots.worktree()))) {
            return SnapshotValidationResult.notChecked();
        }
        return SnapshotValidationResult.checked(filtered);
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

    static boolean isLikelyLombokGeneratedSymbolError(@NotNull String parsedError) {
        return parsedError.contains("cannot find symbol")
            && LOMBOK_GENERATED_SYMBOL.matcher(parsedError).find();
    }

    static boolean allLombokGeneratedSymbolErrors(@NotNull List<String> errors) {
        if (errors.isEmpty()) return false;
        for (String error : errors) {
            if (!isLikelyLombokGeneratedSymbolError(error)) {
                return false;
            }
        }
        return true;
    }

    private static boolean projectUsesLombok(@NotNull Path projectRoot) {
        if (Files.exists(projectRoot.resolve("lombok.config"))) return true;
        if (fileContainsText(projectRoot.resolve("pom.xml"), "lombok")) return true;
        if (fileContainsText(projectRoot.resolve("build.gradle"), "lombok")) return true;
        if (fileContainsText(projectRoot.resolve("build.gradle.kts"), "lombok")) return true;
        // Multi-module Maven: check one level of submodule poms.
        File[] children = projectRoot.toFile().listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && fileContainsText(child.toPath().resolve("pom.xml"), "lombok")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean fileContainsText(@NotNull Path path, @NotNull String needle) {
        try {
            return Files.exists(path) && Files.readString(path).contains(needle);
        } catch (IOException e) {
            return false;
        }
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

    private record BuildCommandPlan(
        @NotNull List<String> primary,
        @NotNull List<String> recovery
    ) {
        private BuildCommandPlan {
            primary = List.copyOf(primary);
            recovery = List.copyOf(recovery);
        }
    }
}
