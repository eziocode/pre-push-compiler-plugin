package com.github.prepushchecker.commitgen;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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
        ".github/git-commit-instructions.md",
        "COMMIT_RULES.md"
    );

    private CommitMessagePromptBuilder() {}

    record Prompt(@NotNull String system, @NotNull String user) {}

    static @NotNull Prompt build(@NotNull Project project) throws Exception {
        return build(project, Collections.emptyList());
    }

    /**
     * Builds the prompt using only the {@code selectedChanges} (checked files in the
     * commit panel). When {@code selectedChanges} is empty the diff falls back to all
     * staged/unstaged changes, preserving the original "generate for everything" behaviour.
     */
    static @NotNull Prompt build(@NotNull Project project,
                                 @NotNull List<Change> selectedChanges) throws Exception {
        flushOpenDocuments();
        String diff = selectedChanges.isEmpty()
            ? collectDiff(project)
            : collectDiffForChanges(project, selectedChanges);
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
        String branch = resolveBranchName(project);

        StringBuilder user = new StringBuilder();
        if (branch != null && !branch.isBlank() && !branch.equals("HEAD")) {
            user.append("Current branch: ").append(branch).append("\n\n");
        }
        List<String> files = parseFileNamesFromDiff(diff);
        if (!files.isEmpty()) {
            user.append("Staged files:\n");
            files.forEach(f -> user.append("- ").append(f).append("\n"));
            user.append("\n");
        }
        user.append("Git diff:\n```\n").append(diff).append("\n```");

        return new Prompt(buildSystemPrompt(project, s, branch), user.toString());
    }

    // ── Diff collection ───────────────────────────────────────────────────────

    private static void flushOpenDocuments() {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            FileDocumentManager.getInstance().saveAllDocuments();
            return;
        }
        ApplicationManager.getApplication().invokeAndWait(
            () -> FileDocumentManager.getInstance().saveAllDocuments(),
            ModalityState.defaultModalityState()
        );
    }

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

    /**
     * Collects diff scoped to the given {@code selectedChanges} (the checked files in
     * the commit panel). For each git repository, maps each change to a repo-relative
     * path and runs {@code git diff} with those paths as operands. The staged →
     * unstaged → HEAD fallback is preserved, just scoped to the selected paths.
     */
    @Nullable
    private static String collectDiffForChanges(@NotNull Project project,
                                                @NotNull List<Change> selectedChanges) throws Exception {
        List<GitRepository> repos = GitRepositoryManager.getInstance(project).getRepositories();
        if (!repos.isEmpty()) {
            StringBuilder combined = new StringBuilder();
            for (GitRepository repo : repos) {
                appendDiffForChanges(combined, repo.getRoot().getPath(), selectedChanges);
            }
            if (!combined.toString().isBlank()) return combined.toString();
        }

        // Fallback: single-repo or non-git4idea context
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        StringBuilder sb = new StringBuilder();
        appendDiffForChanges(sb, basePath, selectedChanges);
        String result = sb.toString();
        return result.isBlank() ? null : result;
    }

    /** Appends the scoped diff for {@code changes} in the repo rooted at {@code root}. */
    private static void appendDiffForChanges(@NotNull StringBuilder combined,
                                             @NotNull String root,
                                             @NotNull List<Change> changes) {
        List<String> paths = extractRelativePaths(root, changes);
        if (paths.isEmpty()) return;

        // 1. staged
        String staged = runGitInDir(root, buildArgs(List.of("diff", "--cached", "--"), paths));
        if (staged != null && !staged.isBlank()) { combined.append(staged); return; }

        // 2. unstaged
        String unstaged = runGitInDir(root, buildArgs(List.of("diff", "--"), paths));
        if (unstaged != null && !unstaged.isBlank()) { combined.append(unstaged); return; }

        // 3. HEAD (handles initial-commit edge case)
        String head = runGitInDir(root, buildArgs(List.of("diff", "HEAD", "--"), paths));
        if (head != null && !head.isBlank()) combined.append(head);
    }

    /**
     * Returns paths of {@code changes} that belong to the repo at {@code root},
     * expressed relative to that root (as expected by git on the command line).
     */
    private static @NotNull List<String> extractRelativePaths(@NotNull String root,
                                                               @NotNull List<Change> changes) {
        Path rootPath = Path.of(root);
        return changes.stream()
            .map(change -> {
                ContentRevision rev = change.getAfterRevision() != null
                    ? change.getAfterRevision()
                    : change.getBeforeRevision();
                if (rev == null) return null;
                Path filePath = Path.of(rev.getFile().getPath());
                if (!filePath.startsWith(rootPath)) return null;
                return rootPath.relativize(filePath).toString();
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    /** Concatenates {@code prefix} and {@code suffix} into a single {@code String[]}. */
    private static String[] buildArgs(@NotNull List<String> prefix, @NotNull List<String> suffix) {
        List<String> all = new ArrayList<>(prefix);
        all.addAll(suffix);
        return all.toArray(new String[0]);
    }

    // ── System prompt ─────────────────────────────────────────────────────────

    @NotNull
    static String buildSystemPrompt(@NotNull Project project,
                                    @NotNull CommitMessageSettings.State s) {
        return buildSystemPrompt(project, s, resolveBranchName(project));
    }

    @NotNull
    static String buildSystemPrompt(@NotNull Project project,
                                    @NotNull CommitMessageSettings.State s,
                                    @Nullable String currentBranch) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a commit message writer. ")
          .append("Generate a single, ready-to-use git commit message for the provided diff. ")
          .append("Return ONLY the commit message text — no explanations, no markdown fences, no surrounding quotes.\n\n");

        // ── Project rules (loaded first so they take highest priority) ──────────
        String mdRules = loadMarkdownRules(project, s.customRulesFilePath);
        boolean hasCustomRules = mdRules != null && !mdRules.isBlank();

        if (hasCustomRules) {
            String fileName = resolvedRuleFileName(project, s.customRulesFilePath);
            sb.append("=== Project commit rules (from ")
              .append(fileName != null ? fileName : "rules file")
              .append(") — these rules OVERRIDE all generic guidance below ===\n")
              .append(mdRules.trim()).append("\n")
              .append("=== End of project commit rules ===\n\n");

            // Enforce the branch gate when the rules contain a Decision Tree.
            // The AI cannot run `git branch --show-current` itself, so we resolve
            // the current branch AND classify it (default vs non-default) here and
            // hand the model a concrete verdict. This removes the model's discretion,
            // which was the root cause of the prefix applying on some branches but
            // not others.
            if (currentBranch != null && !currentBranch.isBlank() && !currentBranch.equals("HEAD")) {
                String defaultBranch = resolveDefaultBranchName(project);
                boolean isDefault = defaultBranch != null
                    && currentBranch.equalsIgnoreCase(defaultBranch);

                sb.append("CRITICAL ENFORCEMENT — branch gate has ALREADY been evaluated for you:\n")
                  .append("- Current branch: '").append(currentBranch).append("'\n");
                if (defaultBranch != null) {
                    sb.append("- Repository default branch: '").append(defaultBranch).append("'\n");
                }
                if (isDefault) {
                    sb.append("- Verdict: this IS the DEFAULT branch. Apply the DEFAULT-branch rules ")
                      .append("(Steps 1–3). ISSUEFIX: / DOCS: selection applies here.\n");
                } else {
                    sb.append("- Verdict: this is a NON-DEFAULT branch. ISSUEFIX: is FORBIDDEN. ")
                      .append("Skip the default-branch steps and apply the NON-DEFAULT rules ")
                      .append("(METHOD_ENTRY: / TESTCASE: / FEATURE: / REFACTOR: per the decision tree).\n");
                }
                sb.append("You MUST honour this verdict. Then execute the remaining decision-tree ")
                  .append("steps in order, using the staged files listed in the user message, ")
                  .append("before choosing the final prefix. Do not re-decide the branch gate.\n\n");
            }
        }

        // ── Generic fallback instructions (ignored when rules file is present) ──
        if (s.useConventionalCommits && !hasCustomRules) {
            sb.append("REQUIRED: Follow the Conventional Commits spec: ")
              .append("<type>(<optional scope>): <subject>. ")
              .append("Allowed types: feat, fix, refactor, docs, test, chore, style, perf, ci, build, revert.\n");
        }
        if (s.maxSubjectLength > 0) {
            sb.append("Keep the subject line under ").append(s.maxSubjectLength).append(" characters.\n");
        }
        if (!s.prefixTemplate.isBlank()) {
            String prefix = s.prefixTemplate;
            if (prefix.contains("{branch}")) {
                String branch = (currentBranch != null && !currentBranch.isBlank()
                    && !currentBranch.equals("HEAD")) ? currentBranch : "";
                prefix = prefix.replace("{branch}", branch);
            }
            if (!prefix.isBlank()) {
                sb.append("Prepend every commit subject with: ").append(prefix).append("\n");
            }
        }
        if (!s.language.isBlank()) {
            sb.append("Write in ").append(s.language).append(".\n");
        }
        if (s.autoDetectScope && !hasCustomRules) {
            sb.append("Auto-detect the scope from the changed file paths (e.g. the module or package name).\n");
        }
        if (!s.extraInstructions.isBlank()) {
            sb.append("Additional instructions: ").append(s.extraInstructions).append("\n");
        }

        return sb.toString().trim();
    }

    // ── Branch resolution ─────────────────────────────────────────────────────

    /**
     * Returns the short name of the currently checked-out branch (e.g. {@code feature/PROJ-123}).
     * Returns {@code null} when the HEAD is detached, the project has no git repository,
     * or the branch name cannot be determined.
     */
    @Nullable
    static String resolveBranchName(@NotNull Project project) {
        // Primary: git4idea repository manager (handles multi-root projects)
        List<GitRepository> repos = GitRepositoryManager.getInstance(project).getRepositories();
        if (!repos.isEmpty()) {
            var branch = repos.get(0).getCurrentBranch();
            if (branch != null) return branch.getName();
        }
        // Fallback: git subprocess (non-git4idea contexts, e.g. test sandbox).
        // Use the exact command the rules file references so the resolved value
        // matches what a user running it manually would see.
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        String name = runGitInDir(basePath, "rev-parse", "--abbrev-ref", "HEAD");
        if (name == null || name.isBlank() || name.equals("HEAD")) {
            name = runGitInDir(basePath, "branch", "--show-current");
        }
        if (name == null || name.isBlank() || name.equals("HEAD")) return null;
        return name.trim();
    }

    /**
     * Resolves the repository's default branch name (e.g. {@code main}, {@code master},
     * or a team-specific default) so the branch gate can be evaluated deterministically
     * in code instead of being guessed by the AI. Resolution order:
     * <ol>
     *   <li>{@code git symbolic-ref --short refs/remotes/origin/HEAD} → strip {@code origin/}</li>
     *   <li>{@code git rev-parse --abbrev-ref origin/HEAD} → strip {@code origin/}</li>
     * </ol>
     * Returns {@code null} when it cannot be determined (in which case the AI is left to
     * apply the decision tree using the branch name alone, preserving prior behaviour).
     */
    @Nullable
    static String resolveDefaultBranchName(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            List<GitRepository> repos = GitRepositoryManager.getInstance(project).getRepositories();
            if (repos.isEmpty()) return null;
            basePath = repos.get(0).getRoot().getPath();
        }
        String head = runGitInDir(basePath, "symbolic-ref", "--short", "refs/remotes/origin/HEAD");
        String stripped = stripOriginPrefix(head);
        if (stripped != null) return stripped;

        head = runGitInDir(basePath, "rev-parse", "--abbrev-ref", "origin/HEAD");
        return stripOriginPrefix(head);
    }

    @Nullable
    private static String stripOriginPrefix(@Nullable String ref) {
        if (ref == null) return null;
        String trimmed = ref.trim();
        if (trimmed.isEmpty() || trimmed.equals("origin/HEAD")) return null;
        if (trimmed.startsWith("origin/")) trimmed = trimmed.substring("origin/".length());
        return trimmed.isBlank() ? null : trimmed;
    }

    /**
     * Parses the file paths touched by the diff from {@code diff --git a/X b/X} header lines.
     * Used to surface a concise "Staged files:" list in the user prompt so the AI can
     * evaluate decision-tree steps without scanning the full diff body.
     */
    @NotNull
    static List<String> parseFileNamesFromDiff(@NotNull String diff) {
        List<String> files = new ArrayList<>();
        for (String line : diff.split("\n")) {
            if (line.startsWith("diff --git ")) {
                // "diff --git a/path/to/file.java b/path/to/file.java"
                // Use the b/ path (after-side); handles renames gracefully.
                int bIdx = line.lastIndexOf(" b/");
                if (bIdx != -1) {
                    String path = line.substring(bIdx + 3).trim();
                    if (!path.isEmpty()) files.add(path);
                }
            }
        }
        return files;
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
                try { return stripFrontMatter(Files.readString(file, StandardCharsets.UTF_8)); }
                catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * Removes a leading YAML front-matter block (delimited by {@code ---} on its own
     * line at the very start of the file, e.g. {@code --- apply: always ---}) so it is
     * not fed to the AI as rule content. Only a front-matter block at the very top of
     * the file is stripped; {@code ---} horizontal rules elsewhere in the body are
     * left untouched.
     */
    @NotNull
    static String stripFrontMatter(@NotNull String content) {
        // Tolerate a leading BOM / blank lines before the opening delimiter.
        String working = content.startsWith("\uFEFF") ? content.substring(1) : content;
        String[] lines = working.split("\n", -1);
        int idx = 0;
        while (idx < lines.length && lines[idx].trim().isEmpty()) idx++;
        if (idx >= lines.length || !lines[idx].trim().equals("---")) {
            return content; // no front-matter block at the top
        }
        // Find the closing delimiter.
        for (int j = idx + 1; j < lines.length; j++) {
            if (lines[j].trim().equals("---")) {
                StringBuilder rest = new StringBuilder();
                for (int k = j + 1; k < lines.length; k++) {
                    rest.append(lines[k]);
                    if (k < lines.length - 1) rest.append('\n');
                }
                return rest.toString().stripLeading();
            }
        }
        // Unterminated front-matter — leave content unchanged to be safe.
        return content;
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
