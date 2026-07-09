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
              .append(") — these rules OVERRIDE all generic guidance below. "
                    + "Follow them EXACTLY, including any decision tree, branch gate, or file-type gate. ===\n")
              .append(mdRules.trim()).append("\n")
              .append("=== End of project commit rules ===\n\n");

            // Provide the concrete git FACTS the rules may reference (current branch,
            // repository default branch) so the model can execute the rules' own
            // decision tree without having to shell out to git — which it cannot do.
            //
            // IMPORTANT: nothing here is hardcoded to any specific project's branch
            // names or prefixes. We only surface neutral, factual context and let the
            // rules file drive every decision (branch gate, prefix selection, file-type
            // gates, etc.). This keeps the tool generic across any team/rule set, exactly
            // like the reference GitHub commit-message generator.
            String factsBlock = buildGitFactsBlock(project, s, currentBranch);
            if (!factsBlock.isBlank()) {
                sb.append(factsBlock).append("\n");
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
     * Builds a neutral, factual git-context block that the rules file can reference when
     * executing its own decision tree (branch gate, file-type gates, etc.). The AI cannot
     * run git itself, so we surface the facts it would otherwise have to shell out for:
     * the current branch, the repository default branch, and whether the two match.
     *
     * <p><b>No project-specific branch names or prefixes are hardcoded.</b> This method
     * only reports observed git state and defers every decision to the rules file — so the
     * tool works identically for any team's rule set. It mirrors how the reference GitHub
     * commit-message generator behaves: rules-driven, context-fed, no baked-in policy.</p>
     *
     * <p>The default branch is resolved from (1) the optional Settings override
     * {@code s.defaultBranchName} — useful when {@code origin/HEAD} is unset locally or
     * points elsewhere — then (2) git auto-detection. When neither is available we simply
     * omit the default-branch fact and let the rules file's own Step 0 (e.g.
     * {@code git branch --show-current}) instruction stand, using the current branch we
     * provide.</p>
     */
    @NotNull
    static String buildGitFactsBlock(@NotNull Project project,
                                     @NotNull CommitMessageSettings.State s,
                                     @Nullable String currentBranch) {
        boolean haveBranch = currentBranch != null && !currentBranch.isBlank()
            && !currentBranch.equals("HEAD");
        if (!haveBranch) return "";

        String defaultBranch = resolveConfiguredOrDetectedDefaultBranch(project, s);

        StringBuilder b = new StringBuilder();
        b.append("=== Git context (facts for evaluating the rules above — do NOT run git yourself) ===\n");
        b.append("- Current branch (`git branch --show-current`): ").append(currentBranch).append("\n");
        if (defaultBranch != null) {
            boolean isDefault = branchNamesMatch(currentBranch, defaultBranch);
            b.append("- Repository default branch: ").append(defaultBranch).append("\n");
            b.append("- Current branch is the default branch: ")
             .append(isDefault ? "YES" : "NO").append("\n");
        }
        b.append("Use these facts to execute the rules file's decision tree exactly as written "
               + "(branch gate first, then the remaining steps against the staged files listed "
               + "in the user message). Let the rules file decide the final prefix.\n");
        b.append("=== End of git context ===\n");
        return b.toString();
    }

    /**
     * Resolves the repository default branch from the optional Settings override first
     * (authoritative when set), then falls back to git auto-detection. Returns {@code null}
     * when neither is available.
     */
    @Nullable
    static String resolveConfiguredOrDetectedDefaultBranch(@NotNull Project project,
                                                           @NotNull CommitMessageSettings.State s) {
        String configured = s.defaultBranchName == null ? "" : s.defaultBranchName.trim();
        if (!configured.isBlank()) return configured;
        return resolveDefaultBranchName(project);
    }

    /**
     * Compares two branch names. Matching is case-insensitive and tolerant of a
     * {@code refs/heads/} / {@code refs/remotes/} / {@code origin/} prefix on either side,
     * since the current branch and the configured/detected default may be expressed
     * differently.
     */
    static boolean branchNamesMatch(@Nullable String a, @Nullable String b) {
        String na = normalizeBranchName(a);
        String nb = normalizeBranchName(b);
        return na != null && na.equalsIgnoreCase(nb);
    }

    @Nullable
    private static String normalizeBranchName(@Nullable String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty()) return null;
        if (n.startsWith("refs/heads/")) n = n.substring("refs/heads/".length());
        else if (n.startsWith("refs/remotes/")) n = n.substring("refs/remotes/".length());
        if (n.startsWith("origin/")) n = n.substring("origin/".length());
        return n.isBlank() ? null : n;
    }

    /**
     * Resolves the repository's default branch name (e.g. {@code main}, {@code master},
     * or a team-specific default) so the branch gate can be evaluated deterministically
     * in code instead of being guessed by the AI. Resolution order:
     * <ol>
     *   <li>{@code git symbolic-ref --short refs/remotes/origin/HEAD} → strip {@code origin/}</li>
     *   <li>{@code git rev-parse --abbrev-ref origin/HEAD} → strip {@code origin/}</li>
     *   <li>{@code git config --get init.defaultBranch}</li>
     * </ol>
     * Returns {@code null} when it cannot be determined (in which case the caller keeps
     * the classification {@code UNKNOWN} and lets the AI run Step 0 itself).
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
        stripped = stripOriginPrefix(head);
        if (stripped != null) return stripped;

        String configured = runGitInDir(basePath, "config", "--get", "init.defaultBranch");
        if (configured != null && !configured.isBlank()) {
            String t = configured.trim();
            if (!t.isEmpty()) return t;
        }
        return null;
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
