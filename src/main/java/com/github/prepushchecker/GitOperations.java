package com.github.prepushchecker;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Small read-mostly helpers for inspecting a git repository's local state.
 *
 * <p>Used by the clean-commit ledger and the post-rebase/merge recompile flow.
 * Every helper is non-interactive (no terminal prompts, no editor) and fails
 * closed: when git cannot tell us the answer, the helper returns a value that
 * causes the caller to take the safe (re-compile) branch.
 */
final class GitOperations {
    private static final Logger LOG = Logger.getInstance(GitOperations.class);
    private static final int CMD_TIMEOUT_SECONDS = 30;

    private GitOperations() {
    }

    /** Output of a git invocation captured by {@link #runGit}. */
    record GitResult(int exitCode, @NotNull String stdoutTrimmed, @Nullable String error) {
        boolean isSuccess() {
            return error == null && exitCode == 0;
        }
    }

    /**
     * Returns the 40-char HEAD commit SHA for {@code repoRoot}, or {@code null} when:
     * git fails, the repo has no commits, HEAD is detached and unresolved, or the
     * directory is not a git working tree.
     */
    static @Nullable String headSha(@NotNull String repoRoot) {
        GitResult r = runGit(repoRoot, "git", "rev-parse", "HEAD");
        if (!r.isSuccess()) return null;
        String sha = r.stdoutTrimmed.trim();
        return sha.length() == 40 && sha.chars().allMatch(GitOperations::isHexDigit) ? sha : null;
    }

    /**
     * Returns {@code true} when the working tree at {@code repoRoot} has no
     * tracked modifications, no staged changes, no untracked files, and no
     * in-progress operation (rebase/merge/cherry-pick).
     *
     * <p>Returns {@code false} for any of: dirty WT, untracked files, git
     * failure, ongoing operation. We err on the side of {@code false} because
     * the caller uses this as a gate for ledger reuse / recording — a false
     * negative just causes an extra compile, while a false positive would
     * allow stale results to be reused.
     */
    static boolean isWorkingTreeFullyClean(@NotNull String repoRoot) {
        if (hasInProgressOperation(repoRoot)) return false;
        GitResult r = runGit(repoRoot, "git", "status", "--porcelain",
            "--untracked-files=normal", "--ignore-submodules=none");
        if (!r.isSuccess()) return false;
        return r.stdoutTrimmed.isEmpty();
    }

    /**
     * Detects an ongoing rebase / merge / cherry-pick / revert / bisect
     * operation by checking the well-known marker files inside {@code .git}.
     */
    private static boolean hasInProgressOperation(@NotNull String repoRoot) {
        GitResult r = runGit(repoRoot, "git", "rev-parse", "--git-dir");
        if (!r.isSuccess()) return true; // unknown -> treat as in-progress (safe)
        String gitDirRaw = r.stdoutTrimmed.trim();
        if (gitDirRaw.isEmpty()) return true;
        Path gitDir = Path.of(gitDirRaw);
        if (!gitDir.isAbsolute()) gitDir = Path.of(repoRoot).resolve(gitDirRaw);
        return Files.exists(gitDir.resolve("rebase-merge"))
            || Files.exists(gitDir.resolve("rebase-apply"))
            || Files.exists(gitDir.resolve("MERGE_HEAD"))
            || Files.exists(gitDir.resolve("CHERRY_PICK_HEAD"))
            || Files.exists(gitDir.resolve("REVERT_HEAD"))
            || Files.exists(gitDir.resolve("BISECT_LOG"));
    }

    /**
     * Runs git with non-interactive credentials, stdin from /dev/null, and a
     * short timeout. Returns a {@link GitResult} that distinguishes "git
     * answered with non-zero exit" from "git could not run at all".
     */
    static @NotNull GitResult runGit(@NotNull String repoRoot, @NotNull String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                .directory(new File(repoRoot))
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("GIT_ASKPASS", "");
            pb.environment().put("SSH_ASKPASS", "");
            pb.environment().put("GIT_MERGE_AUTOEDIT", "no");
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (out.length() > 0) out.append('\n');
                    out.append(line);
                }
            }
            if (!p.waitFor(CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new GitResult(-1, out.toString().trim(), "timed out after " + CMD_TIMEOUT_SECONDS + "s");
            }
            return new GitResult(p.exitValue(), out.toString().trim(), null);
        } catch (Exception e) {
            LOG.debug("git failed at " + repoRoot + ": " + String.join(" ", command), e);
            return new GitResult(-1, "", e.getMessage() == null ? "git invocation failed" : e.getMessage());
        }
    }

    private static boolean isHexDigit(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
