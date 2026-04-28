package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class PrePushSnapshotGuardTest extends BasePlatformTestCase {
    public void testDisabledGuardDoesNotBlock() {
        PrePushCheckerSettings.setStrictSnapshotGuardEnabled(getProject(), false);

        PrePushSnapshotGuard.SnapshotValidationResult result =
            PrePushSnapshotGuard.validateHeadSnapshotIfNeeded(getProject(), List.of(), null);

        assertFalse(result.wasChecked());
        assertTrue(result.errors().isEmpty());
    }

    public void testProjectRelativePathIsUsedForProjectFiles() {
        String basePath = getProject().getBasePath();
        assertNotNull(basePath);

        String path = PrePushSnapshotGuard.toProjectRelativePath(
            getProject(), basePath + "/src/main/java/App.java");

        assertEquals("src/main/java/App.java", path);
    }

    public void testSnapshotBuildCommandUsesNarrowGradleTaskForSourceChanges() {
        assertEquals(
            List.of("./gradlew", "--console=plain", "--quiet", "--parallel", "--build-cache", "classes"),
            PrePushSnapshotGuard.buildCommand(
                GitHookInstaller.BuildTool.GRADLE_WRAPPER,
                List.of("src/main/java/App.java")
            )
        );
    }

    public void testSnapshotBuildCommandUsesTestTaskForTestChanges() {
        assertEquals(
            List.of("gradle", "--console=plain", "--quiet", "--parallel", "--build-cache", "testClasses"),
            PrePushSnapshotGuard.buildCommand(
                GitHookInstaller.BuildTool.GRADLE,
                List.of("src/test/java/AppTest.java")
            )
        );
    }

    public void testSnapshotBuildCommandUsesFullCompileForBuildChanges() {
        assertEquals(
            List.of("./gradlew", "--console=plain", "--quiet", "--parallel", "--build-cache", "classes", "testClasses"),
            PrePushSnapshotGuard.buildCommand(
                GitHookInstaller.BuildTool.GRADLE_WRAPPER,
                List.of("build.gradle.kts")
            )
        );
    }

    public void testSnapshotBuildCommandUsesMavenCompileGoals() {
        assertEquals(
            List.of("mvn", "-q", "-T1C", "-Dmaven.javadoc.skip=true", "compile"),
            PrePushSnapshotGuard.buildCommand(
                GitHookInstaller.BuildTool.MAVEN,
                List.of("src/main/java/App.java")
            )
        );
        assertEquals(
            List.of("./mvnw", "-q", "-T1C", "-Dmaven.javadoc.skip=true", "test-compile"),
            PrePushSnapshotGuard.buildCommand(
                GitHookInstaller.BuildTool.MAVEN_WRAPPER,
                List.of("src/test/java/AppTest.java")
            )
        );
    }
}
