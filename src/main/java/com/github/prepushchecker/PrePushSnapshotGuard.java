package com.github.prepushchecker;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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

        List<String> localPaths = collectRelevantLocalPaths(project);
        if (localPaths.isEmpty()) {
            return SnapshotValidationResult.notChecked();
        }

        indicatorCheckCanceled(indicator);
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
                return SnapshotValidationResult.checked(commandFailure(
                    "Strict A/B dependency check could not create a clean pushed snapshot.",
                    checkout,
                    project,
                    worktree
                ));
            }

            CommandResult build = runSnapshotBuild(project, worktree, pushedPaths, indicator, tempRoot);
            if (build.succeeded()) {
                LOG.info("Strict A/B dependency check passed against a clean HEAD snapshot.");
                return SnapshotValidationResult.checked(List.of());
            }

            return SnapshotValidationResult.checked(commandFailure(
                "Strict A/B dependency check failed when compiling HEAD without local changes.",
                build,
                project,
                worktree
            ));
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

    private static @NotNull List<String> collectRelevantLocalPaths(@NotNull Project project) {
        Set<String> relevantLocalPaths = new LinkedHashSet<>();
        ChangeListManager changeListManager = ChangeListManager.getInstance(project);
        for (Change change : changeListManager.getAllChanges()) {
            addRelevantPath(project, extractPath(change), relevantLocalPaths);
        }

        return List.copyOf(relevantLocalPaths);
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
            command = List.of("sh", "-c", override);
        } else {
            GitHookInstaller.BuildTool buildTool = GitHookInstaller.detectBuildTool(worktree.toString());
            command = buildCommand(buildTool, pushedPaths);
            if (command.isEmpty()) {
                return CommandResult.failed(List.of(
                    "No supported Gradle or Maven build file was found in the clean pushed snapshot. " +
                        "Set PRE_PUSH_CHECKER_COMMAND or disable strict A/B dependency guard."));
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
                return new CommandResult(false, -1, true, readOutputLines(outputFile));
            }
            long waitMillis = Math.min(WAIT_SLICE_MILLIS, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                return new CommandResult(
                    process.exitValue() == 0,
                    process.exitValue(),
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
        List<String> normalized = new ArrayList<>(lines.size());
        for (String line : lines) {
            String value = line == null ? "" : PushValidationPaths.normalizePath(line);
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

    private static void addRelevantPath(
        @NotNull Project project,
        @NotNull String path,
        @NotNull Set<String> relevantPaths
    ) {
        String displayPath = toProjectRelativePath(project, path);
        if (!displayPath.isEmpty() && PushValidationPaths.isRelevantPath(displayPath)) {
            relevantPaths.add(displayPath);
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

    private record CommandResult(boolean succeeded, int exitCode, boolean timedOut, List<String> outputLines) {
        private static @NotNull CommandResult failed(@NotNull List<String> outputLines) {
            return new CommandResult(false, -1, false, outputLines);
        }
    }
}
