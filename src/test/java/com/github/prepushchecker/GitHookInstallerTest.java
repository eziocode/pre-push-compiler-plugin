package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class GitHookInstallerTest extends BasePlatformTestCase {
    public void testDetectBuildToolPrefersGradleWrapper() throws IOException {
        File tempDir = createTempDir("prepushchecker-gradle");
        assertTrue(new File(tempDir, "gradlew").createNewFile());
        assertTrue(new File(tempDir, "pom.xml").createNewFile());

        assertEquals(GitHookInstaller.BuildTool.GRADLE_WRAPPER, GitHookInstaller.detectBuildTool(tempDir.getAbsolutePath()));
    }

    public void testManagedHookUsesFastSkipAndOverrideCommand() {
        String script = GitHookInstaller.buildManagedHookScript();

        assertTrue(script.contains("PRE_PUSH_CHECKER_COMMAND"));
        assertTrue(script.contains("No source or build changes detected. Skipping compilation check."));
        assertTrue(script.contains("run_build_tool_command"));
        assertTrue(script.contains("./gradlew --console=plain --quiet --parallel --build-cache $_gradle_tasks"));
        assertTrue(script.contains("mvn -q -T1C -Dmaven.javadoc.skip=true \"$_maven_goal\""));
        assertTrue(script.contains("mvn -q -Dmaven.javadoc.skip=true clean \"$_maven_goal\""));
        assertFalse(script.contains("maven.compiler.useIncrementalCompilation=false"));
        assertTrue(script.contains("setup_maven_java_home"));
        assertTrue(script.contains("preferredJavaHome="));
        assertTrue(script.contains("try_ide_compile_with_retry"));
        assertTrue(script.contains("IntelliJ appears to be running; waiting briefly for IDE compiler service"));
        assertTrue(script.contains("IntelliJ incremental compile unavailable"));
        assertFalse(script.contains("retrying IntelliJ incremental compile once before aborting"));
        assertTrue(script.contains("FALLBACK_LOCK_DIR"));
        assertTrue(script.contains("FALLBACK_WAIT_SECONDS=300"));
        assertTrue(script.contains("FALLBACK_SUCCESS_TTL_SECONDS=60"));
        assertTrue(script.contains("publish_success_result"));
        assertTrue(script.contains("publish_run_result"));
        assertTrue(script.contains("Reusing recent successful fallback result"));
        assertTrue(script.contains("Reusing result from the same in-flight fallback validation"));
        assertTrue(script.contains("IntelliJ validation timed out"));
        assertTrue(script.contains("extract_error_records"));
        assertTrue(script.contains("looks_like_stale_or_parallel_failure"));
        assertTrue(script.contains("bad class file:"));
        assertTrue(script.contains("NoSuchFileException:"));
        assertTrue(script.contains("package[[:space:]]+[^[:space:]]+[[:space:]]+does not exist"));
        assertTrue(script.contains("cannot access[[:space:]]"));
        assertTrue(script.contains("mvnd -q -T1C -Dmaven.javadoc.skip=true"));
        assertTrue(script.contains("mvnd -q -T1 -Dmaven.javadoc.skip=true clean"));
        assertFalse(script.contains("if [ $rc -ne 0 ] && suppression_hard_gate_allows"));
        assertTrue(script.contains("git -C \"$REPO_ROOT\" worktree add --detach"));
        assertTrue(script.contains("git -C \"$REPO_ROOT\" worktree remove --force"));
        assertTrue(script.contains("Running build-tool fallback in isolated HEAD worktree."));
        assertFalse(script.contains("git stash push"));
        assertFalse(script.contains("STASH_RESTORE_FAILED"));
        assertFalse(script.contains("git checkout -- ."));
        assertFalse(script.contains("git clean -fd"));
    }

    public void testManagedHookContainsSelfCleanup() {
        String script = GitHookInstaller.buildManagedHookScript();

        assertTrue("Hook should check global marker",
            script.contains(".prepush-checker/installed"));
        assertTrue("Hook should self-remove managed hook on uninstall",
            script.contains("Plugin uninstalled. Cleaned up hooks and cache."));
        assertTrue("Hook should resolve hooks dir via git",
            script.contains("git rev-parse --git-path hooks"));
    }

    public void testManagedHookScriptHasValidShellSyntax() throws Exception {
        Path script = Files.createTempFile("prepushchecker-managed-hook-", ".sh");
        script.toFile().deleteOnExit();
        Files.writeString(script, GitHookInstaller.buildManagedHookScript(), StandardCharsets.UTF_8);

        Process process = new ProcessBuilder("sh", "-n", script.toString())
            .redirectErrorStream(true)
            .start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, process.exitValue());
    }

    public void testManagedHookSelfCleanupPreservesForeignSharedHookLogic() throws Exception {
        File repo = createTempDir("prepushchecker-self-cleanup-shared");
        runGit(repo, "init");
        Path repoPath = repo.toPath();
        Path hooksDir = repoPath.resolve(".git").resolve("hooks");
        Files.createDirectories(hooksDir);

        Path mainHook = hooksDir.resolve("pre-push");
        Files.writeString(mainHook,
            "#!/usr/bin/env sh\n"
                + "echo foreign-before\n"
                + GitHookInstaller.HOOK_BLOCK_BEGIN + "\n"
                + "echo modified-owned-content\n"
                + GitHookInstaller.HOOK_BLOCK_END + "\n"
                + "echo foreign-after\n",
            StandardCharsets.UTF_8);

        Path managedHook = hooksDir.resolve(GitHookInstaller.MANAGED_HOOK_NAME);
        Files.writeString(managedHook, GitHookInstaller.buildManagedHookScript(), StandardCharsets.UTF_8);
        assertTrue(managedHook.toFile().setExecutable(true, false));

        Path fakeHome = repoPath.resolve("fake-home");
        Path fakeBin = repoPath.resolve("fake-bin");
        Files.createDirectories(fakeHome);
        Files.createDirectories(fakeBin);
        Path fakePgrep = fakeBin.resolve("pgrep");
        Files.writeString(fakePgrep, "#!/usr/bin/env sh\nexit 1\n", StandardCharsets.UTF_8);
        assertTrue(fakePgrep.toFile().setExecutable(true, false));

        ProcessBuilder processBuilder = new ProcessBuilder(managedHook.toString())
            .directory(repo)
            .redirectErrorStream(true);
        processBuilder.environment().put("HOME", fakeHome.toString());
        processBuilder.environment().put("PATH",
            fakeBin + File.pathSeparator + System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        Process process = processBuilder.start();
        process.getOutputStream().close();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, process.exitValue());

        assertFalse(Files.exists(managedHook));
        assertTrue(Files.exists(mainHook));
        String cleaned = Files.readString(mainHook, StandardCharsets.UTF_8);
        assertTrue(cleaned.contains("echo foreign-before"));
        assertTrue(cleaned.contains("echo foreign-after"));
        assertFalse(cleaned.contains("modified-owned-content"));
        assertFalse(cleaned.contains(GitHookInstaller.HOOK_MARKER));
    }

    public void testManagedHookContainsBypassTokenCheck() {
        String script = GitHookInstaller.buildManagedHookScript();

        assertTrue("Hook should check for bypass-token file",
            script.contains("bypass-token"));
        assertTrue("Hook should skip compilation when bypass is active",
            script.contains("Force-push bypass active. Skipping compilation check."));
    }

    public void testDelegatingSnippetCallsManagedHook() {
        String snippet = GitHookInstaller.buildDelegatingSnippet();

        assertTrue(snippet.contains(GitHookInstaller.MANAGED_HOOK_NAME));
        assertTrue(snippet.contains(GitHookInstaller.HOOK_BLOCK_BEGIN));
        assertTrue(snippet.contains(GitHookInstaller.HOOK_BLOCK_END));
        assertEquals(1, GitHookInstaller.countMarkerOccurrences(snippet));
    }

    public void testStripManagedHookSectionsPreservesForeignContentAroundModifiedOwnedBlock() {
        String content = "#!/usr/bin/env sh\n"
            + "echo before\n"
            + GitHookInstaller.HOOK_BLOCK_BEGIN + "\n"
            + "echo modified-by-another-tool-inside-owned-block\n"
            + "\"$SCRIPT_DIR/" + GitHookInstaller.MANAGED_HOOK_NAME + "\" \"$@\"\n"
            + GitHookInstaller.HOOK_BLOCK_END + "\n"
            + "echo after\n";

        String stripped = GitHookInstaller.stripManagedHookSections(content);

        assertTrue(stripped.contains("echo before"));
        assertTrue(stripped.contains("echo after"));
        assertFalse(stripped.contains("modified-by-another-tool-inside-owned-block"));
        assertFalse(stripped.contains(GitHookInstaller.HOOK_MARKER));
        assertFalse(stripped.contains(GitHookInstaller.MANAGED_HOOK_NAME));
    }

    public void testStripManagedHookSectionsPreservesUnterminatedBlockConservatively() {
        String content = "#!/usr/bin/env sh\n"
            + "echo before\n"
            + GitHookInstaller.HOOK_BLOCK_BEGIN + "\n"
            + "echo possibly-foreign\n";

        assertEquals(content, GitHookInstaller.stripManagedHookSections(content));
    }

    public void testRepairRestoresOverwrittenHookWhileKeepingUserLogic() throws Exception {
        File repo = createTempDir("prepushchecker-repair-overwritten");
        Path repoPath = repo.toPath();
        Path hooksDir = repoPath.resolve(".git").resolve("hooks");
        Files.createDirectories(hooksDir);

        Path mainHook = hooksDir.resolve("pre-push");
        String userHook = "#!/usr/bin/env sh\n" +
            "echo \"user-hook\"\n";
        Files.writeString(mainHook, userHook, StandardCharsets.UTF_8);

        GitHookInstaller.HookRepairResult result = GitHookInstaller.repair(repo.getAbsolutePath());
        try {
            assertTrue(result.statusText(), result.isSuccess());

            assertTrue(Files.exists(hooksDir.resolve(GitHookInstaller.MANAGED_HOOK_NAME)));
            String repaired = Files.readString(mainHook, StandardCharsets.UTF_8);
            assertTrue(repaired.contains("echo \"user-hook\""));
            assertTrue(repaired.contains(GitHookInstaller.MANAGED_HOOK_NAME));
            assertEquals(1, GitHookInstaller.countMarkerOccurrences(repaired));
        } finally {
            GitHookInstaller.uninstall(repo.getAbsolutePath());
        }
    }

    public void testRepairNormalizesDuplicateSnippetsAndPreservesUserHook() throws Exception {
        File repo = createTempDir("prepushchecker-repair-duplicates");
        Path repoPath = repo.toPath();
        Path hooksDir = repoPath.resolve(".git").resolve("hooks");
        Files.createDirectories(hooksDir);

        Path mainHook = hooksDir.resolve("pre-push");
        String originalUserLogic = "#!/usr/bin/env sh\necho \"user-hook\"\n";
        String duplicated = originalUserLogic + GitHookInstaller.buildDelegatingSnippet()
            + GitHookInstaller.buildDelegatingSnippet() + "echo \"tail\"\n";
        Files.writeString(mainHook, duplicated, StandardCharsets.UTF_8);
        Files.writeString(hooksDir.resolve(GitHookInstaller.MANAGED_HOOK_NAME), "edited\n",
            StandardCharsets.UTF_8);

        GitHookInstaller.HookRepairResult result = GitHookInstaller.repair(repo.getAbsolutePath());
        try {
            assertTrue(result.statusText(), result.isSuccess());

            String repaired = Files.readString(mainHook, StandardCharsets.UTF_8);
            assertTrue(repaired.contains("echo \"user-hook\""));
            assertTrue(repaired.contains("echo \"tail\""));
            assertEquals(1, GitHookInstaller.countMarkerOccurrences(repaired));
            assertEquals(GitHookInstaller.buildManagedHookScript(),
                Files.readString(hooksDir.resolve(GitHookInstaller.MANAGED_HOOK_NAME), StandardCharsets.UTF_8));
        } finally {
            GitHookInstaller.uninstall(repo.getAbsolutePath());
        }
    }

    public void testRepairMigratesLegacySnippetAndPreservesUserHook() throws Exception {
        File repo = createTempDir("prepushchecker-repair-legacy");
        Path repoPath = repo.toPath();
        Path hooksDir = repoPath.resolve(".git").resolve("hooks");
        Files.createDirectories(hooksDir);

        Path mainHook = hooksDir.resolve("pre-push");
        String legacySnippet = "\n# " + GitHookInstaller.HOOK_MARKER + "\n"
            + "SCRIPT_DIR=\"$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\"\n"
            + "\"$SCRIPT_DIR/" + GitHookInstaller.MANAGED_HOOK_NAME + "\" \"$@\" || exit $?\n";
        Files.writeString(mainHook,
            "#!/usr/bin/env sh\necho legacy-user-hook\n" + legacySnippet + "echo tail\n",
            StandardCharsets.UTF_8);

        GitHookInstaller.HookRepairResult result = GitHookInstaller.repair(repo.getAbsolutePath());
        try {
            assertTrue(result.statusText(), result.isSuccess());
            String repaired = Files.readString(mainHook, StandardCharsets.UTF_8);
            assertTrue(repaired.contains("echo legacy-user-hook"));
            assertTrue(repaired.contains("echo tail"));
            assertTrue(repaired.contains(GitHookInstaller.HOOK_BLOCK_BEGIN));
            assertTrue(repaired.contains(GitHookInstaller.HOOK_BLOCK_END));
            assertEquals(1, GitHookInstaller.countMarkerOccurrences(repaired));
            assertFalse(repaired.contains("\n# " + GitHookInstaller.HOOK_MARKER + "\n"));
        } finally {
            GitHookInstaller.uninstall(repo.getAbsolutePath());
        }
    }

    public void testRepairHonorsCoreHooksPathAndRemovesLegacyManagedHooks() throws Exception {
        File repo = createTempDir("prepushchecker-core-hooks-path");
        runGit(repo, "init");
        runGit(repo, "config", "core.hooksPath", ".githooks");

        Path repoPath = repo.toPath();
        Path configuredHooksDir = repoPath.resolve(".githooks");
        Path legacyHooksDir = repoPath.resolve(".git").resolve("hooks");
        Files.createDirectories(configuredHooksDir);
        Files.createDirectories(legacyHooksDir);
        Files.writeString(legacyHooksDir.resolve("pre-push"), GitHookInstaller.buildWrapperHookScript(),
            StandardCharsets.UTF_8);
        Files.writeString(legacyHooksDir.resolve(GitHookInstaller.MANAGED_HOOK_NAME), "old\n",
            StandardCharsets.UTF_8);

        GitHookInstaller.HookRepairResult result = GitHookInstaller.repair(repo.getAbsolutePath());
        try {
            assertTrue(result.statusText(), result.isSuccess());
            assertEquals(configuredHooksDir.toRealPath(),
                GitHookInstaller.resolveHooksDirectory(repo.getAbsolutePath()).toRealPath());
            assertTrue(Files.exists(configuredHooksDir.resolve("pre-push")));
            assertTrue(Files.exists(configuredHooksDir.resolve(GitHookInstaller.MANAGED_HOOK_NAME)));
            assertFalse(Files.exists(legacyHooksDir.resolve("pre-push")));
            assertFalse(Files.exists(legacyHooksDir.resolve(GitHookInstaller.MANAGED_HOOK_NAME)));
        } finally {
            GitHookInstaller.uninstall(repo.getAbsolutePath());
        }
    }

    public void testUninstallRemovesWrapperHookCacheAndExcludeBlock() throws Exception {
        File repo = createTempDir("prepushchecker-uninstall-wrapper");
        Path repoPath = repo.toPath();
        Path hooksDir = repoPath.resolve(".git").resolve("hooks");
        Path infoDir = repoPath.resolve(".git").resolve("info");
        Files.createDirectories(hooksDir);
        Files.createDirectories(infoDir);

        Path managedHook = hooksDir.resolve(GitHookInstaller.MANAGED_HOOK_NAME);
        Path mainHook = hooksDir.resolve("pre-push");
        Files.writeString(managedHook, "#!/usr/bin/env sh\n", StandardCharsets.UTF_8);
        Files.writeString(mainHook, GitHookInstaller.buildWrapperHookScript(), StandardCharsets.UTF_8);

        Path cacheFile = repoPath.resolve(".idea").resolve("pre-push-checker").resolve("last-run.log");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "error", StandardCharsets.UTF_8);

        Path excludeFile = infoDir.resolve("exclude");
        Files.writeString(excludeFile,
            "*.iml\n# BEGIN " + GitHookInstaller.HOOK_MARKER + "\n/.idea/pre-push-checker/\n# END "
                + GitHookInstaller.HOOK_MARKER + "\n.DS_Store\n",
            StandardCharsets.UTF_8);

        GitHookInstaller.uninstall(repo.getAbsolutePath());

        assertFalse(Files.exists(managedHook));
        assertFalse(Files.exists(mainHook));
        assertFalse(Files.exists(repoPath.resolve(".idea").resolve("pre-push-checker")));

        String exclude = Files.readString(excludeFile, StandardCharsets.UTF_8);
        assertFalse(exclude.contains("# BEGIN " + GitHookInstaller.HOOK_MARKER));
        assertFalse(exclude.contains("# END " + GitHookInstaller.HOOK_MARKER));
        assertTrue(exclude.contains("*.iml"));
        assertTrue(exclude.contains(".DS_Store"));
    }

    public void testUninstallStripsDelegatingSnippetButKeepsUserHookLogic() throws Exception {
        File repo = createTempDir("prepushchecker-uninstall-chained");
        Path repoPath = repo.toPath();
        Path hooksDir = repoPath.resolve(".git").resolve("hooks");
        Files.createDirectories(hooksDir);

        Path mainHook = hooksDir.resolve("pre-push");
        String originalUserLogic = "#!/usr/bin/env sh\necho \"user-hook\"\n";
        String chained = originalUserLogic + GitHookInstaller.buildDelegatingSnippet() + "echo \"tail\"\n";
        Files.writeString(mainHook, chained, StandardCharsets.UTF_8);
        Files.writeString(hooksDir.resolve(GitHookInstaller.MANAGED_HOOK_NAME), "#!/usr/bin/env sh\n",
            StandardCharsets.UTF_8);

        GitHookInstaller.uninstall(repo.getAbsolutePath());

        assertFalse(Files.exists(hooksDir.resolve(GitHookInstaller.MANAGED_HOOK_NAME)));
        assertTrue(Files.exists(mainHook));
        String updated = Files.readString(mainHook, StandardCharsets.UTF_8);
        assertTrue(updated.contains("echo \"user-hook\""));
        assertTrue(updated.contains("echo \"tail\""));
        assertFalse(updated.contains(GitHookInstaller.HOOK_MARKER));
        assertFalse(updated.contains(GitHookInstaller.MANAGED_HOOK_NAME));
    }

    public void testUninstallRemovesModifiedOwnedBlockAndKeepsForeignHookLogic() throws Exception {
        File repo = createTempDir("prepushchecker-uninstall-modified-block");
        Path repoPath = repo.toPath();
        Path hooksDir = repoPath.resolve(".git").resolve("hooks");
        Files.createDirectories(hooksDir);

        Path mainHook = hooksDir.resolve("pre-push");
        String sharedHook = "#!/usr/bin/env sh\n"
            + "echo foreign-before\n"
            + GitHookInstaller.HOOK_BLOCK_BEGIN + "\n"
            + "echo edited-owned-content\n"
            + GitHookInstaller.HOOK_BLOCK_END + "\n"
            + "echo foreign-after\n";
        Files.writeString(mainHook, sharedHook, StandardCharsets.UTF_8);
        Files.writeString(hooksDir.resolve(GitHookInstaller.MANAGED_HOOK_NAME), "edited\n",
            StandardCharsets.UTF_8);

        GitHookInstaller.uninstall(repo.getAbsolutePath());

        assertTrue(Files.exists(mainHook));
        String updated = Files.readString(mainHook, StandardCharsets.UTF_8);
        assertTrue(updated.contains("echo foreign-before"));
        assertTrue(updated.contains("echo foreign-after"));
        assertFalse(updated.contains("edited-owned-content"));
        assertFalse(updated.contains(GitHookInstaller.HOOK_MARKER));
        assertFalse(Files.exists(hooksDir.resolve(GitHookInstaller.MANAGED_HOOK_NAME)));
    }

    public void testInstallOpenProjectsRepairsAlreadyOpenProject() throws Exception {
        File projectDir = new File(getProject().getBasePath());
        Path hooksDir = projectDir.toPath().resolve(".git").resolve("hooks");
        Files.createDirectories(hooksDir);

        try {
            new PluginLifecycleListener().installHooksForOpenProjects();

            assertTrue(Files.exists(hooksDir.resolve("pre-push")));
            assertTrue(Files.exists(hooksDir.resolve(GitHookInstaller.MANAGED_HOOK_NAME)));
            String mainHook = Files.readString(hooksDir.resolve("pre-push"), StandardCharsets.UTF_8);
            assertTrue(mainHook.contains(GitHookInstaller.HOOK_BLOCK_BEGIN));
            assertTrue(mainHook.contains(GitHookInstaller.HOOK_BLOCK_END));
        } finally {
            GitHookInstaller.uninstall(projectDir.getAbsolutePath());
        }
    }

    public void testManagedHookBlocksMixedGeneratedAndRealErrors() throws Exception {
        File repo = createTempDir("prepushchecker-mixed-errors");
        runGit(repo, "init");
        runGit(repo, "config", "user.email", "test@example.com");
        runGit(repo, "config", "user.name", "Test User");

        Path repoPath = repo.toPath();
        Path readme = repoPath.resolve("README.md");
        Files.writeString(readme, "seed\n", StandardCharsets.UTF_8);
        runGit(repo, "add", "README.md");
        runGit(repo, "commit", "-m", "seed");

        Path source = repoPath.resolve("src").resolve("main").resolve("java").resolve("Touched.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Touched {}\n", StandardCharsets.UTF_8);
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "init");
        String headSha = runGitOutput(repo, "rev-parse", "HEAD").trim();

        Path hook = repoPath.resolve(".git").resolve("hooks").resolve(GitHookInstaller.MANAGED_HOOK_NAME);
        Files.writeString(hook, GitHookInstaller.buildManagedHookScript(), StandardCharsets.UTF_8);
        assertTrue(hook.toFile().setExecutable(true, false));

        Path fakeBuild = Files.createTempFile("prepushchecker-fake-build-", ".sh");
        fakeBuild.toFile().deleteOnExit();
        Files.writeString(fakeBuild,
            "#!/usr/bin/env sh\n"
                + "echo \"[ERROR] " + repoPath + "/src/main/java/Unrelated.java:[10,2] cannot find symbol\"\n"
                + "echo \"[ERROR]   symbol:   class ApprovalProcessRuleBuilder\"\n"
                + "echo \"[ERROR] " + repoPath + "/src/main/java/Unrelated.java:[12,2] package com.example.missing does not exist\"\n"
                + "exit 1\n",
            StandardCharsets.UTF_8);
        assertTrue(fakeBuild.toFile().setExecutable(true, false));

        ProcessBuilder pb = new ProcessBuilder(hook.toString())
            .directory(repo)
            .redirectErrorStream(true);
        Path fakeHome = repoPath.resolve("fake-home");
        Files.createDirectories(fakeHome.resolve(".prepush-checker"));
        Files.writeString(fakeHome.resolve(".prepush-checker").resolve("installed"),
            "1\n", StandardCharsets.UTF_8);
        pb.environment().put("HOME", fakeHome.toString());
        pb.environment().put("PRE_PUSH_CHECKER_COMMAND", fakeBuild.toAbsolutePath().toString());
        Process run = pb.start();
        String stdin = "refs/heads/main " + headSha + " refs/heads/main "
            + "0000000000000000000000000000000000000000\n";
        run.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        run.getOutputStream().close();
        assertTrue(run.waitFor(20, TimeUnit.SECONDS));

        String output = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue("Hook should block mixed generated + real error logs. Exit=" + run.exitValue()
                + "\nOutput:\n" + output,
            run.exitValue() != 0);
        assertTrue(output.contains("Compilation failed"));
    }

    public void testConcurrentFallbackHooksShareOneBuildResult() throws Exception {
        File repo = createTempDir("prepushchecker-concurrent-fallback");
        runGit(repo, "init");
        runGit(repo, "config", "user.email", "test@example.com");
        runGit(repo, "config", "user.name", "Test User");
        Path repoPath = repo.toPath();
        Path source = repoPath.resolve("src/main/java/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}\n", StandardCharsets.UTF_8);
        Files.writeString(repoPath.resolve(".gitignore"), ".idea/\nbuild-count\n",
            StandardCharsets.UTF_8);
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "init");
        Files.writeString(source, "class App { int value; }\n", StandardCharsets.UTF_8);
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "change");

        Path hook = repoPath.resolve(".git/hooks").resolve(GitHookInstaller.MANAGED_HOOK_NAME);
        Files.writeString(hook, GitHookInstaller.buildManagedHookScript(), StandardCharsets.UTF_8);
        assertTrue(hook.toFile().setExecutable(true, false));

        Path fakeHome = repoPath.resolve("fake-home");
        Path fakeBin = repoPath.resolve("fake-bin");
        Files.createDirectories(fakeHome.resolve(".prepush-checker"));
        Files.createDirectories(fakeBin);
        Files.writeString(fakeHome.resolve(".prepush-checker/installed"), "1\n", StandardCharsets.UTF_8);
        Path fakePgrep = fakeBin.resolve("pgrep");
        Files.writeString(fakePgrep, "#!/usr/bin/env sh\nexit 1\n", StandardCharsets.UTF_8);
        assertTrue(fakePgrep.toFile().setExecutable(true, false));

        Path count = repoPath.resolve("build-count");
        Path fakeBuild = repoPath.resolve("fake-build.sh");
        Files.writeString(fakeBuild,
            "#!/usr/bin/env sh\nprintf 'build\\n' >> \"" + count + "\"\nsleep 2\nexit 0\n",
            StandardCharsets.UTF_8);
        assertTrue(fakeBuild.toFile().setExecutable(true, false));
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "add test harness");
        String headSha = runGitOutput(repo, "rev-parse", "HEAD").trim();

        String input = "refs/heads/main " + headSha + " refs/heads/main "
            + "0000000000000000000000000000000000000000\n";
        ProcessBuilder firstBuilder = new ProcessBuilder(hook.toString())
            .directory(repo).redirectErrorStream(true);
        ProcessBuilder secondBuilder = new ProcessBuilder(hook.toString())
            .directory(repo).redirectErrorStream(true);
        for (ProcessBuilder builder : List.of(firstBuilder, secondBuilder)) {
            builder.environment().put("HOME", fakeHome.toString());
            builder.environment().put("PRE_PUSH_CHECKER_COMMAND", fakeBuild.toString());
            builder.environment().put("PATH",
                fakeBin + File.pathSeparator + System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        }

        Process first = firstBuilder.start();
        Process second = secondBuilder.start();
        first.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        first.getOutputStream().close();
        second.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        second.getOutputStream().close();

        assertTrue(first.waitFor(20, TimeUnit.SECONDS));
        assertTrue(second.waitFor(20, TimeUnit.SECONDS));
        String firstOutput = new String(first.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String secondOutput = new String(second.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(firstOutput, 0, first.exitValue());
        assertEquals(secondOutput, 0, second.exitValue());
        assertTrue("Fallback command did not run.\nFirst:\n" + firstOutput
            + "\nSecond:\n" + secondOutput, Files.exists(count));
        assertEquals("First:\n" + firstOutput + "\nSecond:\n" + secondOutput,
            List.of("build"), Files.readAllLines(count, StandardCharsets.UTF_8));
    }

    public void testFailedFallbackResultIsNotReusedByLaterInvocation() throws Exception {
        File repo = createFallbackRepository("prepushchecker-failure-retry", false);
        Path repoPath = repo.toPath();
        Path count = repoPath.resolve("build-count");
        Path fakeBuild = repoPath.resolve("fake-build.sh");
        Files.writeString(fakeBuild,
            "#!/usr/bin/env sh\nprintf 'build\\n' >> \"" + count + "\"\n"
                + "echo '[ERROR] real compilation failure'\nexit 1\n",
            StandardCharsets.UTF_8);
        assertTrue(fakeBuild.toFile().setExecutable(true, false));
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "add failure harness");

        HookFixture fixture = installHookFixture(repo);
        Path legacyResult = repoPath.resolve(
            ".idea/pre-push-checker/last-fallback-result");
        Files.createDirectories(legacyResult.getParent());
        Files.writeString(legacyResult, fixture.headSha() + "|main|1\n",
            StandardCharsets.UTF_8);
        HookRun first = runHook(fixture, Map.of("PRE_PUSH_CHECKER_COMMAND", fakeBuild.toString()));
        HookRun second = runHook(fixture, Map.of("PRE_PUSH_CHECKER_COMMAND", fakeBuild.toString()));

        assertTrue(first.output(), first.exitCode() != 0);
        assertTrue(second.output(), second.exitCode() != 0);
        assertFalse(Files.exists(legacyResult));
        assertEquals(List.of("build", "build"),
            Files.readAllLines(count, StandardCharsets.UTF_8));
    }

    public void testSuccessfulFallbackCacheExpiresByTimestamp() throws Exception {
        File repo = createFallbackRepository("prepushchecker-success-cache", false);
        Path repoPath = repo.toPath();
        Path count = repoPath.resolve("build-count");
        Path fakeBuild = repoPath.resolve("fake-build.sh");
        Files.writeString(fakeBuild,
            "#!/usr/bin/env sh\nprintf 'build\\n' >> \"" + count + "\"\nexit 0\n",
            StandardCharsets.UTF_8);
        assertTrue(fakeBuild.toFile().setExecutable(true, false));
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "add success harness");

        HookFixture fixture = installHookFixture(repo);
        Map<String, String> environment = Map.of(
            "PRE_PUSH_CHECKER_COMMAND", fakeBuild.toString());
        HookRun first = runHook(fixture, environment);
        HookRun cached = runHook(fixture, environment);

        assertEquals(first.output(), 0, first.exitCode());
        assertEquals(cached.output(), 0, cached.exitCode());
        assertEquals(List.of("build"), Files.readAllLines(count, StandardCharsets.UTF_8));

        Path successFile = repoPath.resolve(
            ".idea/pre-push-checker/last-fallback-success-v2");
        List<String> fields = List.of(Files.readString(successFile, StandardCharsets.UTF_8)
            .trim().split("\\|", -1));
        assertEquals(4, fields.size());
        Files.writeString(successFile,
            fields.get(0) + "|" + fields.get(1) + "|0|0\n", StandardCharsets.UTF_8);

        HookRun expired = runHook(fixture, environment);
        assertEquals(expired.output(), 0, expired.exitCode());
        assertEquals(List.of("build", "build"),
            Files.readAllLines(count, StandardCharsets.UTF_8));
    }

    public void testFallbackBuildRunsInDetachedWorktreeWithoutOldOutputs() throws Exception {
        File repo = createFallbackRepository("prepushchecker-isolated-worktree", false);
        Path repoPath = repo.toPath();
        Path staleOutput = repoPath.resolve("target/classes/Stale.class");
        Files.createDirectories(staleOutput.getParent());
        Files.writeString(staleOutput, "stale", StandardCharsets.UTF_8);
        Path observed = repoPath.resolve("observed-worktree");
        Path fakeBuild = repoPath.resolve("fake-build.sh");
        Files.writeString(fakeBuild,
            "#!/usr/bin/env sh\n"
                + "[ \"$PWD\" != \"$PRE_PUSH_CHECKER_ORIGINAL_ROOT\" ] || exit 7\n"
                + "[ ! -e target/classes/Stale.class ] || exit 8\n"
                + "printf '%s|%s|%s\\n' \"$PWD\" \"$PRE_PUSH_CHECKER_ORIGINAL_ROOT\" "
                + "\"$PRE_PUSH_CHECKER_SNAPSHOT_ROOT\" > \"" + observed + "\"\n"
                + "exit 0\n",
            StandardCharsets.UTF_8);
        assertTrue(fakeBuild.toFile().setExecutable(true, false));
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "add isolation harness");

        HookFixture fixture = installHookFixture(repo);
        HookRun run = runHook(fixture, Map.of(
            "PRE_PUSH_CHECKER_COMMAND", fakeBuild.toString()));

        assertEquals(run.output(), 0, run.exitCode());
        String[] values = Files.readString(observed, StandardCharsets.UTF_8)
            .trim().split("\\|", -1);
        assertEquals(3, values.length);
        assertFalse(values[0].equals(repo.getAbsolutePath()));
        assertEquals(repo.getCanonicalPath(), new File(values[1]).getCanonicalPath());
        assertEquals(normalizeMacPath(values[0]), normalizeMacPath(values[2]));
        assertTrue(Files.exists(staleOutput));
    }

    public void testMavenFallbackRetriesParallelClasspathFailureSequentially() throws Exception {
        File repo = createFallbackRepository("prepushchecker-maven-recovery", true);
        Path repoPath = repo.toPath();
        Path invocations = repoPath.resolve("maven-invocations");
        HookFixture fixture = installHookFixture(repo);
        Path fakeMaven = fixture.fakeBin().resolve("mvn");
        Files.writeString(fakeMaven,
            "#!/usr/bin/env sh\n"
                + "printf '%s\\n' \"$*\" >> \"" + invocations + "\"\n"
                + "case \" $* \" in\n"
                + "  *' -T1C '*)\n"
                + "    echo \"[ERROR] $PWD/src/main/java/App.java:[1,1] "
                + "class file for javax.persistence.Table not found\"\n"
                + "    exit 1 ;;\n"
                + "  *) exit 0 ;;\n"
                + "esac\n",
            StandardCharsets.UTF_8);
        assertTrue(fakeMaven.toFile().setExecutable(true, false));

        HookRun run = runHook(fixture, Map.of());

        assertEquals(run.output(), 0, run.exitCode());
        List<String> calls = Files.readAllLines(invocations, StandardCharsets.UTF_8);
        assertEquals(calls.toString(), 2, calls.size());
        assertTrue(calls.get(0), calls.get(0).contains("-T1C"));
        assertFalse(calls.get(1), calls.get(1).contains("-T1C"));
        assertTrue(calls.get(1), calls.get(1).contains("clean compile"));
    }

    public void testMavenFallbackDoesNotRetryOrdinaryCompileFailure() throws Exception {
        File repo = createFallbackRepository("prepushchecker-maven-real-error", true);
        Path repoPath = repo.toPath();
        Path invocations = repoPath.resolve("maven-invocations");
        HookFixture fixture = installHookFixture(repo);
        Path fakeMaven = fixture.fakeBin().resolve("mvn");
        Files.writeString(fakeMaven,
            "#!/usr/bin/env sh\n"
                + "printf '%s\\n' \"$*\" >> \"" + invocations + "\"\n"
                + "echo \"[ERROR] $PWD/src/main/java/App.java:[1,1] ';' expected\"\n"
                + "exit 1\n",
            StandardCharsets.UTF_8);
        assertTrue(fakeMaven.toFile().setExecutable(true, false));

        HookRun run = runHook(fixture, Map.of());

        assertTrue(run.output(), run.exitCode() != 0);
        assertEquals(1, Files.readAllLines(invocations, StandardCharsets.UTF_8).size());
    }

    private static File createFallbackRepository(String prefix, boolean includePom)
        throws Exception {
        File repo = createTempDir(prefix);
        runGit(repo, "init");
        runGit(repo, "config", "user.email", "test@example.com");
        runGit(repo, "config", "user.name", "Test User");
        Path repoPath = repo.toPath();
        Path source = repoPath.resolve("src/main/java/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}\n", StandardCharsets.UTF_8);
        if (includePom) {
            Files.writeString(repoPath.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion>"
                    + "<groupId>test</groupId><artifactId>app</artifactId>"
                    + "<version>1</version></project>\n",
                StandardCharsets.UTF_8);
        }
        Files.writeString(repoPath.resolve(".gitignore"),
            ".idea/\nbuild-count\nmaven-invocations\nobserved-worktree\ntarget/\n",
            StandardCharsets.UTF_8);
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "init");
        Files.writeString(source, "class App { int value; }\n", StandardCharsets.UTF_8);
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "change");
        return repo;
    }

    private static HookFixture installHookFixture(File repo) throws Exception {
        Path repoPath = repo.toPath();
        Path hook = repoPath.resolve(".git/hooks").resolve(GitHookInstaller.MANAGED_HOOK_NAME);
        Files.writeString(hook, GitHookInstaller.buildManagedHookScript(), StandardCharsets.UTF_8);
        assertTrue(hook.toFile().setExecutable(true, false));

        Path fakeHome = repoPath.resolve("fake-home");
        Path fakeBin = repoPath.resolve("fake-bin");
        Files.createDirectories(fakeHome.resolve(".prepush-checker"));
        Files.createDirectories(fakeBin);
        Files.writeString(fakeHome.resolve(".prepush-checker/installed"), "1\n",
            StandardCharsets.UTF_8);
        Path fakePgrep = fakeBin.resolve("pgrep");
        Files.writeString(fakePgrep, "#!/usr/bin/env sh\nexit 1\n", StandardCharsets.UTF_8);
        assertTrue(fakePgrep.toFile().setExecutable(true, false));
        return new HookFixture(
            repo,
            hook,
            fakeHome,
            fakeBin,
            runGitOutput(repo, "rev-parse", "HEAD").trim());
    }

    private static HookRun runHook(
        HookFixture fixture,
        Map<String, String> extraEnvironment
    ) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(fixture.hook().toString())
            .directory(fixture.repo())
            .redirectErrorStream(true);
        builder.environment().put("HOME", fixture.fakeHome().toString());
        builder.environment().put("PATH",
            fixture.fakeBin() + File.pathSeparator + "/usr/bin:/bin");
        builder.environment().putAll(extraEnvironment);

        Process process = builder.start();
        String input = "refs/heads/main " + fixture.headSha() + " refs/heads/main "
            + "0000000000000000000000000000000000000000\n";
        process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        return new HookRun(
            process.exitValue(),
            new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private record HookFixture(
        File repo,
        Path hook,
        Path fakeHome,
        Path fakeBin,
        String headSha
    ) {
    }

    private record HookRun(int exitCode, String output) {
    }

    private static String normalizeMacPath(String path) {
        String normalized = path.replace("//", "/");
        return normalized.startsWith("/private/var/")
            ? normalized.substring("/private".length())
            : normalized;
    }

    private static File createTempDir(String prefix) {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), prefix + "-" + System.nanoTime());
        if (!tempDir.mkdirs()) {
            fail("Failed to create temp dir: " + tempDir);
        }
        tempDir.deleteOnExit();
        return tempDir;
    }

    private static void runGit(File repo, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
            .directory(repo)
            .redirectErrorStream(true)
            .start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Timed out running git " + String.join(" ", args));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            fail("git " + String.join(" ", args) + " failed: " + output);
        }
    }

    private static String runGitOutput(File repo, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
            .directory(repo)
            .redirectErrorStream(true)
            .start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Timed out running git " + String.join(" ", args));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            fail("git " + String.join(" ", args) + " failed: " + output);
        }
        return output;
    }
}
