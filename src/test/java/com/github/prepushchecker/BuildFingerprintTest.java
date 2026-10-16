package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class BuildFingerprintTest extends BasePlatformTestCase {
    private Path repo;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        repo = Files.createTempDirectory("fingerprint-test");
        git("init", "-q");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        git("config", "commit.gpgsign", "false");
        write("src/A.java", "class A {}");
        git("add", ".");
        git("commit", "-q", "-m", "init");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            com.intellij.openapi.util.io.FileUtil.delete(repo.toFile());
        } finally {
            super.tearDown();
        }
    }

    public void testParsesPorcelainZ() {
        List<BuildFingerprint.StatusEntry> entries =
            BuildFingerprint.parsePorcelainZ(" M src/A.java\u0000?? new file.txt\u0000UU c.java\u0000");
        assertNotNull(entries);
        assertEquals(3, entries.size());
        assertTrue(entries.get(0).worktreeDiffers());
        assertTrue(entries.get(1).isUntracked());
        assertEquals("new file.txt", entries.get(1).path());
        assertTrue(entries.get(2).isConflict());
        assertNull(BuildFingerprint.parsePorcelainZ("garbage"));
    }

    public void testGitBlobIdMatchesGit() throws Exception {
        assertEquals(git("hash-object", "src/A.java").trim(),
            BuildFingerprint.gitBlobId("class A {}".getBytes(StandardCharsets.UTF_8)));
    }

    public void testEditChangesFingerprintAndRevertRestoresIt() throws Exception {
        BuildFingerprint.RootDigest clean = digest();
        assertTrue(clean.clean());

        write("src/A.java", "class A { int x; }");
        BuildFingerprint.RootDigest edited = digest();
        assertFalse(edited.clean());
        assertFalse(clean.hex().equals(edited.hex()));

        write("src/A.java", "class A {}");
        assertEquals(clean.hex(), digest().hex());
    }

    public void testCommittingKeepsFingerprint() throws Exception {
        write("src/A.java", "class A { int x; }");
        write("src/B.java", "class B {}");
        git("add", "src/B.java"); // B staged as new, A only modified in the working tree
        String beforeCommit = digest().hex();

        git("add", ".");
        git("commit", "-q", "-m", "change");
        BuildFingerprint.RootDigest afterCommit = digest();
        assertTrue(afterCommit.clean());
        assertEquals(beforeCommit, afterCommit.hex());
    }

    public void testUntrackedAndUnsavedContentCount() throws Exception {
        String clean = digest().hex();
        write("src/C.java", "class C {}");
        String untracked = digest().hex();
        assertFalse(clean.equals(untracked));
        Files.delete(repo.resolve("src/C.java"));
        assertEquals(clean, digest().hex());

        String root = repo.toRealPath().toString();
        BuildFingerprint.RootDigest unsaved = BuildFingerprint.digestRoot(root,
            Map.of(root + "/src/A.java", BuildFingerprint.gitBlobId("class A { y }".getBytes(StandardCharsets.UTF_8))));
        assertNotNull(unsaved);
        assertFalse(unsaved.clean());
        assertFalse(clean.equals(unsaved.hex()));
    }

    public void testBranchKeyRoundTrip() {
        List<BuildFingerprint.BranchRef> refs = List.of(
            new BuildFingerprint.BranchRef("/a", "main"),
            new BuildFingerprint.BranchRef("/b", BuildFingerprint.DETACHED_PREFIX + "abc1234"));
        assertEquals(refs, BuildFingerprint.decodeBranchKey(BuildFingerprint.encodeBranchKey(refs)));
        assertTrue(refs.get(1).isDetached());
    }

    private BuildFingerprint.RootDigest digest() throws IOException {
        BuildFingerprint.RootDigest digest = BuildFingerprint.digestRoot(repo.toRealPath().toString(), Map.of());
        assertNotNull(digest);
        return digest;
    }

    private void write(String relative, String content) throws IOException {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String git(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, process.waitFor());
        return output;
    }
}
