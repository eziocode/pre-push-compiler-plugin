package com.github.prepushchecker.commitgen;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Assembles the system prompt (rules) and user prompt (git diff) that are
 * passed to whichever AI provider is configured.
 *
 * <p>Rule priority (highest to lowest):
 * <ol>
 *   <li>Custom markdown rules file (project-level, committed to VCS)</li>
 *   <li>IDE settings (per-developer, stored in PasswordSafe / XML)</li>
 * </ol>
 *
 * <p>The markdown file is located by checking, in order:
 * <ol>
 *   <li>The path in {@code CommitMessageSettings.customRulesFilePath} (if non-blank)</li>
 *   <li>{@code .github/commit-instructions.md}</li>
 *   <li>{@code COMMIT_RULES.md}</li>
 * </ol>
 */
final class CommitMessagePromptBuilder {

    /** Characters above this limit are truncated to avoid API token overflows. */
    private static final int MAX_DIFF_CHARS = 16_000;

    /** Default candidate paths searched when no custom path is configured. */
    private static final List<String> DEFAULT_RULE_FILE_CANDIDATES = List.of(
        ".github/commit-instructions.md",
        "COMMIT_RULES.md"
    );

    private CommitMessagePromptBuilder() {}

    record Prompt(@NotNull String system, @NotNull String user) {}

    /**
     * Builds a {@link Prompt} by reading the staged diff (or HEAD diff as
     * fallback) and combining it with the current rule settings.
     */
    static @NotNull Prompt build(@NotNull Project project) throws Exception {
        String diff = runGit(project, "diff", "--cached");
        if (diff == null || diff.isBlank()) {
            diff = runGit(project, "diff", "HEAD");
        }
        if (diff == null || diff.isBlank()) {
            throw new IllegalStateException(
                "No staged or uncommitted changes found to generate a commit message from.");
        }
        if (diff.length() > MAX_DIFF_CHARS) {
            diff = diff.substring(0, MAX_DIFF_CHARS) + "\n\n[diff truncated — showing first "
                + MAX_DIFF_CHARS + " characters]";
        }
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        String systemPrompt = buildSystemPrompt(project, s);
        return new Prompt(systemPrompt, "Git diff:\n```\n" + diff + "\n```");
    }

    private static @NotNull String buildSystemPrompt(
            @NotNull Project project,
            @NotNull CommitMessageSettings.State s) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a commit message writer. ")
          .append("Generate a single, ready-to-use git commit message for the provided diff. ")
          .append("Return ONLY the commit message text — no explanations, no markdown fences, no surrounding quotes.\n\n");

        // ── IDE settings rules ────────────────────────────────────────────────
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

        // ── Project-level markdown rules file ─────────────────────────────────
        String mdRules = loadMarkdownRules(project, s.customRulesFilePath);
        if (mdRules != null && !mdRules.isBlank()) {
            sb.append("\n--- Project commit rules (from ").append(resolvedRuleFileName(project, s.customRulesFilePath)).append(") ---\n");
            sb.append(mdRules.trim()).append("\n");
        }

        return sb.toString().trim();
    }

    /**
     * Loads the markdown rules file, returning its content or {@code null} if
     * no matching file is found.
     */
    @Nullable
    static String loadMarkdownRules(@NotNull Project project, @Nullable String configuredPath) {
        String basePath = project.getBasePath();
        if (basePath == null) return null;

        List<String> candidates = (configuredPath != null && !configuredPath.isBlank())
            ? List.of(configuredPath)
            : DEFAULT_RULE_FILE_CANDIDATES;

        for (String candidate : candidates) {
            Path file = Path.of(basePath).resolve(candidate);
            if (Files.isRegularFile(file)) {
                try {
                    return Files.readString(file, StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * Returns the filename of the rules file that would be loaded (for display).
     */
    @Nullable
    static String resolvedRuleFileName(@NotNull Project project, @Nullable String configuredPath) {
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        List<String> candidates = (configuredPath != null && !configuredPath.isBlank())
            ? List.of(configuredPath)
            : DEFAULT_RULE_FILE_CANDIDATES;
        for (String candidate : candidates) {
            if (Files.isRegularFile(Path.of(basePath).resolve(candidate))) return candidate;
        }
        return null;
    }

    @Nullable
    static String runGit(@NotNull Project project, String... args) throws Exception {
        String basePath = project.getBasePath();
        if (basePath == null) throw new IllegalStateException("No project base path.");

        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(Path.of(basePath).toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        proc.waitFor();
        return output;
    }
}
