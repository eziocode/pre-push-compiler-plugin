package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

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
}
