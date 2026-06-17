package com.github.prepushchecker.commitgen;

import com.intellij.openapi.project.Project;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Assembles the system prompt (rules) and user prompt (git diff) that are
 * passed to whichever AI provider is configured.
 *
 * <h3>Diff collection</h3>
 * Uses IntelliJ's {@link GitRepositoryManager} to enumerate <em>all</em> git
 * repositories in the project (handles multi-root projects, submodules, and
 * repos whose root differs from {@code project.getBasePath()}). For each repo:
 * <ol>
 *   <li>Tries {@code git diff --cached} (staged changes).</li>
 *   <li>Falls back to {@code git diff HEAD} if the staged diff is empty.</li>
 * </ol>
 * Results from all repos are concatenated.
 */
final class CommitMessagePromptBuilder {

    private static final int MAX_DIFF_CHARS = 16_000;

    private static final List<String> DEFAULT_RULE_FILE_CANDIDATES = List.of(
        ".github/commit-instructions.md",
        "COMMIT_RULES.md"
    );

    private CommitMessagePromptBuilder() {}

    record Prompt(@NotNull String system, @NotNull String user) {}

    static @NotNull Prompt build(@NotNull Project project) throws Exception {
        String diff = collectDiff(project);
        if (diff == null || diff.isBlank()) {
            throw new IllegalStateException(
                "No staged or uncommitted changes found to generate a commit message from.\n"
                    + "Stage or modify at least one file, then try again.");
        }
        return buildForDiff(project, diff);
    }

    static @NotNull Prompt buildForDiff(@NotNull Project project, @NotNull String diff) {
        if (diff.isBlank()) throw new IllegalArgumentException("No diff content.");
        if (diff.length() > MAX_DIFF_CHARS) {
            diff = diff.substring(0, MAX_DIFF_CHARS) + "\n\n[diff truncated — first "
                + MAX_DIFF_CHARS + " chars shown]";
        }
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        return new Prompt(buildSystemPrompt(project, s), "Git diff:\n```\n" + diff + "\n```");
    }

    // ── Diff collection ───────────────────────────────────────────────────────

    /**
     * Collects the combined diff across all git repositories in the project.
     * Uses {@link GitRepositoryManager} so that multi-root projects, repos
     * whose root differs from {@code project.getBasePath()}, and repos opened
     * as a module root are all handled correctly.
     */
    @Nullable
    private static String collectDiff(@NotNull Project project) throws Exception {
        // Primary: use GitRepositoryManager to find all real git repo roots
        List<GitRepository> repos = GitRepositoryManager.getInstance(project).getRepositories();
        if (!repos.isEmpty()) {
            StringBuilder combined = new StringBuilder();
            for (GitRepository repo : repos) {
                String root = repo.getRoot().getPath();
                // 1. staged changes
                String staged = runGitInDir(root, "diff", "--cached");
                if (staged != null && !staged.isBlank()) {
                    combined.append(staged);
                } else {
                    // 2. unstaged (working tree vs HEAD)
                    String unstaged = runGitInDir(root, "diff");
                    if (unstaged != null && !unstaged.isBlank()) {
                        combined.append(unstaged);
                    } else {
                        // 3. last resort: HEAD diff (handles initial commit edge case)
                        String head = runGitInDir(root, "diff", "HEAD");
                        if (head != null && !head.isBlank()) combined.append(head);
                    }
                }
            }
            if (!combined.toString().isBlank()) return combined.toString();
        }

        // Fallback: run from project.getBasePath() (single-repo or non-git4idea context)
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        String staged = runGitInDir(basePath, "diff", "--cached");
        if (staged != null && !staged.isBlank()) return staged;
        String head = runGitInDir(basePath, "diff", "HEAD");
        if (head != null && !head.isBlank()) return head;
        return null;
    }

    // ── System prompt ─────────────────────────────────────────────────────────

    @NotNull
    static String buildSystemPrompt(@NotNull Project project,
                                    @NotNull CommitMessageSettings.State s) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a commit message writer. ")
          .append("Generate a single, ready-to-use git commit message for the provided diff. ")
          .append("Return ONLY the commit message text — no explanations, no markdown fences, no surrounding quotes.\n\n");

        if (s.useConventionalCommits) {
            sb.append("REQUIRED: Follow the Conventional Commits spec: ")
              .append("<type>(<optional scope>): <subject>. ")
              .append("Allowed types: feat, fix, refactor, docs, test, chore, style, perf, ci, build, revert.\n");
        }
        if (s.maxSubjectLength > 0) {
            sb.append("Keep the subject line under ").append(s.maxSubjectLength).append(" characters.\n");
        }
        if (!s.prefixTemplate.isBlank()) {
            sb.append("Prepend every commit subject with: ").append(s.prefixTemplate).append("\n");
        }
        if (!s.language.isBlank()) {
            sb.append("Write in ").append(s.language).append(".\n");
        }
        if (s.autoDetectScope) {
            sb.append("Auto-detect the scope from the changed file paths (e.g. the module or package name).\n");
        }
        if (!s.extraInstructions.isBlank()) {
            sb.append("Additional instructions: ").append(s.extraInstructions).append("\n");
        }

        String mdRules = loadMarkdownRules(project, s.customRulesFilePath);
        if (mdRules != null && !mdRules.isBlank()) {
            String fileName = resolvedRuleFileName(project, s.customRulesFilePath);
            sb.append("\n--- Project commit rules (from ")
              .append(fileName != null ? fileName : "rules file")
              .append(") ---\n")
              .append(mdRules.trim()).append("\n");
        }
        return sb.toString().trim();
    }

    // ── Rules file ────────────────────────────────────────────────────────────

    @Nullable
    static String loadMarkdownRules(@NotNull Project project, @Nullable String configuredPath) {
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        List<String> candidates = (configuredPath != null && !configuredPath.isBlank())
            ? List.of(configuredPath) : DEFAULT_RULE_FILE_CANDIDATES;
        for (String candidate : candidates) {
            Path file = Path.of(basePath).resolve(candidate);
            if (Files.isRegularFile(file)) {
                try { return Files.readString(file, StandardCharsets.UTF_8); }
                catch (Exception ignored) {}
            }
        }
        return null;
    }

    @Nullable
    static String resolvedRuleFileName(@NotNull Project project, @Nullable String configuredPath) {
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        List<String> candidates = (configuredPath != null && !configuredPath.isBlank())
            ? List.of(configuredPath) : DEFAULT_RULE_FILE_CANDIDATES;
        for (String candidate : candidates) {
            if (Files.isRegularFile(Path.of(basePath).resolve(candidate))) return candidate;
        }
        return null;
    }

    // ── Git subprocess ────────────────────────────────────────────────────────

    /**
     * Runs {@code git <args>} in the given directory and returns stdout.
     * Returns {@code null} on error (non-zero exit, exception, or timeout).
     */
    @Nullable
    static String runGitInDir(@NotNull String dir, String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(Path.of(dir).toFile());
            pb.redirectErrorStream(false);
            CliPathResolver.injectAugmentedPath(pb);
            Process proc = pb.start();
            String out;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.lines().collect(Collectors.joining("\n"));
            }
            // drain stderr silently
            proc.getErrorStream().transferTo(java.io.OutputStream.nullOutputStream());
            boolean done = proc.waitFor(30, TimeUnit.SECONDS);
            if (!done) { proc.destroyForcibly(); return null; }
            return proc.exitValue() == 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Legacy helper used by CommitMessageGeneratorService.generateForDiff(). */
    @Nullable
    static String runGit(@NotNull Project project, String... args) {
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        return runGitInDir(basePath, args);
    }
}
