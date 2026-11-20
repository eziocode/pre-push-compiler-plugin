package com.github.prepushchecker.review;

import com.github.prepushchecker.ProcessExecution;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The few git commands the review needs. Stdout only; no prompts; short timeouts. */
final class ReviewGit {
    private static final Logger LOG = Logger.getInstance(ReviewGit.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int BATCH = 200;

    private ReviewGit() {}

    /** The working tree root containing {@code file}, or {@code null}. */
    static @Nullable Path repoRoot(@NotNull Path file) {
        Path dir = Files.isDirectory(file) ? file : file.getParent();
        while (dir != null) {
            if (Files.exists(dir.resolve(".git"))) return dir;
            dir = dir.getParent();
        }
        return null;
    }

    /** Real path of {@code root} (symlinks resolved), so IDE and hook paths give the same key. */
    static @NotNull Path canonical(@NotNull Path root) {
        try {
            return root.toRealPath();
        } catch (Exception e) {
            return root.toAbsolutePath().normalize();
        }
    }

    static @NotNull String relative(@NotNull Path root, @NotNull Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** Blob ids git would store for the working-tree files (filters applied, like {@code git add}). */
    static @NotNull Map<String, String> hashObjects(@NotNull Path root, @NotNull List<String> relPaths) {
        return hashObjects(root, relPaths, false);
    }

    /**
     * Like {@link #hashObjects(Path, List)}; with {@code write} the blobs are also stored in the
     * object database (as {@code git add} would), so a later {@link #diffBlobs} can read them.
     */
    static @NotNull Map<String, String> hashObjects(@NotNull Path root, @NotNull List<String> relPaths, boolean write) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> existing = new ArrayList<>();
        for (String p : relPaths) if (Files.isRegularFile(root.resolve(p))) existing.add(p);
        for (int i = 0; i < existing.size(); i += BATCH) {
            List<String> batch = existing.subList(i, Math.min(existing.size(), i + BATCH));
            List<String> cmd = new ArrayList<>(write ? List.of("hash-object", "-w", "--") : List.of("hash-object", "--"));
            cmd.addAll(batch);
            String stdout = git(root, cmd);
            if (stdout == null) continue;
            String[] lines = stdout.strip().split("\n");
            if (lines.length != batch.size()) continue;
            for (int j = 0; j < lines.length; j++) out.put(batch.get(j), lines[j].trim());
        }
        return out;
    }

    /** Blob ids of {@code relPaths} in commit {@code rev}; files missing there are absent. */
    static @NotNull Map<String, String> blobsAt(@NotNull Path root, @NotNull String rev, @NotNull List<String> relPaths) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < relPaths.size(); i += BATCH) {
            List<String> batch = relPaths.subList(i, Math.min(relPaths.size(), i + BATCH));
            List<String> cmd = new ArrayList<>(List.of("ls-tree", "-r", "-z", rev, "--"));
            cmd.addAll(batch);
            String stdout = git(root, cmd);
            if (stdout == null) continue;
            for (String entry : stdout.split("\0")) {
                int tab = entry.indexOf('\t');
                if (tab < 0) continue;
                String[] meta = entry.substring(0, tab).split(" ");
                if (meta.length == 3 && meta[1].equals("blob")) out.put(entry.substring(tab + 1), meta[2]);
            }
        }
        return out;
    }

    /** The subset of {@code relPaths} that git tracks. */
    static @NotNull java.util.Set<String> tracked(@NotNull Path root, @NotNull List<String> relPaths) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (int i = 0; i < relPaths.size(); i += BATCH) {
            List<String> batch = relPaths.subList(i, Math.min(relPaths.size(), i + BATCH));
            List<String> cmd = new ArrayList<>(List.of("ls-files", "-z", "--"));
            cmd.addAll(batch);
            String stdout = git(root, cmd);
            if (stdout == null) continue;
            for (String p : stdout.split("\0")) if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    static boolean isTracked(@NotNull Path root, @NotNull String relPath) {
        return git(root, List.of("ls-files", "--error-unmatch", "--", relPath)) != null;
    }

    /** {@code rev} resolved to a commit id, or {@code null} when git does not have that commit. */
    static @Nullable String resolveCommit(@NotNull Path root, @NotNull String rev) {
        String out = git(root, List.of("rev-parse", "--verify", "-q", rev + "^{commit}"));
        return out == null || out.isBlank() ? null : out.trim();
    }

    static final int REMOTE_HISTORY_COMMITS = 50;

    /**
     * Blob ids {@code relPath} has had in remote-tracking history: before and after each of the
     * last {@link #REMOTE_HISTORY_COMMITS} remote commits that touched it. Content with one of
     * these ids is already on the remote (a revert, or a merge of the remote's own changes).
     */
    static @NotNull java.util.Set<String> remoteBlobs(@NotNull Path root, @NotNull String relPath) {
        java.util.Set<String> out = new java.util.HashSet<>();
        String stdout = git(root, List.of("log", "--remotes", "-n", String.valueOf(REMOTE_HISTORY_COMMITS),
            "--format=", "--raw", "--no-abbrev", "--no-renames", "--", relPath));
        if (stdout == null) return out;
        for (String line : stdout.split("\n")) {
            if (!line.startsWith(":")) continue;
            int tab = line.indexOf('\t');
            String[] meta = (tab < 0 ? line : line.substring(0, tab)).split(" ");
            if (meta.length < 4) continue;
            for (String id : new String[]{meta[2], meta[3]}) {
                if (!id.isEmpty() && !id.chars().allMatch(c -> c == '0')) out.add(id);
            }
        }
        return out;
    }

    static @Nullable String headSha(@NotNull Path root) {
        String out = git(root, List.of("rev-parse", "--verify", "-q", "HEAD"));
        return out == null || out.isBlank() ? null : out.trim();
    }

    /**
     * Working tree vs HEAD for one tracked file: empty when unchanged, {@code null} when git
     * failed. A failure must not be read as "no changes", or the file silently skips review.
     */
    static @Nullable String diffAgainstHead(@NotNull Path root, @NotNull String relPath) {
        return git(root, withFunctionContext(List.of("diff", "--no-color", "--no-ext-diff", "-U5", "-W", "HEAD", "--", relPath)));
    }

    /**
     * Files with local changes (staged, unstaged or untracked, not deleted and not ignored), as
     * git sees them on disk now. Unlike the IDE's change list this is never behind a save.
     */
    static @NotNull List<String> changedFiles(@NotNull Path root) {
        // Not "git status --porcelain": its fixed-column format does not survive the trimmed stdout.
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        String tracked = headSha(root) == null
            ? git(root, List.of("ls-files", "-z", "--cached"))
            : git(root, List.of("diff", "--name-only", "-z", "--no-renames", "--diff-filter=d", "HEAD", "--"));
        String untracked = git(root, List.of("ls-files", "-z", "--others", "--exclude-standard"));
        for (String stdout : new String[]{tracked, untracked}) {
            if (stdout == null) continue;
            for (String p : stdout.split("\0")) {
                if (!p.isBlank()) out.add(p);
            }
        }
        return new ArrayList<>(out);
    }

    /** {@code base..head} for one file. */
    static @NotNull String diffRange(@NotNull Path root, @NotNull String base, @NotNull String head,
                                     @NotNull String relPath) {
        String out = git(root, withFunctionContext(List.of("diff", "--no-color", "--no-ext-diff", "-U5", "-W", base, head, "--", relPath)));
        return out == null ? "" : out;
    }

    /**
     * Unified diff between two blobs, or {@code null} when either is not in the object database.
     * With {@code ignoreWhitespace} whitespace-only changes are left out.
     */
    static @Nullable String diffBlobs(@NotNull Path root, @NotNull String from, @NotNull String to,
                                      boolean ignoreWhitespace) {
        List<String> cmd = new ArrayList<>(List.of("diff", "--no-color", "--no-ext-diff", "-U5"));
        if (ignoreWhitespace) cmd.add("-w");
        cmd.add(from);
        cmd.add(to);
        return git(root, cmd);
    }

    /** Content of a blob, or {@code null} when it is not in the object database. */
    static @Nullable String catBlob(@NotNull Path root, @NotNull String blob) {
        return git(root, List.of("cat-file", "blob", blob));
    }

    static final int MAX_MERGES = 20;
    static final int MAX_OWN_COMMITS = 150;

    /**
     * Parents of the not-yet-pushed merge commits in {@code head}'s history that are already on a
     * remote: the code a {@code git merge main} brought in. Diffing a file against such a parent
     * shows only what the developer's own side changed. Empty when there are no such merges.
     */
    static @NotNull List<String> mergedRemoteParents(@NotNull Path root, @NotNull String head) {
        String merges = git(root, List.of("rev-list", "--merges", "--parents", "-n", String.valueOf(MAX_MERGES),
            head, "--not", "--remotes"));
        if (merges == null || merges.isBlank()) return List.of();
        String local = git(root, List.of("rev-list", head, "--not", "--remotes"));
        java.util.Set<String> unpushed = new java.util.HashSet<>();
        if (local != null) for (String l : local.split("\n")) if (!l.isBlank()) unpushed.add(l.trim());
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String line : merges.split("\n")) {
            String[] ids = line.trim().split("\\s+");
            // ids[0] is the merge itself; a parent not in the unpushed set is already on a remote.
            for (int i = 1; i < ids.length; i++) if (!ids[i].isEmpty() && !unpushed.contains(ids[i])) out.add(ids[i]);
        }
        return new ArrayList<>(out);
    }

    /** {@code true} when the unpushed history of {@code head} contains a merge commit. */
    static boolean hasUnpushedMerge(@NotNull Path root, @NotNull String head) {
        String out = git(root, List.of("rev-list", "--merges", "-n", "1", head, "--not", "--remotes"));
        return out != null && !out.isBlank();
    }

    /**
     * Files the developer changed themselves in the unpushed history of {@code head}: files touched
     * by non-merge commits on the first-parent chain, plus files a merge changed relative to
     * <em>all</em> its parents (conflict resolutions). Code a {@code git merge} brought in from a
     * second parent is not included, whether or not that parent is known to a remote. {@code null}
     * when git failed (the caller must not narrow anything then).
     */
    static @Nullable java.util.Set<String> ownChangedFiles(@NotNull Path root, @NotNull String head) {
        String commits = git(root, List.of("rev-list", "--first-parent", head, "--not", "--remotes"));
        if (commits == null) return null;
        java.util.Set<String> out = new java.util.HashSet<>();
        String[] ids = commits.split("\n");
        if (ids.length > MAX_OWN_COMMITS) return null; // one git call per commit: too slow, do not narrow
        for (String c : ids) {
            c = c.trim();
            if (c.isEmpty()) continue;
            String names;
            String parents = git(root, List.of("rev-list", "--parents", "-n", "1", c));
            boolean isMerge = parents != null && parents.trim().split("\\s+").length > 2;
            if (isMerge) {
                names = git(root, List.of("diff-tree", "--cc", "--no-commit-id", "--name-only", "-r", "-z", c));
            } else {
                // --root: a first commit has no parent to diff against, so its files would be dropped.
                names = git(root, List.of("diff-tree", "--root", "--no-commit-id", "--name-only", "-r", "-z",
                    "--no-renames", "--diff-filter=ACMR", c));
            }
            if (names == null) return null;
            for (String p : names.split("\0")) if (!p.isBlank()) out.add(p);
        }
        return out;
    }

    /** {@code true} when {@code base..head} changes only whitespace in {@code relPath}. */
    static boolean whitespaceOnly(@NotNull Path root, @NotNull String base, @NotNull String head,
                                  @NotNull String relPath) {
        String all = git(root, List.of("diff", "--no-ext-diff", "--numstat", base, head, "--", relPath));
        if (all == null || all.isBlank()) return false; // no change at all, or git failed: not this case
        String real = git(root, List.of("diff", "--no-ext-diff", "-w", "--ignore-blank-lines", "--no-color", "-U0",
            base, head, "--", relPath));
        return real != null && !real.lines().anyMatch(l -> l.startsWith("@@"));
    }

    /** Unpushed and uncommitted files of {@code root}: what can still need a review. */
    static @Nullable java.util.Set<String> pendingFiles(@NotNull Path root) {
        String status = git(root, List.of("status", "--porcelain=v1", "-z", "--no-renames", "--untracked-files=all"));
        if (status == null) return null;
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String entry : status.split("\0")) {
            if (entry.length() > 3) out.add(entry.substring(3));
        }
        String head = git(root, List.of("rev-parse", "--revs-only", "HEAD"));
        if (head == null) return null;
        if (head.isBlank()) return out; // Valid repository before its first commit.
        String first = git(root, List.of("rev-list", "--reverse", head.trim(), "--not", "--remotes"));
        if (first == null) return null;
        String oldest = first.lines().filter(l -> !l.isBlank()).findFirst().orElse(null);
        if (oldest == null) return out; // everything committed is on a remote
        String base = resolveCommit(root, oldest.trim() + "^");
        String names = base == null
            ? git(root, List.of("ls-tree", "-r", "-z", "--name-only", "HEAD"))
            : git(root, List.of("diff", "--name-only", "-z", "--no-renames", base, "HEAD", "--"));
        if (names == null) return null;
        for (String p : names.split("\0")) if (!p.isBlank()) out.add(p);
        return out;
    }

    /** Merge base of HEAD and its upstream, or {@code null} when there is no upstream. */
    static @Nullable String upstreamBase(@NotNull Path root) {
        String out = git(root, List.of("merge-base", "HEAD", "@{upstream}"));
        return out == null || out.isBlank() ? null : out.trim();
    }

    static @Nullable String showAt(@NotNull Path root, @NotNull String rev, @NotNull String relPath) {
        return git(root, List.of("show", rev + ":" + relPath));
    }

    /**
     * {@code -W} shows the whole changed method, so a description list far below the added
     * lines is still in the diff. Git's built-in Java/Kotlin function patterns are enabled with
     * an attributes file; the repository's own {@code .gitattributes} still applies on top.
     */
    private static List<String> withFunctionContext(List<String> diffArgs) {
        Path attributes = functionAttributesFile();
        if (attributes == null) return diffArgs;
        List<String> out = new ArrayList<>(List.of("-c", "core.attributesFile=" + attributes));
        out.addAll(diffArgs);
        return out;
    }

    private static volatile Path functionAttributes;

    private static @Nullable Path functionAttributesFile() {
        Path p = functionAttributes;
        if (p != null && Files.isRegularFile(p)) return p;
        try {
            p = Files.createTempFile("prepushchecker-review", ".gitattributes");
            Files.writeString(p, "*.java diff=java\n*.kt diff=kotlin\n*.kts diff=kotlin\n*.groovy diff=java\n*.scala diff=java\n");
            p.toFile().deleteOnExit();
            functionAttributes = p;
            return p;
        } catch (Exception e) {
            LOG.debug("cannot write review attributes file", e);
            return null;
        }
    }

    private static @Nullable String git(@NotNull Path root, @NotNull List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-c");
        cmd.add("core.quotepath=off");
        cmd.addAll(args);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(root.toFile());
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("GIT_ASKPASS", "");
            pb.environment().put("SSH_ASKPASS", "");
            ProcessExecution.Result r = args.contains("-z") || args.contains("rev-list")
                ? ProcessExecution.runWithCompleteOutput(pb, TIMEOUT) : ProcessExecution.run(pb, TIMEOUT);
            return r.isSuccess() ? r.stdout() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            LOG.debug("git " + args + " failed in " + root, e);
            return null;
        }
    }
}
