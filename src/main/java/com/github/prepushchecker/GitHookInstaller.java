package com.github.prepushchecker;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class GitHookInstaller {
    private static final Logger LOG = Logger.getInstance(GitHookInstaller.class);
    static final String HOOK_MARKER = "pre-push-compilation-checker-plugin";
    static final String HOOK_BLOCK_BEGIN = "# BEGIN " + HOOK_MARKER;
    static final String HOOK_BLOCK_END = "# END " + HOOK_MARKER;
    static final String MANAGED_HOOK_NAME = "pre-push-prepushchecker";
    static final String EXTERNAL_LOG_RELATIVE_PATH = ".idea/pre-push-checker/last-run.log";
    private static final Path GLOBAL_STATE_DIR = Path.of(System.getProperty("user.home"), ".prepush-checker");
    private static final Path GLOBAL_INSTALL_MARKER = GLOBAL_STATE_DIR.resolve("installed");
    private static final Path GLOBAL_TRACKED_REPOS = GLOBAL_STATE_DIR.resolve("repos.txt");
    private static final Object GLOBAL_REPO_LOCK = new Object();
    private static final Set<PosixFilePermission> HOOK_PERMISSIONS = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_EXECUTE
    );

    enum HookIssue {
        HOOKS_DIRECTORY_UNRESOLVED,
        MANAGED_HOOK_MISSING,
        MANAGED_HOOK_STALE,
        MANAGED_HOOK_NOT_EXECUTABLE,
        MAIN_HOOK_MISSING,
        MAIN_HOOK_SNIPPET_MISSING,
        MAIN_HOOK_SNIPPET_DUPLICATED,
        MAIN_HOOK_SNIPPET_STALE,
        EXCLUDE_BLOCK_MISSING,
        STALE_LEGACY_HOOK_PRESENT
    }

    static final class HookInspectionResult {
        private final String basePath;
        private final Path hooksDirectory;
        private final Path legacyHooksDirectory;
        private final String coreHooksPath;
        private final List<HookIssue> issues;

        private HookInspectionResult(
            String basePath,
            @Nullable Path hooksDirectory,
            @Nullable Path legacyHooksDirectory,
            @Nullable String coreHooksPath,
            List<HookIssue> issues
        ) {
            this.basePath = basePath;
            this.hooksDirectory = hooksDirectory;
            this.legacyHooksDirectory = legacyHooksDirectory;
            this.coreHooksPath = coreHooksPath;
            this.issues = List.copyOf(issues);
        }

        boolean isHealthy() {
            return issues.isEmpty();
        }

        @Nullable
        Path hooksDirectory() {
            return hooksDirectory;
        }

        @Nullable
        Path legacyHooksDirectory() {
            return legacyHooksDirectory;
        }

        boolean usesCoreHooksPath() {
            return coreHooksPath != null && !coreHooksPath.isBlank();
        }

        List<HookIssue> issues() {
            return issues;
        }

        String statusText() {
            if (hooksDirectory == null) {
                return "Git hooks could not be checked for this project.";
            }
            if (issues.isEmpty()) {
                String scope = usesCoreHooksPath()
                    ? " via core.hooksPath (" + coreHooksPath + ")"
                    : "";
                return "Git hooks are healthy" + scope + ".";
            }
            return "Git hooks need repair: " + issueSummary() + ".";
        }

        String issueSummary() {
            if (issues.isEmpty()) return "no issues";
            List<String> labels = new ArrayList<>(issues.size());
            for (HookIssue issue : issues) {
                labels.add(humanize(issue));
            }
            return String.join(", ", labels);
        }
    }

    static final class HookRepairResult {
        private final boolean success;
        private final HookInspectionResult before;
        private final HookInspectionResult after;
        private final String errorMessage;

        private HookRepairResult(
            boolean success,
            HookInspectionResult before,
            HookInspectionResult after,
            @Nullable String errorMessage
        ) {
            this.success = success;
            this.before = before;
            this.after = after;
            this.errorMessage = errorMessage;
        }

        boolean isSuccess() {
            return success;
        }

        HookInspectionResult before() {
            return before;
        }

        HookInspectionResult after() {
            return after;
        }

        String statusText() {
            if (!success) {
                return "Git hook repair failed: " + (errorMessage == null ? after.issueSummary() : errorMessage);
            }
            if (before.isHealthy()) {
                return after.statusText();
            }
            if (after.isHealthy()) {
                return "Git hooks repaired: " + before.issueSummary() + ".";
            }
            return "Git hooks still need attention: " + after.issueSummary() + ".";
        }
    }

    private static final class HookPaths {
        private final Path hooksDirectory;
        private final Path legacyHooksDirectory;
        private final String coreHooksPath;

        private HookPaths(
            @Nullable Path hooksDirectory,
            @Nullable Path legacyHooksDirectory,
            @Nullable String coreHooksPath
        ) {
            this.hooksDirectory = hooksDirectory;
            this.legacyHooksDirectory = legacyHooksDirectory;
            this.coreHooksPath = coreHooksPath;
        }

        boolean usesCoreHooksPath() {
            return coreHooksPath != null && !coreHooksPath.isBlank();
        }
    }

    /**
     * Project-startup entrypoint invoked by {@link PrePushProjectActivities.GitHookInstallerActivity}.
     * Kept package-public {@code static} so the Kotlin {@code ProjectActivity} bridge
     * (required for IntelliJ 2026.1+) can call it directly.
     */
    public static void runStartup(@NotNull Project project) {
        touchGlobalMarker();

        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return;
        }

        PrePushCheckerSettings.syncSettingsFile(project);
        HookRepairResult result = repair(basePath);
        if (result.isSuccess()) {
            LOG.info(result.statusText());
        } else {
            LOG.warn(result.statusText());
        }
    }

    static HookInspectionResult inspect(@NotNull Project project) {
        return inspect(project.getBasePath());
    }

    static HookInspectionResult inspect(@Nullable String basePath) {
        if (basePath == null || basePath.isBlank()) {
            return new HookInspectionResult(basePath, null, null, null,
                List.of(HookIssue.HOOKS_DIRECTORY_UNRESOLVED));
        }

        HookPaths paths = resolveHookPaths(basePath);
        List<HookIssue> issues = new ArrayList<>();
        if (paths.hooksDirectory == null) {
            issues.add(HookIssue.HOOKS_DIRECTORY_UNRESOLVED);
            return new HookInspectionResult(basePath, null, paths.legacyHooksDirectory,
                paths.coreHooksPath, issues);
        }

        Path mainHook = paths.hooksDirectory.resolve("pre-push");
        Path managedHook = paths.hooksDirectory.resolve(MANAGED_HOOK_NAME);
        String managedContent = buildManagedHookScript();

        if (!Files.exists(managedHook)) {
            issues.add(HookIssue.MANAGED_HOOK_MISSING);
        } else {
            try {
                if (!Files.readString(managedHook, StandardCharsets.UTF_8).equals(managedContent)) {
                    issues.add(HookIssue.MANAGED_HOOK_STALE);
                }
                if (!Files.isExecutable(managedHook)) {
                    issues.add(HookIssue.MANAGED_HOOK_NOT_EXECUTABLE);
                }
            } catch (IOException e) {
                issues.add(HookIssue.MANAGED_HOOK_STALE);
            }
        }

        if (!Files.exists(mainHook)) {
            issues.add(HookIssue.MAIN_HOOK_MISSING);
        } else {
            try {
                String content = Files.readString(mainHook, StandardCharsets.UTF_8);
                int markerCount = countMarkerOccurrences(content);
                if (markerCount == 0) {
                    issues.add(HookIssue.MAIN_HOOK_SNIPPET_MISSING);
                } else if (isWrapperHookContent(content)) {
                    // A wrapper with CRLF line endings or harmless trailing whitespace is still valid.
                } else {
                    if (markerCount > 1) {
                        issues.add(HookIssue.MAIN_HOOK_SNIPPET_DUPLICATED);
                    }
                    if (!containsCanonicalDelegatingSnippet(content)) {
                        issues.add(HookIssue.MAIN_HOOK_SNIPPET_STALE);
                    }
                }
            } catch (IOException e) {
                issues.add(HookIssue.MAIN_HOOK_SNIPPET_STALE);
            }
        }

        if (!isExcludeBlockCurrent(basePath, paths.hooksDirectory)) {
            issues.add(HookIssue.EXCLUDE_BLOCK_MISSING);
        }

        if (paths.usesCoreHooksPath()
            && paths.legacyHooksDirectory != null
            && !samePath(paths.hooksDirectory, paths.legacyHooksDirectory)
            && containsManagedArtifacts(paths.legacyHooksDirectory)) {
            issues.add(HookIssue.STALE_LEGACY_HOOK_PRESENT);
        }

        return new HookInspectionResult(basePath, paths.hooksDirectory, paths.legacyHooksDirectory,
            paths.coreHooksPath, issues);
    }

    static HookRepairResult repair(@NotNull Project project) {
        touchGlobalMarker();
        PrePushCheckerSettings.syncSettingsFile(project);
        return repair(project.getBasePath());
    }

    static HookRepairResult repair(@Nullable String basePath) {
        HookInspectionResult before = inspect(basePath);
        if (basePath == null || basePath.isBlank() || before.hooksDirectory() == null) {
            return new HookRepairResult(false, before, before, before.statusText());
        }

        Path hooksDirectory = before.hooksDirectory();

        try {
            Files.createDirectories(hooksDirectory);

            Path managedHook = hooksDirectory.resolve(MANAGED_HOOK_NAME);
            Files.writeString(managedHook, buildManagedHookScript(), StandardCharsets.UTF_8);
            makeExecutable(managedHook.toFile());

            Path mainHook = hooksDirectory.resolve("pre-push");
            if (Files.exists(mainHook)) {
                String content = Files.readString(mainHook, StandardCharsets.UTF_8);
                String repaired = buildRepairedMainHookContent(content);
                if (!content.equals(repaired)) {
                    Files.writeString(mainHook, repaired, StandardCharsets.UTF_8);
                }
            } else {
                Files.writeString(mainHook, buildWrapperHookScript(), StandardCharsets.UTF_8);
            }
            makeExecutable(mainHook.toFile());

            addToRepoLocalExclude(basePath, hooksDirectory);
            cleanupLegacyManagedHooks(before);
            trackRepo(basePath);

            HookInspectionResult after = inspect(basePath);
            return new HookRepairResult(after.isHealthy(), before, after, null);
        } catch (IOException ioException) {
            LOG.error("Failed to repair the pre-push hook.", ioException);
            HookInspectionResult after = inspect(basePath);
            return new HookRepairResult(false, before, after, ioException.getMessage());
        }
    }

    /**
     * Adds plugin-owned paths to {@code .git/info/exclude} so they never show up as untracked
     * in git clients, without touching the repo's tracked {@code .gitignore}.
     *
     * <p>Managed as a delimited block so we can upgrade the patterns on future plugin versions.
     */
    static void addToRepoLocalExclude(String basePath, @Nullable Path hooksDirectory) {
        Path gitDir = resolveGitCommonDir(basePath);
        if (gitDir == null) return;

        Path excludeFile = gitDir.resolve("info").resolve("exclude");
        String desiredBlock = buildExcludeBlock(basePath, hooksDirectory);

        try {
            Files.createDirectories(excludeFile.getParent());
            String existing = Files.exists(excludeFile)
                ? Files.readString(excludeFile, StandardCharsets.UTF_8)
                : "";
            String stripped = stripExistingBlock(existing);
            if (stripped.equals(existing) && existing.contains(desiredBlock)) {
                return; // already current
            }
            String sep = stripped.isEmpty() || stripped.endsWith("\n") ? "" : "\n";
            Files.writeString(excludeFile, stripped + sep + desiredBlock, StandardCharsets.UTF_8);
            LOG.info("Updated plugin entries in " + excludeFile);
        } catch (IOException ignored) {
            // Non-fatal: plugin-owned files will just show as untracked, which is cosmetic.
        }
    }

    /**
     * Reverse of {@link #runActivity} and {@link #addToRepoLocalExclude}. Invoked when the
     * plugin is being uninstalled or disabled so the repo is left in a clean state:
     * <ul>
     *   <li>Removes the managed hook script ({@value #MANAGED_HOOK_NAME}).</li>
     *   <li>Restores the main {@code pre-push} hook: deletes it if it is our plain wrapper,
     *       strips our delegating snippet if we only chained into an existing hook.</li>
     *   <li>Deletes the {@code .idea/pre-push-checker/} cache/log directory.</li>
     *   <li>Strips the plugin's delimited block from {@code .git/info/exclude}.</li>
     * </ul>
     * Safe to call repeatedly; missing files are ignored.
     */
    static void uninstall(String basePath) {
        if (basePath == null || basePath.isBlank()) return;

        HookPaths paths = resolveHookPaths(basePath);
        if (paths.hooksDirectory != null) {
            removeManagedHookArtifacts(paths.hooksDirectory);
        }
        if (paths.legacyHooksDirectory != null
            && (paths.hooksDirectory == null || !samePath(paths.hooksDirectory, paths.legacyHooksDirectory))) {
            removeManagedHookArtifacts(paths.legacyHooksDirectory);
        }

        // Remove plugin cache + log dir.
        Path cacheDir = Path.of(basePath, ".idea", "pre-push-checker");
        deleteRecursively(cacheDir);

        // Strip the .git/info/exclude block.
        Path gitDir = resolveGitCommonDir(basePath);
        if (gitDir != null) {
            Path excludeFile = gitDir.resolve("info").resolve("exclude");
            if (Files.exists(excludeFile)) {
                try {
                    String existing = Files.readString(excludeFile, StandardCharsets.UTF_8);
                    String stripped = stripExistingBlock(existing);
                    if (!stripped.equals(existing)) {
                        Files.writeString(excludeFile, stripped, StandardCharsets.UTF_8);
                    }
                } catch (IOException ignored) {}
            }
        }

        untrackRepo(basePath);
        LOG.info("Pre-Push Compilation Checker: cleaned hooks and cache under " + basePath);
    }

    /**
     * Best-effort cleanup for repos that were managed by this plugin but are not currently open.
     * Called on uninstall/disable so stale hook files do not survive until the next manual push.
     */
    static void uninstallTrackedRepos() {
        for (String repoPath : snapshotTrackedRepos()) {
            try {
                uninstall(repoPath);
            } catch (Throwable t) {
                LOG.warn("Cleanup failed for tracked repo " + repoPath, t);
            }
        }
    }

    /**
     * Removes only content explicitly owned by this plugin from the shared pre-push hook.
     *
     * <p>Current snippets use a BEGIN/END block, allowing other hook managers to freely add
     * content before or after our block. The legacy three-line snippet is also recognized so
     * upgrades and uninstalls clean hooks written by older plugin versions. An unterminated
     * current block is preserved: deleting everything after a damaged BEGIN marker could erase
     * another tool's hook logic.
     */
    static String stripManagedHookSections(String content) {
        StringBuilder out = new StringBuilder(content.length());
        String[] lines = normalizeLineEndings(content).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (line.trim().equals(HOOK_BLOCK_BEGIN)) {
                int end = findBlockEnd(lines, i + 1);
                if (end >= 0) {
                    i = end;
                    continue;
                }
                // Damaged/unclosed block: preserve it and everything after it verbatim.
                appendRemainingLines(out, lines, i);
                return out.toString();
            }

            if (line.trim().equals(HOOK_BLOCK_END)) {
                // Stray owned END marker is safe to remove without touching nearby content.
                continue;
            }

            if (line.trim().equals("# " + HOOK_MARKER)) {
                // Legacy format had no END marker. Remove only the exact known lines that
                // immediately followed its marker; never consume arbitrary neighboring logic.
                int next = i + 1;
                if (next < lines.length && isOwnedScriptDirectoryLine(lines[next])) next++;
                if (next < lines.length && isOwnedManagedHookInvocation(lines[next])) next++;
                i = next - 1;
                continue;
            }

            out.append(line);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    private static int findBlockEnd(String[] lines, int fromIndex) {
        for (int i = fromIndex; i < lines.length; i++) {
            if (lines[i].trim().equals(HOOK_BLOCK_END)) return i;
        }
        return -1;
    }

    private static void appendRemainingLines(StringBuilder out, String[] lines, int fromIndex) {
        for (int i = fromIndex; i < lines.length; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) out.append('\n');
        }
    }

    private static boolean isOwnedScriptDirectoryLine(String line) {
        return line.trim().equals("SCRIPT_DIR=\"$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\"");
    }

    private static boolean isOwnedManagedHookInvocation(String line) {
        String trimmed = line.trim();
        return trimmed.equals("\"$SCRIPT_DIR/" + MANAGED_HOOK_NAME + "\" \"$@\"")
            || trimmed.equals("\"$SCRIPT_DIR/" + MANAGED_HOOK_NAME + "\" \"$@\" || exit $?");
    }

    private static String buildRepairedMainHookContent(String existingContent) {
        if (isWrapperHookContent(existingContent)) {
            return buildWrapperHookScript();
        }

        String cleaned = stripManagedHookSections(existingContent)
            .replaceAll("\\n{3,}", "\n\n");
        if (!hasSubstantiveHookContent(cleaned)) {
            return buildWrapperHookScript();
        }
        return stripTrailingLineBreaks(cleaned) + buildDelegatingSnippet();
    }

    private static void cleanupLegacyManagedHooks(HookInspectionResult before) {
        if (!before.usesCoreHooksPath()
            || before.hooksDirectory() == null
            || before.legacyHooksDirectory() == null
            || samePath(before.hooksDirectory(), before.legacyHooksDirectory())) {
            return;
        }
        removeManagedHookArtifacts(before.legacyHooksDirectory());
    }

    private static void removeManagedHookArtifacts(Path hooksDirectory) {
        Path managedHook = hooksDirectory.resolve(MANAGED_HOOK_NAME);
        try {
            Files.deleteIfExists(managedHook);
        } catch (IOException e) {
            LOG.warn("Could not delete managed hook " + managedHook + ": " + e.getMessage());
        }

        Path mainHook = hooksDirectory.resolve("pre-push");
        if (!Files.exists(mainHook)) return;
        try {
            String content = Files.readString(mainHook, StandardCharsets.UTF_8);
            if (isWrapperHookContent(content)) {
                Files.deleteIfExists(mainHook);
                return;
            }
            if (!content.contains(HOOK_MARKER)) return;

            String cleaned = stripManagedHookSections(content)
                .replaceAll("\\n{3,}", "\n\n");
            if (!hasSubstantiveHookContent(cleaned)) {
                Files.deleteIfExists(mainHook);
            } else {
                Files.writeString(mainHook, stripTrailingLineBreaks(cleaned) + '\n', StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOG.warn("Could not clean managed hook content from " + mainHook + ": " + e.getMessage());
        }
    }

    private static boolean containsManagedArtifacts(Path hooksDirectory) {
        Path managedHook = hooksDirectory.resolve(MANAGED_HOOK_NAME);
        if (Files.exists(managedHook)) return true;

        Path mainHook = hooksDirectory.resolve("pre-push");
        if (!Files.exists(mainHook)) return false;
        try {
            return Files.readString(mainHook, StandardCharsets.UTF_8).contains(HOOK_MARKER);
        } catch (IOException e) {
            return true;
        }
    }

    static int countMarkerOccurrences(String content) {
        int count = 0;
        for (String line : normalizeLineEndings(content).split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.equals(HOOK_BLOCK_BEGIN) || trimmed.equals("# " + HOOK_MARKER)) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasSubstantiveHookContent(String content) {
        for (String line : normalizeLineEndings(content).split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#!")) continue;
            return true;
        }
        return false;
    }

    private static boolean containsCanonicalDelegatingSnippet(String content) {
        return normalizeLineEndings(content).contains(normalizeLineEndings(buildDelegatingSnippet()));
    }

    private static boolean isWrapperHookContent(String content) {
        return normalizeHookContent(content).equals(normalizeHookContent(buildWrapperHookScript()));
    }

    private static String normalizeHookContent(String content) {
        return normalizeLineEndings(content).stripTrailing();
    }

    private static String normalizeLineEndings(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String stripTrailingLineBreaks(String content) {
        int end = content.length();
        while (end > 0) {
            char c = content.charAt(end - 1);
            if (c != '\n' && c != '\r') break;
            end--;
        }
        return content.substring(0, end);
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) return;
        try {
            try (var stream = Files.walk(path)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        } catch (IOException ignored) {}
    }

    private static String stripExistingBlock(String content) {
        if (content.isEmpty()) return content;
        String begin = "# BEGIN " + HOOK_MARKER;
        String end = "# END " + HOOK_MARKER;
        int b = content.indexOf(begin);
        if (b < 0) {
            // Legacy unversioned single-line marker -> remove stray lines referencing it.
            StringBuilder out = new StringBuilder(content.length());
            for (String line : content.split("\n", -1)) {
                if (line.contains(HOOK_MARKER)) continue;
                if (line.equals("/.idea/pre-push-checker/")) continue;
                out.append(line).append('\n');
            }
            // Trim the trailing newline we just added when the source didn't have one.
            if (!content.endsWith("\n") && out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
                out.setLength(out.length() - 1);
            }
            return out.toString();
        }
        int e = content.indexOf(end, b);
        if (e < 0) return content.substring(0, b);
        int stopLine = content.indexOf('\n', e);
        int stop = stopLine < 0 ? content.length() : stopLine + 1;
        // Also consume one blank separator line left over from a previous append.
        while (stop < content.length() && content.charAt(stop) == '\n') stop++;
        return content.substring(0, b) + content.substring(stop);
    }

    private static String buildExcludeBlock(String basePath, @Nullable Path hooksDirectory) {
        StringBuilder block = new StringBuilder();
        block.append("# BEGIN ").append(HOOK_MARKER).append('\n');
        block.append("/.idea/pre-push-checker/\n");

        // If the resolved hooks dir is inside the working tree (e.g. core.hooksPath=.githooks),
        // also exclude the hook files themselves so they don't appear as unversioned.
        String hooksRel = relativeToWorkTree(basePath, hooksDirectory);
        if (hooksRel != null && !hooksRel.isEmpty()) {
            block.append('/').append(hooksRel).append("/pre-push\n");
            block.append('/').append(hooksRel).append('/').append(MANAGED_HOOK_NAME).append('\n');
        }
        block.append("# END ").append(HOOK_MARKER).append('\n');
        return block.toString();
    }

    private static boolean isExcludeBlockCurrent(String basePath, @Nullable Path hooksDirectory) {
        Path gitDir = resolveGitCommonDir(basePath);
        if (gitDir == null) return true;
        Path excludeFile = gitDir.resolve("info").resolve("exclude");
        if (!Files.exists(excludeFile)) return false;
        try {
            return Files.readString(excludeFile, StandardCharsets.UTF_8)
                .contains(buildExcludeBlock(basePath, hooksDirectory));
        } catch (IOException e) {
            return false;
        }
    }

    @Nullable
    private static String relativeToWorkTree(String basePath, @Nullable Path hooksDirectory) {
        if (hooksDirectory == null) return null;
        try {
            Path base = resolveWorkTreeRoot(basePath);
            Path hooks = hooksDirectory.toAbsolutePath().normalize();
            if (!hooks.startsWith(base)) return null;
            // Never exclude the default /.git/hooks/ — it isn't tracked anyway.
            Path relative = base.relativize(hooks);
            String rel = relative.toString().replace('\\', '/');
            if (rel.isEmpty() || rel.startsWith(".git/") || rel.equals(".git")) return null;
            return rel;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Locates the hooks directory the way git itself does. This handles:
     * <ul>
     *   <li>Plain repos ({@code <root>/.git/hooks})</li>
     *   <li>Worktrees and submodules where {@code .git} is a <em>file</em> containing {@code gitdir: ...}</li>
     *   <li>Repos that override {@code core.hooksPath}</li>
     * </ul>
     * Falls back to {@code <basePath>/.git/hooks} only when git is unavailable.
     */
    static Path resolveHooksDirectory(String basePath) {
        return resolveHookPaths(basePath).hooksDirectory;
    }

    private static HookPaths resolveHookPaths(String basePath) {
        String coreHooksPath = queryGitOutput(basePath, "config", "--path", "--get", "core.hooksPath");
        if (coreHooksPath != null && coreHooksPath.isBlank()) {
            coreHooksPath = null;
        }

        Path legacyHooksDirectory = resolveDefaultHooksDirectory(basePath);
        Path hooksDirectory = null;
        if (coreHooksPath != null) {
            hooksDirectory = resolveConfiguredHooksPath(basePath, coreHooksPath);
        }
        if (hooksDirectory == null) {
            Path viaGit = queryGit(basePath, "rev-parse", "--git-path", "hooks");
            hooksDirectory = viaGit != null ? viaGit : legacyHooksDirectory;
        }
        return new HookPaths(hooksDirectory, legacyHooksDirectory, coreHooksPath);
    }

    @Nullable
    private static Path resolveDefaultHooksDirectory(String basePath) {
        Path gitCommonDir = queryGit(basePath, "rev-parse", "--git-common-dir");
        if (gitCommonDir != null) {
            return gitCommonDir.resolve("hooks").normalize();
        }

        Path dotGit = Path.of(basePath, ".git");
        if (Files.isDirectory(dotGit)) {
            return dotGit.resolve("hooks");
        }

        // ".git" is a file pointing to the real gitdir (worktree / submodule).
        if (Files.isRegularFile(dotGit)) {
            try {
                for (String line : Files.readAllLines(dotGit, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("gitdir:")) {
                        String target = trimmed.substring("gitdir:".length()).trim();
                        Path resolved = Path.of(target);
                        if (!resolved.isAbsolute()) {
                            resolved = Path.of(basePath).resolve(target).normalize();
                        }
                        return resolved.resolve("hooks");
                    }
                }
            } catch (IOException ignored) {
                // fall through
            }
        }
        return null;
    }

    @Nullable
    private static Path resolveConfiguredHooksPath(String basePath, String configuredHooksPath) {
        String value = configuredHooksPath.trim();
        if (value.isEmpty()) return null;
        Path configured = expandUserHome(value);
        if (!configured.isAbsolute()) {
            configured = resolveWorkTreeRoot(basePath).resolve(configured);
        }
        return configured.toAbsolutePath().normalize();
    }

    private static Path expandUserHome(String path) {
        if (path.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (path.startsWith("~/") || path.startsWith("~" + File.separator)) {
            return Path.of(System.getProperty("user.home")).resolve(path.substring(2));
        }
        return Path.of(path);
    }

    private static Path resolveWorkTreeRoot(String basePath) {
        Path workTreeRoot = queryGit(basePath, "rev-parse", "--show-toplevel");
        return workTreeRoot != null
            ? workTreeRoot.toAbsolutePath().normalize()
            : Path.of(basePath).toAbsolutePath().normalize();
    }

    @Nullable
    private static Path resolveGitCommonDir(String basePath) {
        Path gitDir = queryGit(basePath, "rev-parse", "--git-common-dir");
        if (gitDir != null) return gitDir;
        Path fallback = Path.of(basePath, ".git");
        return Files.isDirectory(fallback) ? fallback : null;
    }

    @Nullable
    private static Path queryGit(String basePath, String... args) {
        String output = queryGitOutput(basePath, args);
        if (output == null) return null;
        Path candidate = Path.of(output);
        if (!candidate.isAbsolute()) {
            candidate = Path.of(basePath).resolve(output).normalize();
        }
        return candidate;
    }

    @Nullable
    private static String queryGitOutput(String basePath, String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            Process process = new ProcessBuilder(cmd)
                .directory(new File(basePath))
                .redirectErrorStream(false)
                .start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
                output = sb.toString().trim();
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0 || output.isEmpty()) {
                return null;
            }
            return output;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private static boolean samePath(Path first, Path second) {
        try {
            return Files.isSameFile(first, second);
        } catch (IOException e) {
            return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
        }
    }

    private static String humanize(HookIssue issue) {
        return switch (issue) {
            case HOOKS_DIRECTORY_UNRESOLVED -> "hooks directory unresolved";
            case MANAGED_HOOK_MISSING -> "managed hook missing";
            case MANAGED_HOOK_STALE -> "managed hook outdated or edited";
            case MANAGED_HOOK_NOT_EXECUTABLE -> "managed hook not executable";
            case MAIN_HOOK_MISSING -> "pre-push hook missing";
            case MAIN_HOOK_SNIPPET_MISSING -> "pre-push hook does not call the checker";
            case MAIN_HOOK_SNIPPET_DUPLICATED -> "duplicate checker hook snippets";
            case MAIN_HOOK_SNIPPET_STALE -> "checker hook snippet needs refresh";
            case EXCLUDE_BLOCK_MISSING -> "repo-local exclude block missing";
            case STALE_LEGACY_HOOK_PRESENT -> "stale managed hook in legacy .git/hooks";
        };
    }

    static BuildTool detectBuildTool(String basePath) {
        if (new File(basePath, "gradlew").exists()) {
            return BuildTool.GRADLE_WRAPPER;
        }
        if (new File(basePath, "build.gradle.kts").exists() || new File(basePath, "build.gradle").exists()) {
            return BuildTool.GRADLE;
        }
        if (new File(basePath, "mvnw").exists()) {
            return BuildTool.MAVEN_WRAPPER;
        }
        if (new File(basePath, "pom.xml").exists()) {
            return BuildTool.MAVEN;
        }
        return BuildTool.UNKNOWN;
    }

    static String buildManagedHookScript() {
        return String.join("\n",
            "#!/usr/bin/env sh",
            "# " + HOOK_MARKER,
            "# Installed by the Pre-Push Compilation Checker IntelliJ plugin.",
            "# Runs a compile check before allowing pushes from terminals, Sublime Merge,",
            "# SourceTree, GitHub Desktop, etc. The output is also written to a log file so",
            "# IntelliJ can surface the same errors inside the Compilation Checker tool window.",
            "",
            "set -u",
            "",
            "NULL_SHA=0000000000000000000000000000000000000000",
            "",
            "REPO_ROOT=\"$(git rev-parse --show-toplevel 2>/dev/null || pwd)\"",
            "cd \"$REPO_ROOT\" || exit 1",
            "",
            "# ── Self-cleanup: if the plugin was uninstalled, remove ourselves and exit ─────",
            "GLOBAL_MARKER=\"$HOME/.prepush-checker/installed\"",
            "if [ ! -f \"$GLOBAL_MARKER\" ]; then",
            "  # Only self-remove when IntelliJ is NOT running — avoids premature cleanup",
            "  # during a brief window between IDE restart and marker refresh.",
            "  _ide_running=0",
            "  if command -v pgrep >/dev/null 2>&1; then",
            "    _uid=\"$(id -u 2>/dev/null || printf '')\"",
            "    if [ -n \"$_uid\" ]; then",
            "      pgrep -u \"$_uid\" -f 'IntelliJ IDEA|idea\\.app|idea64|com\\.jetbrains\\.idea' >/dev/null 2>&1 && _ide_running=1",
            "    else",
            "      pgrep -f 'IntelliJ IDEA|idea\\.app|idea64|com\\.jetbrains\\.idea' >/dev/null 2>&1 && _ide_running=1",
            "    fi",
            "  fi",
            "  if [ \"$_ide_running\" -eq 0 ]; then",
            "    # Resolve hooks dir the same way the plugin does.",
            "    _hooks_dir=\"$(git rev-parse --git-path hooks 2>/dev/null)\"",
            "    [ -z \"$_hooks_dir\" ] && _hooks_dir=\"$(git rev-parse --git-dir 2>/dev/null)/hooks\"",
            "    if [ -n \"$_hooks_dir\" ] && [ -d \"$_hooks_dir\" ]; then",
            "      rm -f \"$_hooks_dir/" + MANAGED_HOOK_NAME + "\" 2>/dev/null",
            "      # Remove only our delimited block (plus the legacy three-line snippet).",
            "      if [ -f \"$_hooks_dir/pre-push\" ]; then",
            "        if grep -qF '" + HOOK_MARKER + "' \"$_hooks_dir/pre-push\" 2>/dev/null; then",
            "          _other_content=\"$(awk '",
            "            $0 == \"" + HOOK_BLOCK_BEGIN + "\" { in_owned=1; block=$0; next }",
            "            in_owned { block=block ORS $0; if ($0 == \"" + HOOK_BLOCK_END + "\") { in_owned=0; block=\"\" }; next }",
            "            $0 == \"# " + HOOK_MARKER + "\" { legacy=1; next }",
            "            legacy && $0 ~ /^SCRIPT_DIR=.*CDPATH=/ { next }",
            "            legacy && index($0, \"" + MANAGED_HOOK_NAME + "\") > 0 { legacy=0; next }",
            "            { legacy=0; print }",
            "            END { if (in_owned) print block }",
            "          ' \"$_hooks_dir/pre-push\")\"",
            "          _other_logic=\"$(printf '%s\\n' \"$_other_content\" | sed '/^[[:space:]]*$/d; /^#!/d')\"",
            "          if [ -z \"$_other_logic\" ]; then",
            "            rm -f \"$_hooks_dir/pre-push\" 2>/dev/null",
            "          else",
            "            printf '%s\\n' \"$_other_content\" > \"$_hooks_dir/pre-push\" 2>/dev/null",
            "          fi",
            "        fi",
            "      fi",
            "    fi",
            "    rm -rf \"$REPO_ROOT/.idea/pre-push-checker\" 2>/dev/null",
            "    # Strip plugin block from .git/info/exclude.",
            "    _git_dir=\"$(git rev-parse --git-common-dir 2>/dev/null)\"",
            "    [ -z \"$_git_dir\" ] && _git_dir=\"$(git rev-parse --git-dir 2>/dev/null)\"",
            "    _exclude=\"${_git_dir}/info/exclude\"",
            "    if [ -f \"$_exclude\" ]; then",
            "      sed -i.bak '/# BEGIN " + HOOK_MARKER + "/,/# END " + HOOK_MARKER + "/d' \"$_exclude\" 2>/dev/null",
            "      rm -f \"${_exclude}.bak\" 2>/dev/null",
            "    fi",
            "    printf '[pre-push] Plugin uninstalled. Cleaned up hooks and cache. Push allowed.\\n' >&2",
            "    exit 0",
            "  fi",
            "fi",
            "",
            "# ── Force-push bypass: skip check if the user requested it from the IDE ────────",
            "BYPASS_TOKEN=\"$REPO_ROOT/.idea/pre-push-checker/bypass-token\"",
            "if [ -f \"$BYPASS_TOKEN\" ]; then",
            "  _token_ms=\"$(head -n1 \"$BYPASS_TOKEN\" 2>/dev/null | tr -d '[:space:]')\"",
            "  _now_s=\"$(date +%s 2>/dev/null || printf '0')\"",
            "  # Token stores millis; convert to seconds for comparison.",
            "  _token_s=$(( ${_token_ms:-0} / 1000 ))",
            "  _age=$(( _now_s - _token_s ))",
            "  if [ \"$_age\" -ge 0 ] && [ \"$_age\" -lt 3600 ] 2>/dev/null; then",
            "    rm -f \"$BYPASS_TOKEN\" 2>/dev/null",
            "    printf '[pre-push] Force-push bypass active. Skipping compilation check.\\n' >&2",
            "    exit 0",
            "  else",
            "    rm -f \"$BYPASS_TOKEN\" 2>/dev/null",
            "  fi",
            "fi",
            "",
            "LOG_DIR=\"$REPO_ROOT/.idea/pre-push-checker\"",
            "LOG_FILE=\"$LOG_DIR/last-run.log\"",
            "mkdir -p \"$LOG_DIR\" 2>/dev/null || true",
            ": > \"$LOG_FILE\" 2>/dev/null || true",
            "",
            "log() { printf '%s\\n' \"$*\" | tee -a \"$LOG_FILE\" >&2; }",
            "",
            "# GUI git clients on macOS (IntelliJ, Sublime Merge, SourceTree, GitHub Desktop)",
            "# launch hooks with a stripped PATH that does not include the user's shell",
            "# additions (Homebrew, SDKMAN, jenv, asdf, etc.). Reconstruct a sensible PATH so",
            "# 'mvn' and 'gradle' resolve the same way they do in an interactive terminal.",
            "bootstrap_build_tool_path() {",
            "  _need_mvn=0",
            "  _need_gradle=0",
            "  if [ -f \"./pom.xml\" ] && [ ! -x \"./mvnw\" ]; then _need_mvn=1; fi",
            "  if { [ -f \"./build.gradle\" ] || [ -f \"./build.gradle.kts\" ]; } && [ ! -x \"./gradlew\" ]; then _need_gradle=1; fi",
            "  [ \"$_need_mvn\" -eq 0 ] && [ \"$_need_gradle\" -eq 0 ] && return 0",
            "  if [ \"$_need_mvn\" -eq 1 ] && command -v mvn >/dev/null 2>&1; then _need_mvn=0; fi",
            "  if [ \"$_need_gradle\" -eq 1 ] && command -v gradle >/dev/null 2>&1; then _need_gradle=0; fi",
            "  [ \"$_need_mvn\" -eq 0 ] && [ \"$_need_gradle\" -eq 0 ] && return 0",
            "",
            "  # Try sourcing the user's shell rc files in a non-interactive subshell to",
            "  # capture any PATH exports they declare there (common SDKMAN / jenv / asdf setup).",
            "  for _rc in \"$HOME/.zshenv\" \"$HOME/.zprofile\" \"$HOME/.zshrc\" \\",
            "             \"$HOME/.bash_profile\" \"$HOME/.bashrc\" \"$HOME/.profile\"; do",
            "    [ -r \"$_rc\" ] || continue",
            "    _extra_path=$(env -i HOME=\"$HOME\" PATH=\"$PATH\" sh -c \". \\\"$_rc\\\" >/dev/null 2>&1; printf %s \\\"\\$PATH\\\"\" 2>/dev/null || true)",
            "    case \":$PATH:\" in *\":$_extra_path:\"*) :;; *)",
            "      [ -n \"$_extra_path\" ] && PATH=\"$_extra_path:$PATH\" ;;",
            "    esac",
            "  done",
            "",
            "  # Append known install locations as a safety net.",
            "  for _candidate in \\",
            "      \"${MAVEN_HOME:-}/bin\" \"${M2_HOME:-}/bin\" \"${MVN_HOME:-}/bin\" \\",
            "      \"${GRADLE_HOME:-}/bin\" \\",
            "      \"$HOME/.sdkman/candidates/maven/current/bin\" \\",
            "      \"$HOME/.sdkman/candidates/gradle/current/bin\" \\",
            "      \"$HOME/.jenv/shims\" \"$HOME/.asdf/shims\" \\",
            "      /opt/homebrew/bin /usr/local/bin /opt/local/bin /usr/bin; do",
            "    [ -n \"$_candidate\" ] && [ -d \"$_candidate\" ] || continue",
            "    case \":$PATH:\" in *\":$_candidate:\"*) :;; *) PATH=\"$PATH:$_candidate\" ;; esac",
            "  done",
            "  export PATH",
            "",
            "  if [ \"$_need_mvn\" -eq 1 ] && ! command -v mvn >/dev/null 2>&1; then",
            "    log \"[pre-push] WARNING: 'mvn' not found on PATH. Set MAVEN_HOME or add a Maven Wrapper (./mvnw) to this repo.\"",
            "  fi",
            "  if [ \"$_need_gradle\" -eq 1 ] && ! command -v gradle >/dev/null 2>&1; then",
            "    log \"[pre-push] WARNING: 'gradle' not found on PATH. Set GRADLE_HOME or add a Gradle Wrapper (./gradlew) to this repo.\"",
            "  fi",
            "}",
            "",
            "bootstrap_build_tool_path",
            "",
            "setup_maven_java_home() {",
            "  if [ -n \"${PRE_PUSH_CHECKER_JAVA_HOME:-}\" ] && [ -x \"${PRE_PUSH_CHECKER_JAVA_HOME}/bin/java\" ]; then",
            "    export JAVA_HOME=\"$PRE_PUSH_CHECKER_JAVA_HOME\"",
            "    export PATH=\"$JAVA_HOME/bin:$PATH\"",
            "    return 0",
            "  fi",
            "",
            "  [ -r \"$SETTINGS_FILE\" ] || return 0",
            "  _preferred_java_home=\"$(sed -n 's/^preferredJavaHome=//p' \"$SETTINGS_FILE\" | head -n1)\"",
            "  if [ -n \"$_preferred_java_home\" ] && [ -x \"$_preferred_java_home/bin/java\" ]; then",
            "    export JAVA_HOME=\"$_preferred_java_home\"",
            "    export PATH=\"$JAVA_HOME/bin:$PATH\"",
            "  fi",
            "}",
            "",
            "# Maven / Gradle repeat every error and tack on a 'Help 1 / re-run with -X' footer.",
            "# We keep the full noise in the log file (for IntelliJ), but show a compact slice here.",
            "filter_terminal_output() {",
            "  awk '",
            "    /^\\[ERROR\\] Failed to execute goal/ { stop=1 }",
            "    /^\\[ERROR\\] BUILD FAILURE/ { stop=1 }",
            "    stop { next }",
            "    /^\\[ERROR\\] -> \\[Help/ { next }",
            "    /^\\[ERROR\\] \\[Help/ { next }",
            "    /^\\[ERROR\\] To see the full stack/ { next }",
            "    /^\\[ERROR\\] Re-run Maven/ { next }",
            "    /^\\[ERROR\\] For more information/ { next }",
            "    /^\\[ERROR\\]\\s*$/ { next }",
            "    /^\\[INFO\\] BUILD FAILURE/ { next }",
            "    /^\\[INFO\\] -+$/ { next }",
            "    /^\\[INFO\\] Total time/ { next }",
            "    /^\\[INFO\\] Finished at/ { next }",
            "    /^> Task :.*FAILED$/ { print; next }",
            "    /^FAILURE: Build failed/ { stop=1; next }",
            "    { print }",
            "  '",
            "}",
            "",
            "collect_changed_files() {",
            "  while IFS=' ' read -r local_ref local_sha remote_ref remote_sha; do",
            "    [ -z \"${local_sha:-}\" ] && continue",
            "    if [ \"$local_sha\" = \"$NULL_SHA\" ]; then",
            "      continue",
            "    fi",
            "",
            "    if [ \"${remote_sha:-$NULL_SHA}\" = \"$NULL_SHA\" ]; then",
            "      git rev-list \"$local_sha\" --not --remotes 2>/dev/null | while IFS= read -r commit_sha; do",
            "        [ -z \"$commit_sha\" ] && continue",
            "        git diff-tree --no-commit-id --name-only -r \"$commit_sha\" 2>/dev/null",
            "      done",
            "    else",
            "      git diff --name-only --diff-filter=ACMR \"$remote_sha\" \"$local_sha\" 2>/dev/null",
            "    fi",
            "  done",
            "}",
            "",
            "extract_error_records() {",
            "  _out_file=\"$1\"",
            "  _records_file=\"$2\"",
            "  awk '",
            "    function emit(path, line, kind, msg) {",
            "      gsub(/\\t/, \" \", msg)",
            "      print path \"\\t\" line \"\\t\" kind \"\\t\" msg",
            "    }",
            "    {",
            "      raw=$0",
            "      sub(/\\r$/, \"\", raw)",
            "      line=raw",
            "      sub(/^[[:space:]]+/, \"\", line)",
            "      if (line ~ /^\\[ERROR\\][[:space:]]+/) line=substr(line, 9)",
            "      if (match(line, /^(.+\\.(java|kt|kts|groovy|scala|xml|gradle|properties)):\\[([0-9]+),([0-9]+)\\][[:space:]]*(.+)$/, m)) {",
            "        emit(m[1], m[3], \"maven\", m[5])",
            "        next",
            "      }",
            "      if (match(line, /^(.+\\.(java|kt|kts|groovy|scala|xml|gradle|properties)):([0-9]+)(:[0-9]+)?:[[:space:]]*(error|warning):?[[:space:]]*(.+)$/, j)) {",
            "        emit(j[1], j[3], \"javac\", j[6])",
            "        next",
            "      }",
            "      if (match(line, /^e:[[:space:]]+(file:\\/\\/)?([^:]+\\.(kt|kts)):([0-9]+):([0-9]+)[[:space:]]+(.+)$/, k)) {",
            "        emit(k[2], k[4], \"kotlin\", k[6])",
            "      }",
            "    }",
            "  ' \"$_out_file\" > \"$_records_file\" 2>/dev/null || :",
            "}",
            "",
            "looks_like_stale_or_parallel_failure() {",
            "  _out_file=\"$1\"",
            "  _records_file=\"$2\"",
            "  if grep -Eiq 'class file for .* not found|cannot find annotation method|unknown enum constant|bad class file:|NoSuchFileException:|cannot access[[:space:]]' \"$_out_file\"; then",
            "    return 0",
            "  fi",
            "  if grep -Eiq \"';' expected|illegal start|not a statement|reached end of file|unclosed |identifier expected|incompatible types\" \"$_out_file\"; then",
            "    return 1",
            "  fi",
            "  _classpath_failures=\"$(grep -Eic 'package[[:space:]]+[^[:space:]]+[[:space:]]+does not exist|cannot find symbol' \"$_out_file\" 2>/dev/null || printf '0')\"",
            "  _affected_files=\"$(awk -F '	' 'NF && !seen[$1]++ { count++ } END { print count + 0 }' \"$_records_file\" 2>/dev/null || printf '0')\"",
            "  [ \"$_classpath_failures\" -ge 4 ] 2>/dev/null && [ \"$_affected_files\" -ge 3 ] 2>/dev/null",
            "}",
            "",
            "LAST_BUILD_TOOL=none",
            "run_build_tool_command() {",
            "  _gradle_tasks=\"$1\"",
            "  _maven_goal=\"$2\"",
            "  _out_file=\"$3\"",
            "  _mode=\"${4:-primary}\"",
            "  if [ -n \"${PRE_PUSH_CHECKER_COMMAND:-}\" ]; then",
            "    LAST_BUILD_TOOL=custom",
            "    sh -c \"$PRE_PUSH_CHECKER_COMMAND\" >\"$_out_file\" 2>&1",
            "    return $?",
            "  elif [ -x \"./gradlew\" ]; then",
            "    LAST_BUILD_TOOL=gradle",
            "    ./gradlew --console=plain --quiet --parallel --build-cache $_gradle_tasks >\"$_out_file\" 2>&1",
            "    return $?",
            "  elif [ -f \"./build.gradle\" ] || [ -f \"./build.gradle.kts\" ]; then",
            "    LAST_BUILD_TOOL=gradle",
            "    gradle --console=plain --quiet --parallel --build-cache $_gradle_tasks >\"$_out_file\" 2>&1",
            "    return $?",
            "  elif [ -x \"./mvnw\" ]; then",
            "    LAST_BUILD_TOOL=maven",
            "    if [ \"$_mode\" = \"recovery\" ]; then",
            "      ./mvnw -q -Dmaven.javadoc.skip=true clean \"$_maven_goal\" >\"$_out_file\" 2>&1",
            "    else",
            "      ./mvnw -q -T1C -Dmaven.javadoc.skip=true \"$_maven_goal\" >\"$_out_file\" 2>&1",
            "    fi",
            "    return $?",
            "  elif [ -f \"./pom.xml\" ]; then",
            "    LAST_BUILD_TOOL=maven",
            "    if command -v mvnd >/dev/null 2>&1; then",
            "      if [ \"$_mode\" = \"recovery\" ]; then",
            "        mvnd -q -T1 -Dmaven.javadoc.skip=true clean \"$_maven_goal\" >\"$_out_file\" 2>&1",
            "      else",
            "        mvnd -q -T1C -Dmaven.javadoc.skip=true \"$_maven_goal\" >\"$_out_file\" 2>&1",
            "      fi",
            "      return $?",
            "    fi",
            "    if [ \"$_mode\" = \"recovery\" ]; then",
            "      mvn -q -Dmaven.javadoc.skip=true clean \"$_maven_goal\" >\"$_out_file\" 2>&1",
            "    else",
            "      mvn -q -T1C -Dmaven.javadoc.skip=true \"$_maven_goal\" >\"$_out_file\" 2>&1",
            "    fi",
            "    return $?",
            "  fi",
            "  return 127",
            "}",
            "",
            "run_compilation() {",
            "  setup_maven_java_home",
            "  TMP_OUT=\"$(mktemp 2>/dev/null || printf '%s/prepush-%s.out' \"${TMPDIR:-/tmp}\" $$)\"",
            "  TMP_REC=\"$(mktemp 2>/dev/null || printf '%s/prepush-%s.records' \"${TMPDIR:-/tmp}\" $$)\"",
            "  rc=0",
            "",
            "  # Pick the narrowest task set for the change surface.",
            "  GRADLE_TASKS=\"\"",
            "  MAVEN_GOAL=\"test-compile\"",
            "  if [ \"$MAIN_CHANGED\" -eq 1 ] && [ \"$TEST_CHANGED\" -eq 1 ]; then",
            "    GRADLE_TASKS=\"classes testClasses\"",
            "    MAVEN_GOAL=\"test-compile\"",
            "  elif [ \"$TEST_CHANGED\" -eq 1 ]; then",
            "    GRADLE_TASKS=\"testClasses\"",
            "    MAVEN_GOAL=\"test-compile\"",
            "  else",
            "    GRADLE_TASKS=\"classes\"",
            "    MAVEN_GOAL=\"compile\"",
            "  fi",
            "",
            "  run_build_tool_command \"$GRADLE_TASKS\" \"$MAVEN_GOAL\" \"$TMP_OUT\" primary",
            "  rc=$?",
            "  extract_error_records \"$TMP_OUT\" \"$TMP_REC\"",
            "",
            "  if [ \"$rc\" -eq 127 ]; then",
            "    log \"[pre-push] No supported build tool found. Skipping compilation check.\"",
            "    rm -f \"$TMP_OUT\" \"$TMP_REC\" 2>/dev/null",
            "    return 0",
            "  fi",
            "",
            "  if [ $rc -ne 0 ] && [ \"$LAST_BUILD_TOOL\" = \"maven\" ] \\",
            "      && looks_like_stale_or_parallel_failure \"$TMP_OUT\" \"$TMP_REC\"; then",
            "    log \"[pre-push] Parallel Maven compile reported likely stale/reactor state; retrying once sequentially from clean output.\"",
            "    : > \"$TMP_OUT\" 2>/dev/null || true",
            "    run_build_tool_command \"$GRADLE_TASKS\" \"$MAVEN_GOAL\" \"$TMP_OUT\" recovery",
            "    rc=$?",
            "    extract_error_records \"$TMP_OUT\" \"$TMP_REC\"",
            "  fi",
            "",
            "  # Full output -> log file (IntelliJ parses this).",
            "  cat \"$TMP_OUT\" >> \"$LOG_FILE\" 2>/dev/null || true",
            "",
            "  # Only show a concise slice in the terminal when the check fails.",
            "  if [ $rc -ne 0 ]; then",
            "    filter_terminal_output < \"$TMP_OUT\" >&2",
            "  fi",
            "",
            "  rm -f \"$TMP_OUT\" \"$TMP_REC\" 2>/dev/null",
            "  return $rc",
            "}",
            "",
            "HOOK_INPUT=\"$(cat || true)\"",
            "CHANGED_FILES=\"$(printf '%s\\n' \"$HOOK_INPUT\" | collect_changed_files | sed '/^$/d' | sort -u)\"",
            "",
            "if [ -z \"$CHANGED_FILES\" ]; then",
            "  if git rev-parse --verify --quiet '@{upstream}' >/dev/null 2>&1; then",
            "    CHANGED_FILES=\"$(git diff --name-only --diff-filter=ACMR '@{upstream}...HEAD' 2>/dev/null | sed '/^$/d' | sort -u)\"",
            "  else",
            "    CHANGED_FILES=\"$(git diff-tree --no-commit-id --name-only -r HEAD 2>/dev/null | sed '/^$/d' | sort -u)\"",
            "  fi",
            "fi",
            "",
            "if [ -z \"$CHANGED_FILES\" ]; then",
            "  log \"[pre-push] No outgoing files detected. Skipping compilation check.\"",
            "  exit 0",
            "fi",
            "",
            "CHANGED_SET_FILE=\"$(mktemp 2>/dev/null || printf '%s/prepush-%s.changed' \"${TMPDIR:-/tmp}\" $$)\"",
            "printf '%s\\n' \"$CHANGED_FILES\" > \"$CHANGED_SET_FILE\" 2>/dev/null || true",
            "trap 'rm -f \"$CHANGED_SET_FILE\" 2>/dev/null || true' EXIT INT TERM HUP",
            "",
            "if ! printf '%s\\n' \"$CHANGED_FILES\" | grep -Eq '(^|/)(pom\\.xml|build\\.gradle(\\.kts)?|settings\\.gradle(\\.kts)?|gradle\\.properties|gradlew|mvnw)$|(\\.java|\\.kt|\\.groovy|\\.scala)$'; then",
            "  log \"[pre-push] No source or build changes detected. Skipping compilation check.\"",
            "  exit 0",
            "fi",
            "",
            "# Classify what kind of source files changed so we can skip unnecessary compile work.",
            "# MAIN_CHANGED=1 when any non-test .java/.kt/.groovy/.scala was touched, or a build script.",
            "# TEST_CHANGED=1 when any test source (under src/test/ or *Test.(java|kt)) was touched.",
            "MAIN_CHANGED=0",
            "TEST_CHANGED=0",
            "while IFS= read -r f; do",
            "  [ -z \"$f\" ] && continue",
            "  case \"$f\" in",
            "    */src/test/*|*/src/testFixtures/*|*/src/integrationTest/*|*Test.java|*Test.kt|*Tests.java|*Tests.kt|*IT.java|*IT.kt)",
            "      TEST_CHANGED=1 ;;",
            "    *.java|*.kt|*.groovy|*.scala|*pom.xml|*build.gradle|*build.gradle.kts|*settings.gradle|*settings.gradle.kts|*gradle.properties)",
            "      MAIN_CHANGED=1 ;;",
            "  esac",
            "done <<CHANGED_EOF",
            "$CHANGED_FILES",
            "CHANGED_EOF",
            "",
            "# Build-script edits invalidate everything -> always compile both.",
            "if printf '%s\\n' \"$CHANGED_FILES\" | grep -Eq '(^|/)(pom\\.xml|build\\.gradle(\\.kts)?|settings\\.gradle(\\.kts)?|gradle\\.properties)$'; then",
            "  MAIN_CHANGED=1",
            "  TEST_CHANGED=1",
            "fi",
            "",
            "log \"[pre-push] Relevant source/build changes detected. Running compilation check...\"",
            "",
            "# Fast path: ask the running IntelliJ to reuse its incremental JPS compiler.",
            "# Falls through to the build tool if IntelliJ is not running or the request fails.",
            "PORT_FILE=\"$REPO_ROOT/.idea/pre-push-checker/server.port\"",
            "SETTINGS_FILE=\"$REPO_ROOT/.idea/pre-push-checker/settings\"",
            "FALLBACK_PROTOCOL_VERSION=2",
            "FALLBACK_SUCCESS_FILE=\"$REPO_ROOT/.idea/pre-push-checker/last-fallback-success-v2\"",
            "FALLBACK_RUN_RESULT_FILE=\"$REPO_ROOT/.idea/pre-push-checker/last-fallback-run-v2\"",
            "LEGACY_FALLBACK_RESULT_FILE=\"$REPO_ROOT/.idea/pre-push-checker/last-fallback-result\"",
            "FALLBACK_LOCK_DIR=\"$REPO_ROOT/.idea/pre-push-checker/fallback.lock\"",
            "FALLBACK_LOCK_HELD=0",
            "FALLBACK_WAIT_SECONDS=300",
            "FALLBACK_SUCCESS_TTL_SECONDS=60",
            "FALLBACK_WAIT_OWNER=\"\"",
            "SNAPSHOT_TEMP_BASE=\"${TMPDIR:-/tmp}\"",
            "SNAPSHOT_TEMP_BASE=\"${SNAPSHOT_TEMP_BASE%/}\"",
            "SNAPSHOT_TEMP_ROOT=\"\"",
            "SNAPSHOT_WORKTREE=\"\"",
            "IDE_BOOTSTRAP_RETRIES=24",
            "IDE_BOOTSTRAP_SLEEP=0.5",
            "IDE_FALLBACK_REASON=\"\"",
            "rm -f \"$LEGACY_FALLBACK_RESULT_FILE\" 2>/dev/null || true",
            "try_ide_compile() {",
            "  [ -r \"$PORT_FILE\" ] || return 2",
            "  IDE_PORT=\"$(head -n1 \"$PORT_FILE\" 2>/dev/null | tr -d '[:space:]')\"",
            "  [ -n \"$IDE_PORT\" ] || return 2",
            "  # /dev/tcp is a bash feature; fall back cleanly if unavailable.",
            "  command -v bash >/dev/null 2>&1 || return 2",
            "  # Translate the change list (repo-relative) to absolute paths so the IDE can resolve them.",
            "  ABS_FILES=\"$(printf '%s\\n' \"$CHANGED_FILES\" | awk -v root=\"$REPO_ROOT\" 'NF{print root\"/\"$0}')\"",
            "  IDE_RESP=\"$(REQ_FILES=\"$ABS_FILES\" IDE_PORT=\"$IDE_PORT\" bash -c '",
            "    exec 3<>/dev/tcp/127.0.0.1/\"$IDE_PORT\" || exit 2",
            "    printf \"CHECK\\n%s\\n\\n\" \"$REQ_FILES\" >&3",
            "    cat <&3",
            "  ' 2>/dev/null)\" || return 2",
            "  [ -n \"$IDE_RESP\" ] || return 2",
            "  IDE_FIRST=\"$(printf '%s' \"$IDE_RESP\" | head -n1)\"",
            "  case \"$IDE_FIRST\" in",
            "    OK)",
            "      log \"[pre-push] IntelliJ incremental compile: OK\"",
            "      printf '%s\\n' \"$IDE_RESP\" >> \"$LOG_FILE\" 2>/dev/null || true",
            "      return 0 ;;",
            "    ERRORS*)",
            "      log \"[pre-push] IntelliJ incremental compile reported errors:\"",
            "      printf '%s\\n' \"$IDE_RESP\" >> \"$LOG_FILE\" 2>/dev/null || true",
            "      printf '%s\\n' \"$IDE_RESP\" | sed -n '2,$p' | grep -v '^END$' >&2",
            "      return 1 ;;",
            "    TIMEOUT*)",
            "      log \"[pre-push] IntelliJ validation timed out. Push aborted without starting a duplicate fallback build.\"",
            "      printf '%s\\n' \"$IDE_RESP\" >> \"$LOG_FILE\" 2>/dev/null || true",
            "      return 3 ;;",
            "    FAIL*)",
            "      printf '%s\\n' \"$IDE_RESP\" >> \"$LOG_FILE\" 2>/dev/null || true",
            "      return 2 ;;",
            "    ERR*|*)",
            "      # IDE could not run the check (disposed, unreachable, etc.) - fall back.",
            "      return 2 ;;",
            "  esac",
            "}",
            "",
            "is_intellij_running() {",
            "  command -v pgrep >/dev/null 2>&1 || return 1",
            "  _uid=\"$(id -u 2>/dev/null || printf '')\"",
            "  if [ -n \"$_uid\" ]; then",
            "    pgrep -u \"$_uid\" -f 'IntelliJ IDEA|idea\\.app|idea64|com\\.jetbrains\\.idea' >/dev/null 2>&1",
            "  else",
            "    pgrep -f 'IntelliJ IDEA|idea\\.app|idea64|com\\.jetbrains\\.idea' >/dev/null 2>&1",
            "  fi",
            "}",
            "",
            "try_ide_compile_with_retry() {",
            "  IDE_FALLBACK_REASON=\"ide-check-unavailable\"",
            "  try_ide_compile",
            "  IDE_RETRY_RC=$?",
            "  [ \"$IDE_RETRY_RC\" -ne 2 ] && return \"$IDE_RETRY_RC\"",
            "",
            "  if [ ! -r \"$PORT_FILE\" ] && ! is_intellij_running; then",
            "    IDE_FALLBACK_REASON=\"port-file-missing-and-intellij-not-running\"",
            "    return 2",
            "  fi",
            "",
            "  log \"[pre-push] IntelliJ appears to be running; waiting briefly for IDE compiler service...\"",
            "  _attempt=0",
            "  while [ \"$_attempt\" -lt \"$IDE_BOOTSTRAP_RETRIES\" ]; do",
            "    sleep \"$IDE_BOOTSTRAP_SLEEP\"",
            "    try_ide_compile",
            "    IDE_RETRY_RC=$?",
            "    [ \"$IDE_RETRY_RC\" -ne 2 ] && return \"$IDE_RETRY_RC\"",
            "    _attempt=$(($_attempt + 1))",
            "  done",
            "  if [ ! -r \"$PORT_FILE\" ]; then",
            "    IDE_FALLBACK_REASON=\"port-file-not-written-after-wait\"",
            "  else",
            "    IDE_FALLBACK_REASON=\"ide-server-unreachable-or-busy-after-wait\"",
            "  fi",
            "  return 2",
            "}",
            "",
            "try_ide_compile_with_retry",
            "IDE_RC=$?",
            "if [ \"$IDE_RC\" -eq 0 ]; then",
            "  printf '[pre-push-checker] exit=0\\n' >> \"$LOG_FILE\" 2>/dev/null || true",
            "  log \"[pre-push] Compilation passed (via IntelliJ incremental). Proceeding with push.\"",
            "  exit 0",
            "elif [ \"$IDE_RC\" -eq 1 ]; then",
            "  printf '[pre-push-checker] exit=1\\n' >> \"$LOG_FILE\" 2>/dev/null || true",
            "  log \"[pre-push] Compilation failed (via IntelliJ incremental). Push aborted.\"",
            "  log \"[pre-push] Open IntelliJ -> View -> Tool Windows -> Compilation Checker for navigable errors.\"",
            "  exit 1",
            "elif [ \"$IDE_RC\" -eq 3 ]; then",
            "  printf '[pre-push-checker] exit=1\\n' >> \"$LOG_FILE\" 2>/dev/null || true",
            "  exit 1",
            "fi",
            "",
            "log \"[pre-push] IntelliJ incremental compile unavailable (${IDE_FALLBACK_REASON:-unknown}); using build-tool fallback.\"",
            "",
            "# If build-tool fallback is disabled, skip rather than run Maven/Gradle without IntelliJ.",
            "# Prevents false-positive errors from annotation processors (e.g. Lombok) that Maven",
            "# cannot resolve without the same incremental setup IntelliJ uses.",
            "if [ -f \"$SETTINGS_FILE\" ] && grep -q \"^disableBuildToolFallback=true\" \"$SETTINGS_FILE\" 2>/dev/null; then",
            "  log \"[pre-push] IntelliJ not running and build-tool fallback is disabled. Skipping compilation check.\"",
            "  printf '[pre-push-checker] exit=0\\n' >> \"$LOG_FILE\" 2>/dev/null || true",
            "  exit 0",
            "fi",
            "",
            "FALLBACK_MODE=\"main\"",
            "if [ \"$MAIN_CHANGED\" -eq 1 ] && [ \"$TEST_CHANGED\" -eq 1 ]; then",
            "  FALLBACK_MODE=\"all\"",
            "elif [ \"$TEST_CHANGED\" -eq 1 ]; then",
            "  FALLBACK_MODE=\"test\"",
            "fi",
            "HEAD_SHA=\"$(git rev-parse --verify HEAD 2>/dev/null || printf '')\"",
            "CACHED_FALLBACK_RC=",
            "FALLBACK_TOOL_ID=unknown",
            "if [ -n \"${PRE_PUSH_CHECKER_COMMAND:-}\" ]; then",
            "  FALLBACK_TOOL_ID=\"custom:$(printf '%s' \"$PRE_PUSH_CHECKER_COMMAND\" | cksum | awk '{print $1 \"-\" $2}')\"",
            "elif git cat-file -e \"$HEAD_SHA:gradlew\" 2>/dev/null; then FALLBACK_TOOL_ID=gradle-wrapper",
            "elif git cat-file -e \"$HEAD_SHA:build.gradle\" 2>/dev/null || git cat-file -e \"$HEAD_SHA:build.gradle.kts\" 2>/dev/null; then FALLBACK_TOOL_ID=\"gradle:$(command -v gradle 2>/dev/null || printf missing)\"",
            "elif git cat-file -e \"$HEAD_SHA:mvnw\" 2>/dev/null; then FALLBACK_TOOL_ID=maven-wrapper",
            "elif git cat-file -e \"$HEAD_SHA:pom.xml\" 2>/dev/null && command -v mvnd >/dev/null 2>&1; then FALLBACK_TOOL_ID=\"mvnd:$(command -v mvnd)\"",
            "elif git cat-file -e \"$HEAD_SHA:pom.xml\" 2>/dev/null; then FALLBACK_TOOL_ID=\"maven:$(command -v mvn 2>/dev/null || printf missing)\"",
            "fi",
            "FALLBACK_CHANGED_ID=\"$(printf '%s\\n' \"$CHANGED_FILES\" | cksum | awk '{print $1 \"-\" $2}')\"",
            "FALLBACK_JAVA_ID=\"${PRE_PUSH_CHECKER_JAVA_HOME:-${JAVA_HOME:-}}\"",
            "if [ -r \"$SETTINGS_FILE\" ]; then",
            "  _settings_java_home=\"$(sed -n 's/^preferredJavaHome=//p' \"$SETTINGS_FILE\" | head -n1)\"",
            "  [ -n \"$_settings_java_home\" ] && FALLBACK_JAVA_ID=\"$_settings_java_home\"",
            "fi",
            "FALLBACK_FINGERPRINT=\"$(printf '%s\\n' \"$FALLBACK_PROTOCOL_VERSION|$HEAD_SHA|$FALLBACK_MODE|$FALLBACK_CHANGED_ID|$FALLBACK_TOOL_ID|$FALLBACK_JAVA_ID\" | cksum | awk '{print $1 \"-\" $2}')\"",
            "",
            "read_success_result() {",
            "  [ -n \"$FALLBACK_FINGERPRINT\" ] && [ -r \"$FALLBACK_SUCCESS_FILE\" ] || return 1",
            "  IFS='|' read -r _version _fingerprint _completed_at _result_rc < \"$FALLBACK_SUCCESS_FILE\" || return 1",
            "  [ \"$_version\" = \"$FALLBACK_PROTOCOL_VERSION\" ] || return 1",
            "  [ \"$_fingerprint\" = \"$FALLBACK_FINGERPRINT\" ] || return 1",
            "  [ \"$_result_rc\" = \"0\" ] || return 1",
            "  _now=\"$(date +%s 2>/dev/null || printf '0')\"",
            "  _age=$(( _now - ${_completed_at:-0} ))",
            "  [ \"$_age\" -ge 0 ] 2>/dev/null && [ \"$_age\" -lt \"$FALLBACK_SUCCESS_TTL_SECONDS\" ] 2>/dev/null || return 1",
            "  CACHED_FALLBACK_RC=0",
            "  return 0",
            "}",
            "",
            "read_run_result() {",
            "  _expected_owner=\"$1\"",
            "  [ -n \"$_expected_owner\" ] && [ -r \"$FALLBACK_RUN_RESULT_FILE\" ] || return 1",
            "  IFS='|' read -r _version _owner _fingerprint _result_rc _completed_at < \"$FALLBACK_RUN_RESULT_FILE\" || return 1",
            "  [ \"$_version\" = \"$FALLBACK_PROTOCOL_VERSION\" ] || return 1",
            "  [ \"$_owner\" = \"$_expected_owner\" ] || return 1",
            "  [ \"$_fingerprint\" = \"$FALLBACK_FINGERPRINT\" ] || return 1",
            "  case \"$_result_rc\" in",
            "    0|1) CACHED_FALLBACK_RC=\"$_result_rc\"; return 0 ;;",
            "  esac",
            "  return 1",
            "}",
            "",
            "publish_run_result() {",
            "  _result_tmp=\"$FALLBACK_RUN_RESULT_FILE.$$\"",
            "  _completed_at=\"$(date +%s 2>/dev/null || printf '0')\"",
            "  printf '%s|%s|%s|%s|%s\\n' \"$FALLBACK_PROTOCOL_VERSION\" \"$FALLBACK_LOCK_OWNER\" \"$FALLBACK_FINGERPRINT\" \"$1\" \"$_completed_at\" > \"$_result_tmp\" 2>/dev/null || return 1",
            "  mv -f \"$_result_tmp\" \"$FALLBACK_RUN_RESULT_FILE\" 2>/dev/null || { rm -f \"$_result_tmp\" 2>/dev/null || true; return 1; }",
            "}",
            "",
            "publish_success_result() {",
            "  _result_tmp=\"$FALLBACK_SUCCESS_FILE.$$\"",
            "  _completed_at=\"$(date +%s 2>/dev/null || printf '0')\"",
            "  printf '%s|%s|%s|0\\n' \"$FALLBACK_PROTOCOL_VERSION\" \"$FALLBACK_FINGERPRINT\" \"$_completed_at\" > \"$_result_tmp\" 2>/dev/null || return 1",
            "  mv -f \"$_result_tmp\" \"$FALLBACK_SUCCESS_FILE\" 2>/dev/null || { rm -f \"$_result_tmp\" 2>/dev/null || true; return 1; }",
            "}",
            "",
            "create_snapshot_worktree() {",
            "  SNAPSHOT_TEMP_ROOT=\"$(mktemp -d \"$SNAPSHOT_TEMP_BASE/prepushchecker-snapshot.XXXXXX\" 2>/dev/null || printf '')\"",
            "  [ -n \"$SNAPSHOT_TEMP_ROOT\" ] || return 1",
            "  SNAPSHOT_WORKTREE=\"$SNAPSHOT_TEMP_ROOT/worktree\"",
            "  git -C \"$REPO_ROOT\" worktree add --detach \"$SNAPSHOT_WORKTREE\" \"$HEAD_SHA\" >> \"$LOG_FILE\" 2>&1 || return 1",
            "  return 0",
            "}",
            "",
            "cleanup_snapshot_worktree() {",
            "  if [ -n \"$SNAPSHOT_WORKTREE\" ]; then",
            "    git -C \"$REPO_ROOT\" worktree remove --force \"$SNAPSHOT_WORKTREE\" >> \"$LOG_FILE\" 2>&1 || true",
            "    SNAPSHOT_WORKTREE=\"\"",
            "  fi",
            "  case \"$SNAPSHOT_TEMP_ROOT\" in",
            "    \"$SNAPSHOT_TEMP_BASE\"/prepushchecker-snapshot.*) rm -rf \"$SNAPSHOT_TEMP_ROOT\" 2>/dev/null || true ;;",
            "  esac",
            "  SNAPSHOT_TEMP_ROOT=\"\"",
            "  git -C \"$REPO_ROOT\" worktree prune >/dev/null 2>&1 || true",
            "}",
            "",
            "release_fallback_lock() {",
            "  [ \"$FALLBACK_LOCK_HELD\" -eq 1 ] || return 0",
            "  _owner=\"$(cat \"$FALLBACK_LOCK_DIR/owner\" 2>/dev/null || printf '')\"",
            "  if [ \"$_owner\" = \"$FALLBACK_LOCK_OWNER\" ]; then",
            "    rm -f \"$FALLBACK_LOCK_DIR/owner\" 2>/dev/null || true",
            "    rmdir \"$FALLBACK_LOCK_DIR\" 2>/dev/null || true",
            "  fi",
            "  FALLBACK_LOCK_HELD=0",
            "}",
            "",
            "acquire_fallback_lock() {",
            "  _started=\"$(date +%s 2>/dev/null || printf '0')\"",
            "  while :; do",
            "    if [ -n \"$FALLBACK_WAIT_OWNER\" ] && read_run_result \"$FALLBACK_WAIT_OWNER\"; then return 1; fi",
            "    if mkdir \"$FALLBACK_LOCK_DIR\" 2>/dev/null; then",
            "      _now=\"$(date +%s 2>/dev/null || printf '0')\"",
            "      FALLBACK_LOCK_OWNER=\"$$ $_now\"",
            "      printf '%s\\n' \"$FALLBACK_LOCK_OWNER\" > \"$FALLBACK_LOCK_DIR/owner\" 2>/dev/null || { rmdir \"$FALLBACK_LOCK_DIR\" 2>/dev/null || true; return 2; }",
            "      FALLBACK_LOCK_HELD=1",
            "      if read_success_result; then release_fallback_lock; return 1; fi",
            "      return 0",
            "    fi",
            "    _now=\"$(date +%s 2>/dev/null || printf '0')\"",
            "    _owner_line=\"$(cat \"$FALLBACK_LOCK_DIR/owner\" 2>/dev/null || printf '')\"",
            "    if [ -z \"$FALLBACK_WAIT_OWNER\" ] && [ -n \"$_owner_line\" ]; then FALLBACK_WAIT_OWNER=\"$_owner_line\"; fi",
            "    if [ -n \"$FALLBACK_WAIT_OWNER\" ] && read_run_result \"$FALLBACK_WAIT_OWNER\"; then return 1; fi",
            "    _owner_pid=\"${_owner_line%% *}\"",
            "    _owner_started=\"${_owner_line#* }\"",
            "    _owner_age=$(( _now - ${_owner_started:-_now} ))",
            "    if [ -n \"$_owner_pid\" ] && [ \"$_owner_age\" -ge \"$FALLBACK_WAIT_SECONDS\" ] 2>/dev/null \\",
            "        && ! kill -0 \"$_owner_pid\" 2>/dev/null; then",
            "      _stale=\"$FALLBACK_LOCK_DIR.stale.$$\"",
            "      if mv \"$FALLBACK_LOCK_DIR\" \"$_stale\" 2>/dev/null; then",
            "        rm -f \"$_stale/owner\" 2>/dev/null || true",
            "        rmdir \"$_stale\" 2>/dev/null || true",
            "        FALLBACK_WAIT_OWNER=\"\"",
            "        continue",
            "      fi",
            "    fi",
            "    if [ $(( _now - _started )) -ge \"$FALLBACK_WAIT_SECONDS\" ] 2>/dev/null; then return 2; fi",
            "    sleep 1",
            "  done",
            "}",
            "",
            "if read_success_result; then",
            "  log \"[pre-push] Reusing recent successful fallback result for unchanged fingerprint.\"",
            "  printf '[pre-push-checker] exit=%s\\n' \"$CACHED_FALLBACK_RC\" >> \"$LOG_FILE\" 2>/dev/null || true",
            "  exit 0",
            "fi",
            "",
            "acquire_fallback_lock",
            "LOCK_RC=$?",
            "if [ \"$LOCK_RC\" -eq 1 ]; then",
            "  log \"[pre-push] Reusing result from the same in-flight fallback validation.\"",
            "  printf '[pre-push-checker] exit=%s\\n' \"$CACHED_FALLBACK_RC\" >> \"$LOG_FILE\" 2>/dev/null || true",
            "  [ \"$CACHED_FALLBACK_RC\" -eq 0 ] && exit 0",
            "  exit 1",
            "elif [ \"$LOCK_RC\" -ne 0 ]; then",
            "  log \"[pre-push] Timed out after 300s waiting for the fallback validation owner. Push aborted.\"",
            "  printf '[pre-push-checker] exit=1\\n' >> \"$LOG_FILE\" 2>/dev/null || true",
            "  exit 1",
            "fi",
            "trap 'cleanup_snapshot_worktree; release_fallback_lock; rm -f \"$CHANGED_SET_FILE\" 2>/dev/null || true' EXIT INT TERM HUP",
            "",
            "# Fallback builds run in a detached worktree so ignored or stale target/build",
            "# outputs from the user's working tree cannot affect the pushed HEAD snapshot.",
            "if ! create_snapshot_worktree; then",
            "  publish_run_result 1 || true",
            "  release_fallback_lock",
            "  log \"[pre-push] Could not create an isolated HEAD worktree. Push aborted.\"",
            "  printf '[pre-push-checker] exit=1\\n' >> \"$LOG_FILE\" 2>/dev/null || true",
            "  exit 1",
            "fi",
            "log \"[pre-push] Running build-tool fallback in isolated HEAD worktree.\"",
            "(",
            "  cd \"$SNAPSHOT_WORKTREE\" || exit 1",
            "  export PRE_PUSH_CHECKER_ORIGINAL_ROOT=\"$REPO_ROOT\"",
            "  export PRE_PUSH_CHECKER_SNAPSHOT_ROOT=\"$SNAPSHOT_WORKTREE\"",
            "  run_compilation",
            ")",
            "EXIT_CODE=$?",
            "cleanup_snapshot_worktree",
            "",
            "# Trailer line the IntelliJ side looks for to detect the final status.",
            "printf '[pre-push-checker] exit=%s\\n' \"$EXIT_CODE\" >> \"$LOG_FILE\" 2>/dev/null || true",
            "",
            "if [ \"$EXIT_CODE\" -ne 0 ]; then",
            "  publish_run_result 1 || true",
            "  release_fallback_lock",
            "  log \"[pre-push] Compilation failed. Push aborted.\"",
            "  log \"[pre-push] Open IntelliJ -> View -> Tool Windows -> Compilation Checker for navigable errors.\"",
            "  exit 1",
            "fi",
            "",
            "publish_success_result || true",
            "publish_run_result 0 || true",
            "release_fallback_lock",
            "log \"[pre-push] Compilation passed. Proceeding with push.\"",
            "exit 0",
            ""
        );
    }

    static String buildWrapperHookScript() {
        return String.join("\n",
            "#!/usr/bin/env sh",
            HOOK_BLOCK_BEGIN,
            "SCRIPT_DIR=\"$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\"",
            "\"$SCRIPT_DIR/" + MANAGED_HOOK_NAME + "\" \"$@\"",
            HOOK_BLOCK_END,
            ""
        );
    }

    static String buildDelegatingSnippet() {
        return String.join("\n",
            "",
            HOOK_BLOCK_BEGIN,
            "SCRIPT_DIR=\"$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\"",
            "\"$SCRIPT_DIR/" + MANAGED_HOOK_NAME + "\" \"$@\" || exit $?",
            HOOK_BLOCK_END,
            ""
        );
    }

    /**
     * Creates/refreshes the global install marker so hook scripts in any repository
     * can detect that the plugin is still installed — even repos that were closed when
     * the IDE started. Called on every project open.
     */
    static void touchGlobalMarker() {
        try {
            Files.createDirectories(GLOBAL_STATE_DIR);
            Files.writeString(GLOBAL_INSTALL_MARKER,
                String.valueOf(System.currentTimeMillis()) + '\n',
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Could not write global install marker: " + e.getMessage());
        }
    }

    /**
     * Deletes the global install marker so orphaned hooks in other repositories
     * self-remove on their next invocation. Called when the plugin is uninstalled.
     */
    static void removeGlobalMarker() {
        try {
            Files.deleteIfExists(GLOBAL_INSTALL_MARKER);
            deleteGlobalStateDirIfEmpty();
        } catch (IOException e) {
            LOG.warn("Could not remove global install marker: " + e.getMessage());
        }
    }

    private static void trackRepo(String basePath) {
        String normalized = normalizePath(basePath);
        if (normalized == null) return;
        synchronized (GLOBAL_REPO_LOCK) {
            LinkedHashSet<String> repos = readTrackedRepos();
            if (!repos.add(normalized)) return;
            writeTrackedRepos(repos);
        }
    }

    private static void untrackRepo(String basePath) {
        String normalized = normalizePath(basePath);
        if (normalized == null) return;
        synchronized (GLOBAL_REPO_LOCK) {
            LinkedHashSet<String> repos = readTrackedRepos();
            if (!repos.remove(normalized)) return;
            writeTrackedRepos(repos);
        }
    }

    private static List<String> snapshotTrackedRepos() {
        synchronized (GLOBAL_REPO_LOCK) {
            return new ArrayList<>(readTrackedRepos());
        }
    }

    private static LinkedHashSet<String> readTrackedRepos() {
        LinkedHashSet<String> repos = new LinkedHashSet<>();
        if (!Files.isRegularFile(GLOBAL_TRACKED_REPOS)) return repos;
        try {
            for (String line : Files.readAllLines(GLOBAL_TRACKED_REPOS, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) repos.add(trimmed);
            }
        } catch (IOException ignored) {
            // Best-effort bookkeeping only.
        }
        return repos;
    }

    private static void writeTrackedRepos(Set<String> repos) {
        try {
            if (repos.isEmpty()) {
                Files.deleteIfExists(GLOBAL_TRACKED_REPOS);
                deleteGlobalStateDirIfEmpty();
                return;
            }
            Files.createDirectories(GLOBAL_STATE_DIR);
            List<String> sorted = new ArrayList<>(repos);
            sorted.sort(Comparator.naturalOrder());
            Files.writeString(
                GLOBAL_TRACKED_REPOS,
                String.join("\n", sorted) + '\n',
                StandardCharsets.UTF_8
            );
        } catch (IOException ignored) {
            // Best-effort bookkeeping only.
        }
    }

    private static void deleteGlobalStateDirIfEmpty() throws IOException {
        if (!Files.isDirectory(GLOBAL_STATE_DIR)) return;
        try (var stream = Files.list(GLOBAL_STATE_DIR)) {
            if (stream.findFirst().isEmpty()) {
                Files.deleteIfExists(GLOBAL_STATE_DIR);
            }
        }
    }

    @org.jetbrains.annotations.Nullable
    private static String normalizePath(@org.jetbrains.annotations.Nullable String path) {
        if (path == null || path.isBlank()) return null;
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void makeExecutable(File file) throws IOException {
        try {
            Files.setPosixFilePermissions(file.toPath(), HOOK_PERMISSIONS);
        } catch (UnsupportedOperationException unsupportedOperationException) {
            if (!file.setExecutable(true, false)) {
                throw new IOException("Failed to mark hook as executable: " + file.getAbsolutePath());
            }
        }
    }

    enum BuildTool {
        GRADLE_WRAPPER,
        GRADLE,
        MAVEN_WRAPPER,
        MAVEN,
        UNKNOWN
    }
}
