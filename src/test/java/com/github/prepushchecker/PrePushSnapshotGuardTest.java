package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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

    public void testSnapshotRiskSkipsWhenNoLocalRelevantChangesExist() {
        PrePushSnapshotGuard.PushSnapshotRisk risk =
            PrePushSnapshotGuard.analyzeSnapshotRisk(getProject(), List.of("src/main/java/App.java"));

        assertFalse(risk.shouldValidateSnapshot());
        assertEquals(List.of("src/main/java/App.java"), risk.pushedPaths());
    }

    public void testSnapshotRiskSkipsIrrelevantPushedPaths() {
        PrePushSnapshotGuard.PushSnapshotRisk risk =
            PrePushSnapshotGuard.analyzeSnapshotRisk(getProject(), List.of("README.md"));

        assertFalse(risk.shouldValidateSnapshot());
        assertTrue(risk.pushedPaths().isEmpty());
    }

    public void testCoveredLocalPathDoesNotCountAsUnpushedRisk() {
        assertTrue(PrePushSnapshotGuard.isCoveredByPushedPaths(
            "src/main/java/App.java",
            List.of("src/main/java/App.java")
        ));
    }

    public void testAbsoluteLocalPathCanBeCoveredByProjectRelativePushedPath() {
        assertTrue(PrePushSnapshotGuard.isCoveredByPushedPaths(
            "/project/src/main/java/App.java",
            List.of("src/main/java/App.java")
        ));
    }

    public void testUnselectedLocalPathIsNotCoveredByPushedPath() {
        assertFalse(PrePushSnapshotGuard.isCoveredByPushedPaths(
            "src/main/java/Dependency.java",
            List.of("src/main/java/App.java")
        ));
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
            List.of("mvn", "-q", "-T1C", "-Dmaven.javadoc.skip=true",
                "-Dmaven.compiler.useIncrementalCompilation=false", "compile"),
            PrePushSnapshotGuard.buildCommand(
                GitHookInstaller.BuildTool.MAVEN,
                List.of("src/main/java/App.java")
            )
        );
        assertEquals(
            List.of("./mvnw", "-q", "-T1C", "-Dmaven.javadoc.skip=true",
                "-Dmaven.compiler.useIncrementalCompilation=false", "test-compile"),
            PrePushSnapshotGuard.buildCommand(
                GitHookInstaller.BuildTool.MAVEN_WRAPPER,
                List.of("src/test/java/AppTest.java")
            )
        );
    }

    public void testResolveBuildCommandRunsWrapperThroughShell() throws IOException {
        File tempDir = Files.createTempDirectory("prepushchecker-wrapper").toFile();
        File wrapper = new File(tempDir, "mvnw");
        assertTrue(wrapper.createNewFile());

        List<String> resolved = PrePushSnapshotGuard.resolveBuildCommand(
            tempDir.toPath(),
            List.of("./mvnw", "-q", "compile")
        );

        assertEquals("/bin/sh", resolved.get(0));
        assertEquals(wrapper.toPath().toString(), resolved.get(1));
        assertEquals(List.of("-q", "compile"), resolved.subList(2, resolved.size()));
    }

    public void testResolveBuildCommandSkipsMissingSystemExecutable() {
        List<String> resolved = PrePushSnapshotGuard.resolveBuildCommand(
            getProject().getBasePath() == null
                ? new File(".").toPath()
                : new File(getProject().getBasePath()).toPath(),
            List.of("prepushchecker-tool-that-should-not-exist", "compile")
        );

        assertTrue(resolved.isEmpty());
    }

    public void testFilterSnapshotErrorsKeepsOnlySelectedSourceFiles() {
        List<String> filtered = PrePushSnapshotGuard.filterSnapshotErrors(
            List.of(
                "[src/main/java/Selected.java (12, 4)] cannot find symbol",
                "[src/main/java/Unrelated.java (8, 2)] cannot find symbol"
            ),
            List.of("src/main/java/Selected.java")
        );

        assertEquals(1, filtered.size());
        assertTrue(filtered.get(0).contains("Selected.java"));
    }

    public void testFilterSnapshotErrorsKeepsAllForBuildFileChanges() {
        List<String> errors = List.of(
            "[src/main/java/Unrelated.java (8, 2)] cannot find symbol"
        );

        assertEquals(
            errors,
            PrePushSnapshotGuard.filterSnapshotErrors(errors, List.of("pom.xml"))
        );
    }

    public void testLombokGeneratedSymbolErrorDetectsGetterPattern() {
        assertTrue(PrePushSnapshotGuard.isLikelyLombokGeneratedSymbolError(
            "[src/main/java/App.java (42, 13)] cannot find symbol | symbol:   method isSkipClickModuleAndSelectingRecords()"));
        assertTrue(PrePushSnapshotGuard.isLikelyLombokGeneratedSymbolError(
            "[src/main/java/App.java (10, 5)] cannot find symbol | symbol:   method getFieldName()"));
        assertTrue(PrePushSnapshotGuard.isLikelyLombokGeneratedSymbolError(
            "[src/main/java/App.java (10, 5)] cannot find symbol | symbol:   method setFieldName()"));
    }

    public void testLombokGeneratedSymbolErrorDetectsBuilderPattern() {
        assertTrue(PrePushSnapshotGuard.isLikelyLombokGeneratedSymbolError(
            "[src/main/java/App.java (42, 13)] cannot find symbol | symbol:   class ApprovalProcessRuleBuilder"));
    }

    public void testLombokGeneratedSymbolErrorRejectsNonGeneratedMethod() {
        assertFalse(PrePushSnapshotGuard.isLikelyLombokGeneratedSymbolError(
            "[src/main/java/App.java (42, 13)] cannot find symbol | symbol:   method doSomething()"));
        assertFalse(PrePushSnapshotGuard.isLikelyLombokGeneratedSymbolError(
            "[src/main/java/App.java (10, 5)] ';' expected"));
    }

    public void testAllLombokGeneratedSymbolErrorsSuppressesAllMatchingErrors() {
        assertTrue(PrePushSnapshotGuard.allLombokGeneratedSymbolErrors(List.of(
            "[src/main/java/App.java (42, 13)] cannot find symbol | symbol:   method isFlag()",
            "[src/main/java/App.java (50, 8)] cannot find symbol | symbol:   method getName()"
        )));
    }

    public void testAllLombokGeneratedSymbolErrorsRejectsMixedErrors() {
        assertFalse(PrePushSnapshotGuard.allLombokGeneratedSymbolErrors(List.of(
            "[src/main/java/App.java (42, 13)] cannot find symbol | symbol:   method isFlag()",
            "[src/main/java/App.java (50, 8)] cannot find symbol | symbol:   method doSomething()"
        )));
    }

    public void testAllLombokGeneratedSymbolErrorsRejectsEmpty() {
        assertFalse(PrePushSnapshotGuard.allLombokGeneratedSymbolErrors(List.of()));
    }
}
