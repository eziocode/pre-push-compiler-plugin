package com.github.prepushchecker;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class GitHookInstaller {
    private static final Logger LOG = Logger.getInstance(GitHookInstaller.class);
    static final String HOOK_MARKER = "pre-push-compilation-checker-plugin";
    static final String HOOK_BLOCK_BEGIN = "# BEGIN " + HOOK_MARKER;
    static final String HOOK_BLOCK_END = "# END " + HOOK_MARKER;
    static final String MANAGED_HOOK_NAME = "pre-push-prepushchecker";
    private static final String SAVE_STATUS = "_prepushchecker_previous_status=$?";
    private static final String RESTORE_STATUS = "(exit \"$_prepushchecker_previous_status\")";
    private static final String SCRIPT_DIR_LINE = "SCRIPT_DIR=\"$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\"";
    private static final String HELPER_PATH = "\"$SCRIPT_DIR/" + MANAGED_HOOK_NAME + "\"";
    private static final String CALL_HELPER = HELPER_PATH + " \"$@\"";
    private static final String GUARDED_CALL = "if [ -f " + HELPER_PATH + " ]; then " + CALL_HELPER + " || exit $?; fi";
    private static final String UNINSTALL_CHECK = loadUninstallCheck();
    static final String EXTERNAL_LOG_RELATIVE_PATH = ".idea/pre-push-checker/last-run.log";
    private static final Path GLOBAL_STATE_DIR = Path.of(System.getProperty("user.home"), ".prepush-checker");
    private static final Path GLOBAL_INSTALL_MARKER = GLOBAL_STATE_DIR.resolve("installed");
    private static final Path GLOBAL_TRACKED_REPOS = GLOBAL_STATE_DIR.resolve("repos.txt");
    private static final Object GLOBAL_REPO_LOCK = new Object();
    private static volatile boolean removalStarted;
    // Startup, dynamic-load and repository-change events can all repair the same root at once.
    private static final ConcurrentHashMap<String, Object> ROOT_LOCKS = new ConcurrentHashMap<>();
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

        /** Folds per-root results into one: success only when every root succeeded. */
        static HookRepairResult aggregate(@NotNull List<HookRepairResult> results) {
            if (results.isEmpty()) {
                HookInspectionResult unresolved = new HookInspectionResult(null, null, null, null,
                    List.of(HookIssue.HOOKS_DIRECTORY_UNRESOLVED));
                return new HookRepairResult(false, unresolved, unresolved,
                    "no Git repository was found for this project");
            }
            for (HookRepairResult result : results) {
                if (!result.isSuccess()) return result;
            }
            return results.get(0);
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
        // The bypass switch always starts off; drop any token left by a previous session.
        BypassController.getInstance(project).resetOnStartup();
        // Git repositories are usually not registered yet this early, so this pass is
        // best-effort and silent. The authoritative pass runs once VCS has initialised.
        checkProjectHooks(project, false);
    }

    /**
     * Installs/repairs the hook in every Git root of the project: each registered Git
     * repository plus the project base path when it is itself inside a Git work tree.
     *
     * @param notify when {@code true}, a failed or missing hook raises the
     *               "Install Pre-Push Hook" notification, and a healthy result clears it
     */
    static List<HookRepairResult> installForProject(@NotNull Project project, boolean notify) {
        HookSetup.get(project).enable();
        return checkProjectHooks(project, notify);
    }

    static List<String> projectRoots(@NotNull Project project) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        String base = project.getBasePath();
        if (base != null && !base.isBlank() && resolveDefaultHooksDirectory(base) != null) {
            roots.add(resolveWorkTreeRoot(base).toString());
        }
        for (git4idea.repo.GitRepository repository
                : git4idea.repo.GitRepositoryManager.getInstance(project).getRepositories()) {
            roots.add(repository.getRoot().toNioPath().toAbsolutePath().normalize().toString());
        }
        return List.copyOf(roots);
    }

    /** Automatic discovery may repair existing installations. New installations need project consent. */
    static List<HookRepairResult> checkProjectHooks(@NotNull Project project, boolean notify) {
        if (project.isDisposed() || removalStarted) return List.of();
        PluginLifecycleListener.watchForUninstall();
        touchGlobalMarker();
        List<String> roots = projectRoots(project);
        List<HookRepairResult> results = new ArrayList<>();
        List<HookRepairResult> failures = new ArrayList<>();
        for (String root : roots) {
            if (project.isDisposed()) return results;
            PrePushCheckerSettings.syncSettingsFile(project, root);
            HookRepairResult result = checkRoot(project, root, notify);
            if (result == null) continue;
            results.add(result);
            if (result.isSuccess()) {
                LOG.info(root + ": " + result.statusText());
            } else {
                failures.add(result);
                LOG.warn(root + ": " + result.statusText());
            }
        }

        if (notify && !removalStarted) {
            if (!failures.isEmpty()) {
                HookNotifier.showNotInstalled(project, failures);
            } else if (!results.isEmpty()) {
                HookNotifier.clear(project);
            }
        }
        return results;
    }

    static @Nullable HookRepairResult checkRoot(Project project, String root, boolean notify) {
        if (HookSetup.get(project).enabled()) return repair(root);
        HookPaths paths = resolveHookPaths(root);
        if (paths.hooksDirectory == null) return null;
        if (containsManagedArtifacts(paths.hooksDirectory)
                || paths.usesCoreHooksPath() && paths.legacyHooksDirectory != null
                && !samePath(paths.hooksDirectory, paths.legacyHooksDirectory)
                && containsManagedArtifacts(paths.legacyHooksDirectory)) {
            return repair(root);
        }
        if (notify) HookNotifier.offerSetup(project);
        return null;
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

    /**
     * Explicit repair from the tool window: repairs every Git root of the project and returns
     * the first failure, if any. The caller reports failures itself; success clears any
     * outstanding "hook not installed" notification.
     */
    static HookRepairResult repair(@NotNull Project project) {
        HookRepairResult result = HookRepairResult.aggregate(installForProject(project, false));
        if (result.isSuccess()) HookNotifier.clear(project);
        return result;
    }

    static HookRepairResult repair(@Nullable String basePath) {
        if (basePath == null || basePath.isBlank()) {
            HookInspectionResult before = inspect(basePath);
            return new HookRepairResult(false, before, before, before.statusText());
        }
        synchronized (lockFor(basePath)) {
            return repairLocked(basePath);
        }
    }

    private static Object lockFor(@NotNull String basePath) {
        String key = normalizePath(basePath);
        return ROOT_LOCKS.computeIfAbsent(key == null ? basePath : key, ignored -> new Object());
    }

    private static HookRepairResult repairLocked(@NotNull String basePath) {
        HookInspectionResult before = inspect(basePath);
        if (before.hooksDirectory() == null) {
            return new HookRepairResult(false, before, before, before.statusText());
        }
        synchronized (GLOBAL_REPO_LOCK) {
            if (removalStarted) return new HookRepairResult(false, before, before, "plugin removal in progress");
            // Register before the first mutation so uninstall also waits for an in-flight repair.
            trackRepo(basePath);
        }
        if (before.isHealthy()) {
            // Runs on every repository change; avoid rewriting healthy hooks.
            return new HookRepairResult(true, before, before, null);
        }

        Path hooksDirectory = before.hooksDirectory();

        try {
            Files.createDirectories(hooksDirectory);

            Path managedHook = hooksDirectory.resolve(MANAGED_HOOK_NAME);
            writeAtomically(managedHook, buildManagedHookScript());
            makeExecutable(managedHook.toFile());

            Path mainHook = hooksDirectory.resolve("pre-push");
            if (Files.exists(mainHook)) {
                String content = Files.readString(mainHook, StandardCharsets.UTF_8);
                String repaired = buildRepairedMainHookContent(content);
                if (!content.equals(repaired)) {
                    writeAtomically(mainHook, repaired);
                }
            } else {
                writeAtomically(mainHook, buildWrapperHookScript());
            }
            makeExecutable(mainHook.toFile());

            addToRepoLocalExclude(basePath, hooksDirectory);
            cleanupLegacyManagedHooks(before);

            HookInspectionResult after = inspect(basePath);
            return new HookRepairResult(after.isHealthy(), before, after, null);
        } catch (IOException ioException) {
            LOG.warn("Failed to repair the pre-push hook in " + basePath, ioException);
            HookInspectionResult after = inspect(basePath);
            return new HookRepairResult(false, before, after, ioException.getMessage());
        }
    }

    /**
     * Writes via a sibling temp file and a rename so git (or a concurrent inspection)
     * never sees a half-written hook.
     */
    private static void writeAtomically(@NotNull Path target, @NotNull String content) throws IOException {
        if (Files.isSymbolicLink(target)) {
            target = target.toRealPath();
        }
        Path temp = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".tmp");
        try {
            if (Files.isRegularFile(target)) {
                Files.copy(target, temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
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
     * Reverse of {@link #runStartup} and {@link #addToRepoLocalExclude}. Invoked when the
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
        synchronized (lockFor(basePath)) {
            uninstallLocked(basePath);
        }
    }

    private static void uninstallLocked(@NotNull String basePath) {
        HookPaths paths = resolveHookPaths(basePath);
        boolean cleaned = paths.hooksDirectory == null || removeManagedHookArtifacts(paths.hooksDirectory);
        if (paths.legacyHooksDirectory != null
            && (paths.hooksDirectory == null || !samePath(paths.hooksDirectory, paths.legacyHooksDirectory))) {
            cleaned &= removeManagedHookArtifacts(paths.legacyHooksDirectory);
        }

        // Remove plugin cache + log dir.
        Path cacheDir = Path.of(basePath, ".idea", "pre-push-checker");
        cleaned &= deleteRecursively(cacheDir);

        // Strip the .git/info/exclude block.
        Path gitDir = resolveGitCommonDir(basePath);
        if (gitDir != null) {
            Path excludeFile = gitDir.resolve("info").resolve("exclude");
            if (Files.exists(excludeFile)) {
                try {
                    String existing = Files.readString(excludeFile, StandardCharsets.UTF_8);
                    String stripped = stripExistingBlock(existing);
                    if (!stripped.equals(existing)) {
                        writeAtomically(excludeFile, stripped);
                    }
                } catch (IOException failure) {
                    cleaned = false;
                    LOG.warn("Could not clean " + excludeFile, failure);
                }
            }
        }

        if (cleaned) untrackRepo(basePath);
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
        return stripManagedSections(content, false);
    }

    private static String stripManagedSections(String content, boolean exclude) {
        if (!content.contains(HOOK_MARKER)) return content;
        String[] lines = content.split("(?<=\\n)|(?<=\\r)(?!\\n)");
        StringBuilder out = new StringBuilder(content.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.equals(HOOK_BLOCK_BEGIN)) {
                int end = i + 1;
                while (end < lines.length && !lines[end].trim().equals(HOOK_BLOCK_BEGIN)
                        && !lines[end].trim().equals(HOOK_BLOCK_END)) end++;
                if (end < lines.length && lines[end].trim().equals(HOOK_BLOCK_END)) {
                    i = end;
                    continue;
                }
                // An unmatched BEGIN cannot claim arbitrary subsequent user code.
                if (!exclude && i + 4 < lines.length && SAVE_STATUS.equals(lines[i + 1].trim())
                        && isOwnedScriptDirectoryLine(lines[i + 2]) && GUARDED_CALL.equals(lines[i + 3].trim())
                        && RESTORE_STATUS.equals(lines[i + 4].trim())) {
                    i += 4;
                    continue;
                }
                if (exclude || i + 2 >= lines.length || !isOwnedScriptDirectoryLine(lines[i + 1])
                        || !isOwnedManagedHookInvocation(lines[i + 2])) {
                    out.append(lines[i]);
                    continue;
                }
            } else if (line.equals(HOOK_BLOCK_END)) {
                continue;
            } else if (!line.equals("# " + HOOK_MARKER)) {
                out.append(lines[i]);
                continue;
            }

            if (exclude) {
                if (i + 1 < lines.length && lines[i + 1].trim().equals("/.idea/pre-push-checker/")) i++;
            } else {
                if (i + 1 < lines.length && isOwnedScriptDirectoryLine(lines[i + 1])) i++;
                if (i + 1 < lines.length && isOwnedManagedHookInvocation(lines[i + 1])) i++;
            }
        }
        return out.toString();
    }

    private static boolean isOwnedScriptDirectoryLine(String line) {
        return line.trim().equals(SCRIPT_DIR_LINE);
    }

    private static boolean isOwnedManagedHookInvocation(String line) {
        String trimmed = line.trim();
        return trimmed.equals(CALL_HELPER) || trimmed.equals(CALL_HELPER + " || exit $?")
            || trimmed.equals(GUARDED_CALL);
    }

    private static String buildRepairedMainHookContent(String existingContent) {
        if (isWrapperHookContent(existingContent)) {
            return buildWrapperHookScript();
        }

        String cleaned = stripManagedHookSections(existingContent);
        if (!hasSubstantiveHookContent(cleaned)) {
            return buildWrapperHookScript();
        }
        String snippet = buildDelegatingSnippet();
        return cleaned.endsWith("\n") ? cleaned + snippet.substring(1) : cleaned + snippet;
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

    private static boolean removeManagedHookArtifacts(Path hooksDirectory) {
        Path mainHook = hooksDirectory.resolve("pre-push");
        try {
            if (Files.isRegularFile(mainHook)) {
                String content = Files.readString(mainHook, StandardCharsets.UTF_8);
                String cleaned = stripManagedHookSections(content);
                if (!cleaned.equals(content)) {
                    if (!hasSubstantiveHookContent(cleaned) && !Files.isSymbolicLink(mainHook)) {
                        Files.deleteIfExists(mainHook);
                    } else {
                        writeAtomically(mainHook, cleaned);
                    }
                }
            }
            // Older wrappers unconditionally execute the helper. Detach them first.
            Files.deleteIfExists(hooksDirectory.resolve(MANAGED_HOOK_NAME));
            return true;
        } catch (IOException e) {
            LOG.warn("Could not clean managed hook in " + hooksDirectory, e);
            return false;
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
            // An unreadable or binary third-party hook is not evidence of a plugin installation.
            return false;
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

    private static boolean deleteRecursively(Path path) {
        if (Files.notExists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true;
        try (var stream = Files.walk(path)) {
            for (Path entry : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
            return true;
        } catch (IOException | java.io.UncheckedIOException e) {
            LOG.warn("Could not clean " + path, e);
            return false;
        }
    }

    private static String stripExistingBlock(String content) {
        return stripManagedSections(content, true);
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

        Path gitDir = resolveGitDirFromFile(dotGit);
        return gitDir != null ? resolveCommonDir(gitDir).resolve("hooks") : null;
    }

    /** Reads the {@code gitdir:} pointer of a worktree / submodule {@code .git} file. */
    @Nullable
    private static Path resolveGitDirFromFile(Path dotGit) {
        if (!Files.isRegularFile(dotGit)) return null;
        try {
            for (String line : Files.readAllLines(dotGit, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("gitdir:")) {
                    String target = trimmed.substring("gitdir:".length()).trim();
                    Path resolved = Path.of(target);
                    if (!resolved.isAbsolute()) {
                        resolved = dotGit.getParent().resolve(target);
                    }
                    return resolved.normalize();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // fall through
        }
        return null;
    }

    /**
     * Linked worktrees keep a {@code commondir} file pointing at the main repository's
     * git dir; git reads hooks, config and info/exclude from there, not the worktree dir.
     */
    private static Path resolveCommonDir(Path gitDir) {
        Path commonDirFile = gitDir.resolve("commondir");
        if (Files.isRegularFile(commonDirFile)) {
            try {
                String value = Files.readString(commonDirFile, StandardCharsets.UTF_8).trim();
                if (!value.isEmpty()) {
                    Path common = Path.of(value);
                    return (common.isAbsolute() ? common : gitDir.resolve(common)).normalize();
                }
            } catch (IOException | RuntimeException ignored) {
                // fall through
            }
        }
        return gitDir;
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

    static Path resolveWorkTreeRoot(String basePath) {
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
        if (Files.isDirectory(fallback)) return fallback;
        Path fromFile = resolveGitDirFromFile(fallback);
        return fromFile != null ? resolveCommonDir(fromFile) : null;
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

    /**
     * Prefers the Git executable configured/detected by Git4Idea. A Dock-launched IDE on
     * macOS has a minimal PATH, where a bare {@code git} may be missing or an Xcode shim.
     */
    private static String resolveGitExecutable() {
        var application = ApplicationManager.getApplication();
        // Detection may spawn a process; never do that on the EDT.
        if (application == null || application.isDispatchThread()) return "git";
        try {
            // Git4Idea caches its detection result, so this is cheap after the first call.
            String fromIde = git4idea.config.GitExecutableManager.getInstance().getPathToGit();
            if (fromIde != null && !fromIde.isBlank()) return fromIde;
        } catch (com.intellij.openapi.progress.ProcessCanceledException cancelled) {
            throw cancelled;
        } catch (Throwable failure) {
            LOG.debug("Falling back to git on PATH", failure);
        }
        return "git";
    }

    @Nullable
    private static String queryGitOutput(String basePath, String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = resolveGitExecutable();
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

    private static String loadUninstallCheck() {
        try (var stream = GitHookInstaller.class.getResourceAsStream("hooks/uninstall.sh")) {
            if (stream == null) throw new IllegalStateException("Missing hook uninstall script");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .replace("@MARKER@", HOOK_MARKER).replace("@HELPER@", MANAGED_HOOK_NAME)
                .replace("@SCRIPT_DIR@", SCRIPT_DIR_LINE).replace("@CALL@", CALL_HELPER)
                .replace("@GUARDED_CALL@", GUARDED_CALL)
                .replace("@SAVE_STATUS@", SAVE_STATUS).replace("@RESTORE_STATUS@", RESTORE_STATUS);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String compilerDiagnosticFunctions() {
        try (var stream = GitHookInstaller.class.getResourceAsStream("hooks/compiler-diagnostics.sh")) {
            if (stream == null) throw new IllegalStateException("Missing hook diagnostic parser");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .replace("@MAVEN_GOAL@", awkRegex(CompilerOutputRules.MAVEN_GOAL))
                .replace("@WRAPPER@", awkRegex(CompilerOutputRules.WRAPPER))
                .replace("@INFRA@", awkRegex(CompilerOutputRules.INFRASTRUCTURE));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String awkRegex(String regex) {
        // Embed an ERE in a slash-delimited awk pattern inside a shell single-quoted program.
        return regex.replace("/", "\\/").replace("'", "'\\''");
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
            UNINSTALL_CHECK,
            "",
            "# ── Plugin disabled in IntelliJ: the marker records the installing IDE's config dir (line 2).",
            "# Only that profile's disabled_plugins.txt counts, so another IDE install cannot bypass checks.",
            "_pp_cfg=\"$(sed -n '2p' \"$GLOBAL_MARKER\" 2>/dev/null)\"",
            "if [ -n \"$_pp_cfg\" ] && grep -qx 'com.github.prepushchecker' \"$_pp_cfg/disabled_plugins.txt\" 2>/dev/null; then",
            "  printf '[pre-push] Pre-Push Checker plugin is disabled in IntelliJ. Check skipped. Push allowed.\\n' >&2",
            "  exit 0",
            "fi",
            "",
            "# ── Bypass switch: skip check while the IDE's bypass switch is on ─────────────",
            "# The IDE removes the token when the switch is turned off or auto-expires; the",
            "# age check below also expires a token left behind by a closed or crashed IDE.",
            "BYPASS_TOKEN=\"$REPO_ROOT/.idea/pre-push-checker/bypass-token\"",
            "if [ -f \"$BYPASS_TOKEN\" ]; then",
            "  _token_ms=\"$(head -n1 \"$BYPASS_TOKEN\" 2>/dev/null | tr -d '[:space:]')\"",
            "  _now_s=\"$(date +%s 2>/dev/null || printf '0')\"",
            "  # Token stores millis; convert to seconds for comparison.",
            "  _token_s=$(( ${_token_ms:-0} / 1000 ))",
            "  _age=$(( _now_s - _token_s ))",
            "  if [ \"$_age\" -ge 0 ] && [ \"$_age\" -lt 3600 ] 2>/dev/null; then",
            "    _left_m=$(( (3600 - _age + 59) / 60 ))",
            "    mkdir -p \"$REPO_ROOT/.idea/pre-push-checker\" 2>/dev/null || true",
            "    printf '[pre-push-checker] exit=0\\n' > \"$REPO_ROOT/.idea/pre-push-checker/last-run.log\" 2>/dev/null || true",
            "    printf '[pre-push] Force-push bypass active. Skipping compilation check. Auto-off in %sm.\\n' \"$_left_m\" >&2",
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
            "# Base of one pushed ref: the remote side when git has that commit; otherwise (new branch,",
            "# or the remote moved on and was not fetched) the parent of the oldest commit no remote has.",
            "# Empty when nothing is outgoing or the outgoing history starts at a root commit.",
            "push_base() {",
            "  _pb_local=\"$1\"",
            "  _pb_remote=\"${2:-$NULL_SHA}\"",
            "  if [ \"$_pb_remote\" != \"$NULL_SHA\" ] && git cat-file -e \"${_pb_remote}^{commit}\" 2>/dev/null; then",
            "    printf '%s\\n' \"$_pb_remote\"",
            "    return 0",
            "  fi",
            "  _pb_first=\"$(git rev-list --topo-order --reverse \"$_pb_local\" --not --remotes 2>/dev/null | head -n1)\"",
            "  [ -n \"$_pb_first\" ] || return 0",
            "  git rev-parse --verify -q \"${_pb_first}^\" 2>/dev/null || true",
            "}",
            "",
            "# Net file changes per pushed ref (base..local), so a commit and its revert cancel out.",
            "# Deletions push no content and are skipped.",
            "collect_changed_files() {",
            "  while IFS=' ' read -r local_ref local_sha remote_ref remote_sha; do",
            "    [ -z \"${local_sha:-}\" ] && continue",
            "    if [ \"$local_sha\" = \"$NULL_SHA\" ]; then",
            "      continue",
            "    fi",
            "",
            "    _cc_base=\"$(push_base \"$local_sha\" \"$remote_sha\")\"",
            "    if [ -n \"$_cc_base\" ]; then",
            "      git diff --name-only --diff-filter=ACMR \"$_cc_base\" \"$local_sha\" 2>/dev/null",
            "    else",
            "      git rev-list \"$local_sha\" --not --remotes 2>/dev/null | while IFS= read -r commit_sha; do",
            "        [ -z \"$commit_sha\" ] && continue",
            "        git diff-tree --no-commit-id --name-only -r --diff-filter=ACMR \"$commit_sha\" 2>/dev/null",
            "      done",
            "    fi",
            "  done",
            "}",
            "",
            compilerDiagnosticFunctions(),
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
            "  _off_g=\"\"",
            "  _off_m=\"\"",
            "  if [ \"$_mode\" = \"primary\" ]; then _off_g=\"--offline\"; _off_m=\"-o\"; fi",
            "  if [ -n \"${PRE_PUSH_CHECKER_COMMAND:-}\" ]; then",
            "    LAST_BUILD_TOOL=custom",
            "    sh -c \"$PRE_PUSH_CHECKER_COMMAND\" >\"$_out_file\" 2>&1",
            "    return $?",
            "  elif [ -x \"./gradlew\" ]; then",
            "    LAST_BUILD_TOOL=gradle",
            "    ./gradlew --console=plain --quiet --parallel --build-cache --configure-on-demand $_off_g $_gradle_tasks >\"$_out_file\" 2>&1",
            "    return $?",
            "  elif [ -f \"./build.gradle\" ] || [ -f \"./build.gradle.kts\" ]; then",
            "    LAST_BUILD_TOOL=gradle",
            "    gradle --console=plain --quiet --parallel --build-cache --configure-on-demand $_off_g $_gradle_tasks >\"$_out_file\" 2>&1",
            "    return $?",
            "  elif [ -x \"./mvnw\" ]; then",
            "    LAST_BUILD_TOOL=maven",
            "    if [ \"$_mode\" = \"recovery\" ]; then",
            "      ./mvnw -q -Dmaven.javadoc.skip=true clean \"$_maven_goal\" >\"$_out_file\" 2>&1",
            "    else",
            "      ./mvnw -q $_off_m -T1C -Dmaven.javadoc.skip=true \"$_maven_goal\" >\"$_out_file\" 2>&1",
            "    fi",
            "    return $?",
            "  elif [ -f \"./pom.xml\" ]; then",
            "    LAST_BUILD_TOOL=maven",
            "    if command -v mvnd >/dev/null 2>&1; then",
            "      if [ \"$_mode\" = \"recovery\" ]; then",
            "        mvnd -q -T1 -Dmaven.javadoc.skip=true clean \"$_maven_goal\" >\"$_out_file\" 2>&1",
            "      else",
            "        mvnd -q $_off_m -T1C -Dmaven.javadoc.skip=true \"$_maven_goal\" >\"$_out_file\" 2>&1",
            "      fi",
            "      return $?",
            "    fi",
            "    if [ \"$_mode\" = \"recovery\" ]; then",
            "      mvn -q -Dmaven.javadoc.skip=true clean \"$_maven_goal\" >\"$_out_file\" 2>&1",
            "    else",
            "      mvn -q $_off_m -T1C -Dmaven.javadoc.skip=true \"$_maven_goal\" >\"$_out_file\" 2>&1",
            "    fi",
            "    return $?",
            "  fi",
            "  return 127",
            "}",
            "",
            "assess_compiler_failure() {",
            "  [ $rc -eq 1 ] && [ \"$LAST_BUILD_TOOL\" != custom ] || return 0",
            "  extract_error_records \"$TMP_OUT\" \"$TMP_REC\" || return 0",
            "  if all_compiler_records_ignored \"$TMP_REC\" && [ \"$FALLBACK_IGNORE_RULES\" = \"$(current_ignore_rules)\" ]; then",
            "    log \"[pre-push] Compiler reported errors only in enabled ignored files. Proceeding.\"",
            "    rc=0",
            "  fi",
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
            "  assess_compiler_failure",
            "",
            "  if [ $rc -ne 0 ] && grep -Eiq 'Could not resolve|offline mode|No cached version|Cannot access .* in offline|Could not transfer artifact' \"$TMP_OUT\" 2>/dev/null; then",
            "    log \"[pre-push] Offline build could not resolve dependencies; retrying once online.\"",
            "    : > \"$TMP_OUT\" 2>/dev/null || true",
            "    run_build_tool_command \"$GRADLE_TASKS\" \"$MAVEN_GOAL\" \"$TMP_OUT\" online",
            "    rc=$?",
            "    assess_compiler_failure",
            "  fi",
            "",
            "  if [ \"$rc\" -eq 127 ] && [ \"$LAST_BUILD_TOOL\" = none ]; then",
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
            "    assess_compiler_failure",
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
            "PUSH_UPDATES_ID=\"$(printf '%s\\n' \"$HOOK_INPUT\" | cksum | awk '{print $1 \"-\" $2}')\"",
            "",
            "# The upstream fallback is only for runs without push input (e.g. run by hand). With input,",
            "# no files means nothing new is uploaded: a branch deletion, a revert that cancels out, or",
            "# commits the remote already has.",
            "if [ -z \"$CHANGED_FILES\" ] && [ -z \"$(printf '%s' \"$HOOK_INPUT\" | tr -d '[:space:]')\" ]; then",
            "  if git rev-parse --verify --quiet '@{upstream}' >/dev/null 2>&1; then",
            "    CHANGED_FILES=\"$(git diff --name-only --diff-filter=ACMR '@{upstream}...HEAD' 2>/dev/null | sed '/^$/d' | sort -u)\"",
            "  else",
            "    CHANGED_FILES=\"$(git diff-tree --no-commit-id --name-only -r HEAD 2>/dev/null | sed '/^$/d' | sort -u)\"",
            "  fi",
            "fi",
            "",
            "if [ -z \"$CHANGED_FILES\" ]; then",
            "  log \"[pre-push] No outgoing files detected. Skipping compilation check.\"",
            "  printf '[pre-push-checker] exit=0\\n' >> \"$LOG_FILE\" 2>/dev/null || true",
            "  exit 0",
            "fi",
            "",
            "CHANGED_SET_FILE=\"$(mktemp 2>/dev/null || printf '%s/prepush-%s.changed' \"${TMPDIR:-/tmp}\" $$)\"",
            "printf '%s\\n' \"$CHANGED_FILES\" > \"$CHANGED_SET_FILE\" 2>/dev/null || true",
            "trap 'rm -f \"$CHANGED_SET_FILE\" 2>/dev/null || true' EXIT INT TERM HUP",
            "",
            "# AI review gate inputs. The IDE decides; the hook only needs to know whether review files",
            "# changed (so it asks the IDE even without source changes) and whether review is mandatory",
            "# (so it can block when the IDE is not running). 'mandatory: true' in the rule file forces it;",
            "# otherwise the developer's own IDE toggle decides (the file never switches it off).",
            "REVIEW_SETTINGS_FILE=\"$REPO_ROOT/.idea/pre-push-checker/settings\"",
            "_review_rule_rel=\"$(sed -n 's/^reviewRuleFile=//p' \"$REVIEW_SETTINGS_FILE\" 2>/dev/null | head -n1)\"",
            "[ -n \"$_review_rule_rel\" ] || _review_rule_rel='.prepush-review.md'",
            "case \"$_review_rule_rel\" in /*) REVIEW_RULE_FILE=\"$_review_rule_rel\" ;; *) REVIEW_RULE_FILE=\"$REPO_ROOT/$_review_rule_rel\" ;; esac",
            "REVIEW_IN_SCOPE=0",
            "REVIEW_MANDATORY=0",
            "if [ -s \"$REVIEW_RULE_FILE\" ] && ! grep -q '^reviewEnabled=false' \"$REVIEW_SETTINGS_FILE\" 2>/dev/null; then",
            "  _review_scope=\"$(sed -n 's/^reviewScopeRegex=//p' \"$REVIEW_SETTINGS_FILE\" 2>/dev/null | head -n1)\"",
            "  if [ -z \"$_review_scope\" ]; then",
            "    REVIEW_IN_SCOPE=1",
            "  elif grep -Eq \"$_review_scope\" \"$CHANGED_SET_FILE\" 2>/dev/null; then",
            "    REVIEW_IN_SCOPE=1",
            "  fi",
            "  _review_rule_mandate=\"$(awk '",
            "    s == 0 && /^[[:space:]]*$/ { next }",
            "    s == 0 { if ($0 ~ /^---[[:space:]]*$/) { s = 1; next } else exit }",
            "    /^---[[:space:]]*$/ { exit }",
            "    /^[[:space:]]*(mandatory|required)[[:space:]]*:/ { sub(/^[^:]*:[[:space:]]*/, \"\"); gsub(/[^A-Za-z]/, \"\"); print tolower($0); exit }",
            "  ' \"$REVIEW_RULE_FILE\" 2>/dev/null)\"",
            "  case \"$_review_rule_mandate\" in",
            "    true|yes|on) REVIEW_MANDATORY=1 ;;",
            "    *) grep -q '^reviewMandatory=true' \"$REVIEW_SETTINGS_FILE\" 2>/dev/null && REVIEW_MANDATORY=1 ;;",
            "  esac",
            "fi",
            "# Remote side of the pushed range, so the IDE review diffs exactly what is pushed.",
            "# PUSH_RANGE_HEAD is the pushed commit, which need not be HEAD (git push origin other-branch).",
            "PUSH_RANGE_BASE=''",
            "PUSH_RANGE_HEAD=''",
            "while IFS=' ' read -r _pr_lref _pr_lsha _pr_rref _pr_rsha; do",
            "  [ -n \"${_pr_lsha:-}\" ] && [ \"$_pr_lsha\" != \"$NULL_SHA\" ] || continue",
            "  PUSH_RANGE_HEAD=\"$(git rev-parse --verify -q \"${_pr_lsha}^{commit}\" 2>/dev/null || printf '%s' \"$_pr_lsha\")\"",
            "  PUSH_RANGE_BASE=\"$(push_base \"$_pr_lsha\" \"$_pr_rsha\")\"",
            "  break",
            "done <<PREPUSH_RANGE_EOF",
            "$HOOK_INPUT",
            "PREPUSH_RANGE_EOF",
            "",
            "# grep the temp file instead of piping: grep -q exits early and a pipe gives printf EPIPE.",
            "SOURCE_CHANGED=1",
            "if ! grep -Eq '(^|/)(pom\\.xml|build\\.gradle(\\.kts)?|settings\\.gradle(\\.kts)?|gradle\\.properties|gradlew|mvnw|build\\.xml|\\.prepush-layers)$|(\\.java|\\.kts?|\\.groovy|\\.scala|\\.jar)$' \"$CHANGED_SET_FILE\" 2>/dev/null; then",
            "  SOURCE_CHANGED=0",
            "  if [ \"$REVIEW_IN_SCOPE\" -ne 1 ]; then",
            "    log \"[pre-push] No source or build changes detected. Skipping compilation check.\"",
            "    printf '[pre-push-checker] exit=0\\n' >> \"$LOG_FILE\" 2>/dev/null || true",
            "    exit 0",
            "  fi",
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
            "    *.java|*.kt|*.kts|*.groovy|*.scala|*.jar|*pom.xml|*build.gradle|*build.gradle.kts|*settings.gradle|*settings.gradle.kts|*gradle.properties)",
            "      MAIN_CHANGED=1 ;;",
            "  esac",
            "done <<CHANGED_EOF",
            "$CHANGED_FILES",
            "CHANGED_EOF",
            "",
            "# Build-script edits invalidate everything -> always compile both.",
            "if grep -Eq '(^|/)(pom\\.xml|build\\.gradle(\\.kts)?|settings\\.gradle(\\.kts)?|gradle\\.properties)$' \"$CHANGED_SET_FILE\" 2>/dev/null; then",
            "  MAIN_CHANGED=1",
            "  TEST_CHANGED=1",
            "fi",
            "",
            "if [ \"$SOURCE_CHANGED\" -eq 1 ]; then",
            "  log \"[pre-push] Relevant source/build changes detected. Running compilation check...\"",
            "else",
            "  log \"[pre-push] Files covered by the AI review rules changed. Checking the review in IntelliJ...\"",
            "fi",
            "",
            "# Fast path: ask the running IntelliJ to reuse its incremental JPS compiler.",
            "# Falls through to the build tool if IntelliJ is not running or the request fails.",
            "PORT_FILE=\"$REPO_ROOT/.idea/pre-push-checker/server.port\"",
            "SETTINGS_FILE=\"$REPO_ROOT/.idea/pre-push-checker/settings\"",
            "HEAD_SHA=\"$(git rev-parse --verify HEAD 2>/dev/null || printf '')\"",
            "FALLBACK_PROTOCOL_VERSION=3",
            "FALLBACK_RUN_RESULT_FILE=\"$REPO_ROOT/.idea/pre-push-checker/last-fallback-run-v2\"",
            "LEGACY_FALLBACK_RESULT_FILE=\"$REPO_ROOT/.idea/pre-push-checker/last-fallback-result\"",
            "FALLBACK_LOCK_DIR=\"$REPO_ROOT/.idea/pre-push-checker/fallback.lock\"",
            "FALLBACK_LOCK_HELD=0",
            "FALLBACK_WAIT_SECONDS=300",
            "FALLBACK_WAIT_OWNER=\"\"",
            "SNAPSHOT_TEMP_BASE=\"${TMPDIR:-/tmp}\"",
            "SNAPSHOT_TEMP_BASE=\"${SNAPSHOT_TEMP_BASE%/}\"",
            "SNAPSHOT_TEMP_ROOT=\"\"",
            "SNAPSHOT_WORKTREE=\"\"",
            "IDE_BOOTSTRAP_RETRIES=1",
            "IDE_BOOTSTRAP_SLEEP=0.25",
            "IDE_FALLBACK_REASON=\"\"",
            "IDE_STATUS=\"\"",
            "IDE_FAIL_DETAIL=\"\"",
            "rm -f \"$LEGACY_FALLBACK_RESULT_FILE\" 2>/dev/null || true",
            "try_ide_compile() {",
            "  [ -r \"$PORT_FILE\" ] || return 2",
            "  IDE_PORT=\"$(head -n1 \"$PORT_FILE\" 2>/dev/null | tr -d '[:space:]')\"",
            "  [ -n \"$IDE_PORT\" ] || return 2",
            "  IDE_TOKEN=\"$(sed -n '2p' \"$PORT_FILE\" 2>/dev/null | tr -d '[:space:]')\"",
            "  [ -n \"$IDE_TOKEN\" ] || return 2",
            "  # /dev/tcp is a bash feature; fall back cleanly if unavailable.",
            "  command -v bash >/dev/null 2>&1 || return 2",
            "  # Translate the change list (repo-relative) to absolute paths so the IDE can resolve them.",
            "  ABS_FILES=\"$(printf '%s\\n' \"$CHANGED_FILES\" | awk -v root=\"$REPO_ROOT\" 'NF{print root\"/\"$0}')\"",
            "  REQ_FILES=\"$(printf '%s\\n' \"$ABS_FILES\" | sed 's/^/PATH=/')\"",
            "  IDE_RESP=\"$(REQ_FILES=\"$REQ_FILES\" IDE_PORT=\"$IDE_PORT\" REQ_TOKEN=\"$IDE_TOKEN\" REQ_ROOT=\"$REPO_ROOT\" REQ_HEAD=\"$HEAD_SHA\" REQ_UPDATES=\"$PUSH_UPDATES_ID\" REQ_RANGE=\"$PUSH_RANGE_BASE\" REQ_PUSHED=\"$PUSH_RANGE_HEAD\" bash -c '",
            "    exec 3<>/dev/tcp/127.0.0.1/\"$IDE_PORT\" || exit 2",
            "    printf \"CHECK 2\\nTOKEN=%s\\nROOT=%s\\nHEAD=%s\\nUPDATES=%s\\nRANGE=%s\\nPUSHED=%s\\n%s\\n\\n\" \"$REQ_TOKEN\" \"$REQ_ROOT\" \"$REQ_HEAD\" \"$REQ_UPDATES\" \"$REQ_RANGE\" \"$REQ_PUSHED\" \"$REQ_FILES\" >&3",
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
            "      IDE_STATUS=TIMEOUT",
            "      log \"[pre-push] IntelliJ validation timed out. Push aborted without starting a duplicate fallback build.\"",
            "      printf '%s\\n' \"$IDE_RESP\" >> \"$LOG_FILE\" 2>/dev/null || true",
            "      return 3 ;;",
            "    STALE*)",
            "      IDE_STATUS=STALE",
            "      log \"[pre-push] Repository changed during validation. Retry push from current branch.\"",
            "      printf '%s\\n' \"$IDE_RESP\" >> \"$LOG_FILE\" 2>/dev/null || true",
            "      return 3 ;;",
            "    FAIL*)",
            "      IDE_FAIL_DETAIL=\"$(printf '%s' \"$IDE_FIRST\" | sed 's/^FAIL infrastructure //')\"",
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
            "  elif [ -n \"$IDE_FAIL_DETAIL\" ]; then",
            "    IDE_FALLBACK_REASON=\"ide-check-failed: $IDE_FAIL_DETAIL\"",
            "  else",
            "    IDE_FALLBACK_REASON=\"ide-server-unreachable-or-busy-after-wait\"",
            "  fi",
            "  return 2",
            "}",
            "",
            "log \"[pre-push] Waiting for IntelliJ: compilation check and AI review run in parallel (see Processes in IntelliJ)...\"",
            "try_ide_compile_with_retry",
            "IDE_RC=$?",
            "if [ \"$IDE_RC\" -eq 0 ] || [ \"$IDE_RC\" -eq 1 ] || [ \"$IDE_RC\" -eq 3 ]; then",
            "  printf '[pre-push-checker] source=ide\\n' >> \"$LOG_FILE\" 2>/dev/null || true",
            "fi",
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
            "  printf '[pre-push-checker] status=%s\\n' \"${IDE_STATUS:-TIMEOUT}\" >> \"$LOG_FILE\" 2>/dev/null || true",
            "  printf '[pre-push-checker] exit=1\\n' >> \"$LOG_FILE\" 2>/dev/null || true",
            "  exit 1",
            "fi",
            "",
            "# 'Skip the check when IntelliJ is closed' wins over everything, mandatory AI review included.",
            "if [ -f \"$SETTINGS_FILE\" ] && grep -q \"^disableBuildToolFallback=true\" \"$SETTINGS_FILE\" 2>/dev/null; then",
            "  log \"[pre-push] IntelliJ check unavailable (${IDE_FALLBACK_REASON:-unknown}) and 'Skip the check when IntelliJ is closed' is on. Compilation check and AI review skipped. Push allowed.\"",
            "  printf '[pre-push-checker] exit=0\\n' >> \"$LOG_FILE\" 2>/dev/null || true",
            "  exit 0",
            "fi",
            "",
            "if [ \"$REVIEW_IN_SCOPE\" -eq 1 ] && [ \"$REVIEW_MANDATORY\" -eq 1 ]; then",
            "  log \"[pre-push] AI review is mandatory for this repository ($_review_rule_rel), and IntelliJ is not available to check it (${IDE_FALLBACK_REASON:-unknown}).\"",
            "  log \"[pre-push] Open this project in IntelliJ, run 'Review Changes with AI' in the Commit tool window (or the Code Review tab), resolve the findings, then push again.\"",
            "  log \"[pre-push] Push aborted.\"",
            "  printf '[pre-push-checker] exit=1\\n' >> \"$LOG_FILE\" 2>/dev/null || true",
            "  exit 1",
            "fi",
            "if [ \"$SOURCE_CHANGED\" -ne 1 ]; then",
            "  log \"[pre-push] IntelliJ is not available and AI review is not mandatory; no source changes to compile. Push allowed.\"",
            "  printf '[pre-push-checker] exit=0\\n' >> \"$LOG_FILE\" 2>/dev/null || true",
            "  exit 0",
            "fi",
            "log \"[pre-push] IntelliJ incremental compile unavailable (${IDE_FALLBACK_REASON:-unknown}); using build-tool fallback.\"",
            "",
            "FALLBACK_MODE=\"main\"",
            "if [ \"$MAIN_CHANGED\" -eq 1 ] && [ \"$TEST_CHANGED\" -eq 1 ]; then",
            "  FALLBACK_MODE=\"all\"",
            "elif [ \"$TEST_CHANGED\" -eq 1 ]; then",
            "  FALLBACK_MODE=\"test\"",
            "fi",
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
            "current_ignore_rules() {",
            "  sed -n '/^ignoredFile=/p' \"$SETTINGS_FILE\" 2>/dev/null || true",
            "}",
            "FALLBACK_IGNORE_RULES=\"$(current_ignore_rules)\"",
            "FALLBACK_IGNORED_ID=none",
            "if [ -r \"$SETTINGS_FILE\" ]; then",
            "  _settings_java_home=\"$(sed -n 's/^preferredJavaHome=//p' \"$SETTINGS_FILE\" | head -n1)\"",
            "  [ -n \"$_settings_java_home\" ] && FALLBACK_JAVA_ID=\"$_settings_java_home\"",
            "  FALLBACK_IGNORED_ID=\"$(printf '%s\\n' \"$FALLBACK_IGNORE_RULES\" | cksum | awk '{print $1 \"-\" $2}')\"",
            "fi",
            "FALLBACK_FINGERPRINT=\"$(printf '%s\\n' \"$FALLBACK_PROTOCOL_VERSION|$HEAD_SHA|$FALLBACK_MODE|$FALLBACK_CHANGED_ID|$FALLBACK_TOOL_ID|$FALLBACK_JAVA_ID|$FALLBACK_IGNORED_ID\" | cksum | awk '{print $1 \"-\" $2}')\"",
            "",
            "read_run_result() {",
            "  _expected_owner=\"$1\"",
            "  [ -n \"$_expected_owner\" ] && [ -r \"$FALLBACK_RUN_RESULT_FILE\" ] || return 1",
            "  IFS='|' read -r _version _owner _fingerprint _result_rc _completed_at < \"$FALLBACK_RUN_RESULT_FILE\" || return 1",
            "  [ \"$_version\" = \"$FALLBACK_PROTOCOL_VERSION\" ] || return 1",
            "  [ \"$_owner\" = \"$_expected_owner\" ] || return 1",
            "  [ \"$_fingerprint\" = \"$FALLBACK_FINGERPRINT\" ] || return 1",
            "  [ \"$FALLBACK_IGNORE_RULES\" = \"$(current_ignore_rules)\" ] || return 1",
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
            "      if [ -n \"$FALLBACK_WAIT_OWNER\" ] && read_run_result \"$FALLBACK_WAIT_OWNER\"; then release_fallback_lock; return 1; fi",
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
            "publish_run_result 0 || true",
            "release_fallback_lock",
            "log \"[pre-push] Compilation passed. Proceeding with push.\"",
            "exit 0",
            ""
        );
    }

    static String buildWrapperHookScript() {
        return "#!/usr/bin/env sh" + buildDelegatingSnippet();
    }

    static String buildDelegatingSnippet() {
        return String.join("\n",
            "",
            HOOK_BLOCK_BEGIN,
            SAVE_STATUS,
            SCRIPT_DIR_LINE,
            GUARDED_CALL,
            RESTORE_STATUS,
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
        synchronized (GLOBAL_REPO_LOCK) {
            if (removalStarted) return;
            Path location = PathManager.getJarForClass(GitHookInstaller.class);
            // A packaged plugin lives in <installation>/lib/<versioned jar>. The installation
            // directory survives an update and disappears when IntelliJ removes the plugin.
            if (location != null && Files.isRegularFile(location)) {
                Path lib = location.getParent();
                if (lib != null && lib.endsWith("lib") && lib.getParent() != null) location = lib.getParent();
            }
            String path = location == null ? "" : location.toString();
            try {
                Files.createDirectories(GLOBAL_STATE_DIR);
                writeAtomically(GLOBAL_INSTALL_MARKER,
                    System.currentTimeMillis() + "\n" + PathManager.getConfigDir() + "\n" + path + "\n");
            } catch (IOException e) {
                LOG.warn("Could not write global install marker", e);
            }
        }
    }

    static boolean isRemovalStarted() {
        return removalStarted;
    }

    static void beginRemoval() {
        synchronized (GLOBAL_REPO_LOCK) {
            removalStarted = true;
            try {
                Files.deleteIfExists(GLOBAL_INSTALL_MARKER);
                deleteGlobalStateDirIfEmpty();
            } catch (IOException e) {
                LOG.warn("Could not remove global install marker", e);
            }
        }
    }

    @FunctionalInterface
    interface CacheWrite {
        void run() throws IOException;
    }

    // Only filesystem operations belong here. Resolve project services before entering.
    static void writeRepositoryCache(String root, Path directory, CacheWrite write) throws IOException {
        synchronized (GLOBAL_REPO_LOCK) {
            if (removalStarted) return;
            if (!Files.isDirectory(directory)) {
                trackRepo(root);
                Files.createDirectories(directory);
            }
            write.run();
        }
    }

    // Called under GLOBAL_REPO_LOCK, before creating hook or cache files.
    private static void trackRepo(String basePath) {
        String normalized = normalizePath(basePath);
        if (normalized == null) return;
        LinkedHashSet<String> repos = readTrackedRepos();
        if (repos.add(normalized)) writeTrackedRepos(repos);
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

    static Set<String> snapshotTrackedRepos() {
        synchronized (GLOBAL_REPO_LOCK) {
            return readTrackedRepos();
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
            writeAtomically(GLOBAL_TRACKED_REPOS, String.join("\n", sorted) + '\n');
        } catch (IOException e) {
            LOG.warn("Could not update the list of repositories to clean on uninstall", e);
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
