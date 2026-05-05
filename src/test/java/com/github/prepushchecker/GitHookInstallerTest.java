package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;
import java.io.IOException;

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

    private static File createTempDir(String prefix) {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), prefix + "-" + System.nanoTime());
        if (!tempDir.mkdirs()) {
            fail("Failed to create temp dir: " + tempDir);
        }
        tempDir.deleteOnExit();
        return tempDir;
    }
}
