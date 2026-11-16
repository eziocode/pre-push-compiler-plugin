package com.github.prepushchecker.commitgen;

import junit.framework.TestCase;

public class SensitivePathsTest extends TestCase {

    public void testPluginStateAndSecretsAreExcluded() {
        assertTrue(SensitivePaths.isExcluded(".idea/pre-push-checker/server.port"));
        assertTrue(SensitivePaths.isExcluded("/Users/x/repo/.idea/pre-push-checker/settings"));
        assertTrue(SensitivePaths.isExcluded(".idea/workspace.xml"));
        assertTrue(SensitivePaths.isExcluded("config/.env"));
        assertTrue(SensitivePaths.isExcluded("certs/server.pem"));
        assertTrue(SensitivePaths.isExcluded("C:\\repo\\.idea\\pre-push-checker\\bypass-token"));
        assertFalse(SensitivePaths.isExcluded("src/testcases/LeadTest.java"));
        assertFalse(SensitivePaths.isExcluded(".idea/codeStyles/Project.xml"));
        assertFalse(SensitivePaths.isExcluded("docs/environment.md"));
    }

    public void testDropExcludedRemovesOnlyThoseSections() {
        String diff = "diff --git a/A.java b/A.java\n+a\n"
            + "diff --git a/.idea/pre-push-checker/server.port b/.idea/pre-push-checker/server.port\n+51234\n+secret-token\n"
            + "diff --git a/B.java b/B.java\n+b\n";
        String out = SensitivePaths.dropExcluded(diff);
        assertEquals("diff --git a/A.java b/A.java\n+a\ndiff --git a/B.java b/B.java\n+b\n", out);
        assertEquals("no diff", SensitivePaths.dropExcluded("no diff"));
    }

    public void testNewFilesDiffSkipsPluginFiles() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("sens");
        java.nio.file.Path port = dir.resolve(".idea/pre-push-checker/server.port");
        java.nio.file.Files.createDirectories(port.getParent());
        java.nio.file.Files.writeString(port, "51234\ntoken\n");
        assertTrue(SensitivePaths.isExcluded(port.toString()));
    }
}
