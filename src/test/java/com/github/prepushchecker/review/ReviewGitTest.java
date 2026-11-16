package com.github.prepushchecker.review;

import junit.framework.TestCase;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ReviewGitTest extends TestCase {

    private Path repo;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        repo = Files.createTempDirectory("review-git").toRealPath();
        git("init", "-q");
        git("config", "user.email", "t@example.com");
        git("config", "user.name", "t");
        git("config", "commit.gpgsign", "false");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/A.java"), "class A {\n    void a() {\n        int x = 1;\n    }\n}\n");
        git("add", ".");
        git("commit", "-q", "-m", "init");
    }

    @Override
    protected void tearDown() throws Exception {
        try (var walk = Files.walk(repo)) {
            walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
        super.tearDown();
    }

    public void testWorkingHashEqualsCommittedBlob() throws Exception {
        Files.writeString(repo.resolve("src/A.java"), "class A {\n    void a() {\n        int x = 2;\n    }\n}\n");
        String working = ReviewGit.hashObjects(repo, List.of("src/A.java")).get("src/A.java");
        assertNotNull(working);
        git("commit", "-qam", "change");
        assertEquals(working, ReviewGit.blobsAt(repo, "HEAD", List.of("src/A.java")).get("src/A.java"));
    }

    public void testTrackedAndDiff() throws Exception {
        Files.writeString(repo.resolve("src/B.java"), "class B {}\n");
        Files.writeString(repo.resolve("src/A.java"), "class A {\n    void a() {\n        int x = 3;\n    }\n}\n");
        assertEquals(java.util.Set.of("src/A.java"), ReviewGit.tracked(repo, List.of("src/A.java", "src/B.java")));
        String diff = ReviewGit.diffAgainstHead(repo, "src/A.java");
        assertTrue(diff, diff.contains("+        int x = 3;"));
        assertTrue(diff, diff.contains("void a()"));
        assertEquals(repo, ReviewGit.repoRoot(repo.resolve("src/A.java")));
        Map<String, String> blobs = ReviewGit.blobsAt(repo, "HEAD", List.of("src/A.java", "src/B.java"));
        assertEquals(1, blobs.size());
    }

    public void testRemoteBlobsSeeRevertedAndMergedContent() throws Exception {
        Path remote = Files.createTempDirectory("review-remote").toRealPath();
        try {
            git("init", "-q", "--bare", remote.toString());
            git("remote", "add", "origin", remote.toString());
            git("push", "-q", "origin", "HEAD:refs/heads/main");
            String v1 = ReviewGit.blobsAt(repo, "HEAD", List.of("src/A.java")).get("src/A.java");
            Files.writeString(repo.resolve("src/A.java"), "class A { int v2; }\n");
            git("commit", "-qam", "v2");
            git("push", "-q", "origin", "HEAD:refs/heads/main");
            git("fetch", "-q", "origin");
            String v2 = ReviewGit.blobsAt(repo, "HEAD", List.of("src/A.java")).get("src/A.java");

            // git revert of pushed v2 restores v1: already on the remote.
            git("revert", "--no-edit", "HEAD");
            java.util.Set<String> known = ReviewGit.remoteBlobs(repo, "src/A.java");
            assertTrue(known.contains(v1));
            assertTrue(known.contains(v2));

            Files.writeString(repo.resolve("src/A.java"), "class A { int fresh; }\n");
            String fresh = ReviewGit.hashObjects(repo, List.of("src/A.java")).get("src/A.java");
            assertFalse(known.contains(fresh));
            assertNull(ReviewGit.resolveCommit(repo, "1234567890123456789012345678901234567890"));
            assertNotNull(ReviewGit.resolveCommit(repo, "HEAD"));
        } finally {
            try (var walk = Files.walk(remote)) {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    private void git(String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(out, 0, p.waitFor());
    }
}
