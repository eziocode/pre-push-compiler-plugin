package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ExternalPushErrorLoaderTest extends BasePlatformTestCase {
    public void testParseMavenCompilerErrorWithSymbolDetails() {
        String basePath = getProject().getBasePath();
        assertNotNull(basePath);

        List<String> errors = ExternalPushErrorLoader.parseErrors(getProject(), List.of(
            "[ERROR] " + basePath + "/src/main/java/App.java:[42,13] cannot find symbol",
            "[ERROR]   symbol:   class ApprovalProcessRuleBuilder",
            "[ERROR]   location: class com.zoho.crm.approvalProcess.ApprovalProcessRule"
        ));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("[src/main/java/App.java (42, 13)] cannot find symbol"));
        assertTrue(errors.get(0).contains("symbol:   class ApprovalProcessRuleBuilder"));
        assertTrue(errors.get(0).contains("location: class com.zoho.crm.approvalProcess.ApprovalProcessRule"));
    }

    public void testParsePrivateMacPathAsProjectRelative() {
        String basePath = getProject().getBasePath();
        assertNotNull(basePath);

        List<String> errors = ExternalPushErrorLoader.parseErrors(getProject(), List.of(
            "[ERROR] /private" + basePath + "/src/main/java/App.java:[5,9] cannot find symbol"
        ));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("[src/main/java/App.java (5, 9)] cannot find symbol"));
    }

    public void testParseDetachedSnapshotWorktreePathAsProjectRelative() {
        List<String> errors = ExternalPushErrorLoader.parseErrors(getProject(), List.of(
            "[ERROR] /tmp/prepushchecker-snapshot.ABC123/worktree/src/main/java/App.java:"
                + "[7,3] package com.example.shared does not exist"
        ));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains(
            "[src/main/java/App.java (7, 3)] package com.example.shared does not exist"));
    }

    public void testSuccessfulHookRunClearsPreviousErrors() throws Exception {
        Path log = Files.createTempFile("prepushchecker-success-", ".log");
        try {
            CompilationErrorService service = CompilationErrorService.getInstance(getProject());
            service.setErrors(List.of("stale error"));
            Files.writeString(log, "[pre-push-checker] exit=0\n", StandardCharsets.UTF_8);

            ExternalPushErrorLoader.loadFromLogIfFailed(getProject(), log);

            assertTrue(service.getErrors().isEmpty());
        } finally {
            Files.deleteIfExists(log);
        }
    }

    public void testParseIdeFormattedErrorPreservesNavigableEntry() {
        String entry = "[src/main/java/App.java (12, 7)] cannot find symbol";
        assertEquals(
            List.of(entry),
            ExternalPushErrorLoader.parseErrors(getProject(), List.of(entry)));
    }
}
