package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class PushValidationPathsTest extends BasePlatformTestCase {
    public void testRelevantPathDetection() {
        assertTrue(PushValidationPaths.isRelevantPath("src/main/java/App.java"));
        assertTrue(PushValidationPaths.isRelevantPath("module/build.gradle.kts"));
        assertTrue(PushValidationPaths.isRelevantPath("service/pom.xml"));

        assertFalse(PushValidationPaths.isRelevantPath("README.md"));
        assertFalse(PushValidationPaths.isRelevantPath("docs/architecture.txt"));
    }

    public void testBuildFilesAreNotTreatedAsSourceFiles() {
        assertTrue(PushValidationPaths.isBuildFile("gradle.properties"));
        assertTrue(PushValidationPaths.isBuildFile("submodule/settings.gradle"));
        assertFalse(PushValidationPaths.isCompilableSource("build.gradle.kts"));
        assertTrue(PushValidationPaths.isCompilableSource("modules/service/App.kt"));
    }
}
