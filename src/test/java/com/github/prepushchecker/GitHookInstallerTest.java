package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertTrue(script.contains("mvn -q -T1C -Dmaven.javadoc.skip=true -Dmaven.compiler.useIncrementalCompilation=false \"$_maven_goal\""));
        assertTrue(script.contains("setup_maven_java_home"));
        assertTrue(script.contains("preferredJavaHome="));
        assertTrue(script.contains("try_ide_compile_with_retry"));
        assertTrue(script.contains("IntelliJ appears to be running; waiting briefly for IDE compiler service"));
        assertTrue(script.contains("IntelliJ incremental compile unavailable"));
        assertTrue(script.contains("Fallback reported generated-symbol errors; retrying IntelliJ incremental compile once before aborting"));
        assertTrue(script.contains("LAST_FALLBACK_OK_HEAD_FILE"));
        assertTrue(script.contains("Reusing previous fallback compile result for unchanged HEAD"));
        assertTrue(script.contains("compile_failure_touches_pushed_files"));
        assertTrue(script.contains("looks_like_generated_symbol_false_positive"));
        assertTrue(script.contains("only_generated_symbol_errors"));
        assertTrue(script.contains("retrying full compile scope once"));
        assertTrue(script.contains("run_build_tool_command \"classes testClasses\" \"test-compile\" \"$TMP_OUT\""));
        assertTrue(script.contains("if [ $rc -ne 0 ] && only_generated_symbol_errors \"$TMP_OUT\"; then"));
        assertFalse(script.contains("project_uses_lombok && only_generated_symbol_errors"));
        assertTrue(script.contains("Build-tool fallback reported only generated setter/getter/builder symbol errors"));
        assertTrue(script.contains("Build-tool fallback reported generated-symbol errors outside pushed files"));
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
        assertTrue(snippet.contains(GitHookInstaller.HOOK_MARKER));
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
            assertEquals(1, countOccurrences(repaired, "# " + GitHookInstaller.HOOK_MARKER));
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
            assertEquals(1, countOccurrences(repaired, "# " + GitHookInstaller.HOOK_MARKER));
            assertEquals(GitHookInstaller.buildManagedHookScript(),
                Files.readString(hooksDir.resolve(GitHookInstaller.MANAGED_HOOK_NAME), StandardCharsets.UTF_8));
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

    private static File createTempDir(String prefix) {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), prefix + "-" + System.nanoTime());
        if (!tempDir.mkdirs()) {
            fail("Failed to create temp dir: " + tempDir);
        }
        tempDir.deleteOnExit();
        return tempDir;
    }

    private static int countOccurrences(String content, String needle) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
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
}
