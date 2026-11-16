package com.github.prepushchecker;

import junit.framework.TestCase;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the hook's own push-range functions ({@code push_base}, {@code collect_changed_files} and
 * the PUSH_RANGE_* block) against real repositories with a bare remote: reverts, deletions,
 * new branches, unfetched remotes and pushing a branch other than HEAD.
 */
public class GitHookPushRangeTest extends TestCase {

    private static final String NULL = "0000000000000000000000000000000000000000";
    private Path dir;
    private Path repo;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        dir = Files.createTempDirectory("hook-range").toRealPath();
        Path remote = dir.resolve("remote.git");
        run(dir, "git", "init", "-q", "--bare", remote.toString());
        repo = dir.resolve("work");
        run(dir, "git", "init", "-q", "-b", "main", repo.toString());
        git("config", "user.email", "t@example.com");
        git("config", "user.name", "t");
        git("config", "commit.gpgsign", "false");
        git("remote", "add", "origin", remote.toString());
        write("A.java", "class A { int v = 1; }\n");
        write("B.java", "class B {}\n");
        git("add", ".");
        git("commit", "-q", "-m", "init");
        git("push", "-q", "origin", "main");
    }

    @Override
    protected void tearDown() throws Exception {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
        super.tearDown();
    }

    public void testCommitAndRevertOnExistingBranchPushesNoFiles() throws Exception {
        String remote = sha("origin/main");
        write("A.java", "class A { int v = 2; }\n");
        git("commit", "-qam", "change");
        git("revert", "--no-edit", "HEAD");
        assertEquals("", changed(update("main", sha("HEAD"), remote)));
    }

    public void testCommitAndRevertOnNewBranchPushesNoFiles() throws Exception {
        git("checkout", "-q", "-b", "feature");
        write("A.java", "class A { int v = 2; }\n");
        git("commit", "-qam", "change");
        git("revert", "--no-edit", "HEAD");
        write("B.java", "class B { int b; }\n");
        git("commit", "-qam", "real change");
        // Before: every commit's files were listed, so the reverted A.java was gated too.
        assertEquals("B.java", changed(update("feature", sha("HEAD"), NULL)));
    }

    public void testBranchDeletionPushesNoFiles() throws Exception {
        write("A.java", "class A { int v = 3; }\n");
        git("commit", "-qam", "local work");
        assertEquals("", changed("refs/heads/(delete) " + NULL + " refs/heads/old " + sha("origin/main")));
    }

    public void testUnfetchedRemoteFallsBackToOutgoingCommits() throws Exception {
        write("B.java", "class B { int b; }\n");
        git("commit", "-qam", "mine");
        // The remote moved to a commit this clone never fetched.
        String unknown = "1234567890123456789012345678901234567890";
        assertEquals("B.java", changed(update("main", sha("HEAD"), unknown)));
    }

    public void testRangeUsesPushedCommitNotHead() throws Exception {
        String base = sha("origin/main");
        git("checkout", "-q", "-b", "other");
        write("B.java", "class B { int other; }\n");
        git("commit", "-qam", "other work");
        String other = sha("HEAD");
        git("checkout", "-q", "main");
        String range = shell(update("other", other, NULL), "printf '%s %s' \"$PUSH_RANGE_BASE\" \"$PUSH_RANGE_HEAD\"");
        assertEquals(base + " " + other, range);
    }

    public void testRangeSkipsDeletionLines() throws Exception {
        String remote = sha("origin/main");
        write("A.java", "class A { int v = 4; }\n");
        git("commit", "-qam", "change");
        String input = "refs/heads/(delete) " + NULL + " refs/heads/old " + remote + "\n"
            + update("main", sha("HEAD"), remote);
        assertEquals(remote + " " + sha("HEAD"),
            shell(input, "printf '%s %s' \"$PUSH_RANGE_BASE\" \"$PUSH_RANGE_HEAD\""));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String update(String branch, String local, String remote) {
        return "refs/heads/" + branch + " " + local + " refs/heads/" + branch + " " + remote;
    }

    private String changed(String hookInput) throws Exception {
        return shell(hookInput, "printf '%s\\n' \"$HOOK_INPUT\" | collect_changed_files | sed '/^$/d' | sort -u | tr '\\n' ' ' | sed 's/ $//'");
    }

    /** Runs the hook's functions and range block with {@code hookInput}, then {@code tail}. */
    private String shell(String hookInput, String tail) throws Exception {
        String hook = GitHookInstaller.buildManagedHookScript();
        String script = "NULL_SHA=" + NULL + "\n"
            + section(hook, "push_base() {", "\n}\n")
            + section(hook, "collect_changed_files() {", "\n}\n")
            + "HOOK_INPUT=\"$(cat)\"\n"
            + section(hook, "PUSH_RANGE_BASE=''", "PREPUSH_RANGE_EOF\n", true)
            + tail + "\n";
        Path file = dir.resolve("harness.sh");
        Files.writeString(file, script);
        Process p = new ProcessBuilder("sh", file.toString()).directory(repo.toFile()).start();
        p.getOutputStream().write((hookInput + "\n").getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(err, 0, p.waitFor());
        return out.strip();
    }

    private static String section(String hook, String start, String end) {
        return section(hook, start, end, false);
    }

    /** From {@code start} through the first {@code end} after it (end included). */
    private static String section(String hook, String start, String end, boolean lastOfTwo) {
        int from = hook.indexOf(start);
        assertTrue("hook has " + start, from >= 0);
        int to = hook.indexOf(end, from + start.length());
        if (lastOfTwo) to = hook.indexOf(end, to + end.length()); // heredoc: opening line, then terminator
        assertTrue("hook has end of " + start, to >= 0);
        return hook.substring(from, to + end.length()) + "\n";
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(repo.resolve(name), content);
    }

    private String sha(String rev) throws Exception {
        return run(repo, "git", "rev-parse", rev).strip();
    }

    private void git(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        run(repo, cmd.toArray(new String[0]));
    }

    private static String run(Path where, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(where.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(String.join(" ", cmd) + "\n" + out, 0, p.waitFor());
        return out;
    }
}
