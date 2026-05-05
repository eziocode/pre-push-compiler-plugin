package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
        assertTrue(script.contains("./gradlew --console=plain --quiet --parallel --build-cache $GRADLE_TASKS"));
        assertTrue(script.contains("mvn -q -T1C -Dmaven.javadoc.skip=true -Dmaven.compiler.useIncrementalCompilation=false \"$MAVEN_GOAL\""));
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
        assertTrue(script.contains("project_uses_lombok"));
        assertTrue(script.contains("only_generated_symbol_errors"));
        assertTrue(script.contains("Build-tool fallback reported only Lombok-generated symbol errors"));
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
}
