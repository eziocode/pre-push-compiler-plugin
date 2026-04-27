package com.github.prepushchecker;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class GitHookInstaller implements StartupActivity {
    private static final Logger LOG = Logger.getInstance(GitHookInstaller.class);
    static final String HOOK_MARKER = "pre-push-compilation-checker-plugin";
    static final String MANAGED_HOOK_NAME = "pre-push-prepushchecker";
    static final String EXTERNAL_LOG_RELATIVE_PATH = ".idea/pre-push-checker/last-run.log";
    private static final Set<PosixFilePermission> HOOK_PERMISSIONS = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_EXECUTE
    );

    @Override
    public void runActivity(Project project) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return;
        }

        Path hooksDirectory = resolveHooksDirectory(basePath);
        if (hooksDirectory == null) {
            LOG.info("Could not resolve a git hooks directory under " + basePath + "; skipping hook installation.");
            return;
        }

        Path mainHook = hooksDirectory.resolve("pre-push");
        Path managedHook = hooksDirectory.resolve(MANAGED_HOOK_NAME);

        try {
            Files.createDirectories(hooksDirectory);
            String managedContent = buildManagedHookScript();
            if (!Files.exists(managedHook) || !Files.readString(managedHook, StandardCharsets.UTF_8).equals(managedContent)) {
                Files.writeString(managedHook, managedContent, StandardCharsets.UTF_8);
            }
            makeExecutable(managedHook.toFile());

            if (Files.exists(mainHook)) {
                String existingContent = Files.readString(mainHook, StandardCharsets.UTF_8);
                if (existingContent.contains(HOOK_MARKER)) {
                    // Always refresh our own wrapper so upgrades ship cleanly.
                    Files.writeString(mainHook, buildWrapperHookScript(), StandardCharsets.UTF_8);
                    makeExecutable(mainHook.toFile());
                    LOG.info("Refreshed managed pre-push hook at " + mainHook);
                    return;
                }

                Files.writeString(mainHook, existingContent + buildDelegatingSnippet(), StandardCharsets.UTF_8);
                makeExecutable(mainHook.toFile());
                LOG.info("Chained the managed pre-push checker into an existing hook at " + mainHook);
                return;
            }

            Files.writeString(mainHook, buildWrapperHookScript(), StandardCharsets.UTF_8);
            makeExecutable(mainHook.toFile());
            LOG.info("Installed pre-push hook wrapper at " + mainHook
                + " (detected build tool: " + detectBuildTool(basePath) + ").");
        } catch (IOException ioException) {
            LOG.error("Failed to install the pre-push hook.", ioException);
        }

        // Keep plugin-owned files out of `git status` / Sublime Merge untracked lists.
        addToRepoLocalExclude(basePath, hooksDirectory);
    }

    /**
     * Adds plugin-owned paths to {@code .git/info/exclude} so they never show up as untracked
     * in git clients, without touching the repo's tracked {@code .gitignore}.
     *
     * <p>Managed as a delimited block so we can upgrade the patterns on future plugin versions.
     */
    static void addToRepoLocalExclude(String basePath, @org.jetbrains.annotations.Nullable Path hooksDirectory) {
        Path gitDir = queryGit(basePath, "rev-parse", "--git-common-dir");
        if (gitDir == null) {
            Path fallback = Path.of(basePath, ".git");
            if (Files.isDirectory(fallback)) gitDir = fallback;
        }
        if (gitDir == null) return;

        Path excludeFile = gitDir.resolve("info").resolve("exclude");

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
        String desiredBlock = block.toString();

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

        Path hooksDirectory = resolveHooksDirectory(basePath);
        if (hooksDirectory != null) {
            Path mainHook = hooksDirectory.resolve("pre-push");
            Path managedHook = hooksDirectory.resolve(MANAGED_HOOK_NAME);
            try { Files.deleteIfExists(managedHook); } catch (IOException ignored) {}

            if (Files.exists(mainHook)) {
                try {
                    String content = Files.readString(mainHook, StandardCharsets.UTF_8);
                    if (content.equals(buildWrapperHookScript())) {
                        Files.deleteIfExists(mainHook);
                    } else if (content.contains(HOOK_MARKER)) {
                        String snippet = buildDelegatingSnippet();
                        String cleaned = content.contains(snippet)
                            ? content.replace(snippet, "")
                            : stripDelegatingLines(content);
                        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");
                        if (cleaned.isBlank()) {
                            Files.deleteIfExists(mainHook);
                        } else {
                            Files.writeString(mainHook, cleaned, StandardCharsets.UTF_8);
                        }
                    }
                } catch (IOException ignored) {}
            }
        }

        // Remove plugin cache + log dir.
        Path cacheDir = Path.of(basePath, ".idea", "pre-push-checker");
        deleteRecursively(cacheDir);

        // Strip the .git/info/exclude block.
        Path gitDir = queryGit(basePath, "rev-parse", "--git-common-dir");
        if (gitDir == null) {
            Path fallback = Path.of(basePath, ".git");
            if (Files.isDirectory(fallback)) gitDir = fallback;
        }
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

        LOG.info("Pre-Push Compilation Checker: cleaned hooks and cache under " + basePath);
    }

    private static String stripDelegatingLines(String content) {
        StringBuilder out = new StringBuilder(content.length());
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().equals("# " + HOOK_MARKER)) {
                // Skip marker line plus the two snippet lines that follow, if they match.
                int skip = 1;
                if (i + 1 < lines.length && lines[i + 1].contains("SCRIPT_DIR=")) skip++;
                if (i + skip < lines.length && lines[i + skip].contains(MANAGED_HOOK_NAME)) skip++;
                i += skip - 1;
                continue;
            }
            out.append(line);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
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

    @org.jetbrains.annotations.Nullable
    private static String relativeToWorkTree(String basePath, @org.jetbrains.annotations.Nullable Path hooksDirectory) {
        if (hooksDirectory == null) return null;
        try {
            Path base = Path.of(basePath).toAbsolutePath().normalize();
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
        Path viaGit = queryGit(basePath, "rev-parse", "--git-path", "hooks");
        if (viaGit != null) {
            return viaGit;
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

    private static Path queryGit(String basePath, String... args) {
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
            Path candidate = Path.of(output);
            if (!candidate.isAbsolute()) {
                candidate = Path.of(basePath).resolve(output).normalize();
            }
            return candidate;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
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
            "LOG_DIR=\"$REPO_ROOT/.idea/pre-push-checker\"",
            "LOG_FILE=\"$LOG_DIR/last-run.log\"",
            "mkdir -p \"$LOG_DIR\" 2>/dev/null || true",
            ": > \"$LOG_FILE\" 2>/dev/null || true",
            "",
            "log() { printf '%s\\n' \"$*\" | tee -a \"$LOG_FILE\" >&2; }",
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
            "run_compilation() {",
            "  TMP_OUT=\"$(mktemp 2>/dev/null || printf '%s/prepush-%s.out' \"${TMPDIR:-/tmp}\" $$)\"",
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
            "  if [ -n \"${PRE_PUSH_CHECKER_COMMAND:-}\" ]; then",
            "    sh -c \"$PRE_PUSH_CHECKER_COMMAND\" >\"$TMP_OUT\" 2>&1",
            "    rc=$?",
            "  elif [ -x \"./gradlew\" ]; then",
            "    ./gradlew --console=plain --quiet --parallel --build-cache $GRADLE_TASKS >\"$TMP_OUT\" 2>&1",
            "    rc=$?",
            "  elif [ -f \"./build.gradle\" ] || [ -f \"./build.gradle.kts\" ]; then",
            "    gradle --console=plain --quiet --parallel --build-cache $GRADLE_TASKS >\"$TMP_OUT\" 2>&1",
            "    rc=$?",
            "  elif [ -x \"./mvnw\" ]; then",
            "    ./mvnw -q -T1C -Dmaven.javadoc.skip=true \"$MAVEN_GOAL\" >\"$TMP_OUT\" 2>&1",
            "    rc=$?",
            "  elif [ -f \"./pom.xml\" ]; then",
            "    mvn -q -T1C -Dmaven.javadoc.skip=true \"$MAVEN_GOAL\" >\"$TMP_OUT\" 2>&1",
            "    rc=$?",
            "  else",
            "    log \"[pre-push] No supported build tool found. Skipping compilation check.\"",
            "    rm -f \"$TMP_OUT\" 2>/dev/null",
            "    return 0",
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
            "  rm -f \"$TMP_OUT\" 2>/dev/null",
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
            "    ERR*|*)",
            "      # IDE could not run the check (disposed, busy, etc.) - fall back.",
            "      return 2 ;;",
            "  esac",
            "}",
            "",
            "try_ide_compile",
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
            "fi",
            "",
            "# Fallback: run the project build tool.",
            "run_compilation",
            "EXIT_CODE=$?",
            "",
            "# Trailer line the IntelliJ side looks for to detect the final status.",
            "printf '[pre-push-checker] exit=%s\\n' \"$EXIT_CODE\" >> \"$LOG_FILE\" 2>/dev/null || true",
            "",
            "if [ \"$EXIT_CODE\" -ne 0 ]; then",
            "  log \"[pre-push] Compilation failed. Push aborted.\"",
            "  log \"[pre-push] Open IntelliJ -> View -> Tool Windows -> Compilation Checker for navigable errors.\"",
            "  exit 1",
            "fi",
            "",
            "log \"[pre-push] Compilation passed. Proceeding with push.\"",
            "exit 0",
            ""
        );
    }

    static String buildWrapperHookScript() {
        return String.join("\n",
            "#!/usr/bin/env sh",
            "# " + HOOK_MARKER,
            "SCRIPT_DIR=\"$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\"",
            "\"$SCRIPT_DIR/" + MANAGED_HOOK_NAME + "\" \"$@\"",
            ""
        );
    }

    static String buildDelegatingSnippet() {
        return String.join("\n",
            "",
            "# " + HOOK_MARKER,
            "SCRIPT_DIR=\"$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\"",
            "\"$SCRIPT_DIR/" + MANAGED_HOOK_NAME + "\" \"$@\" || exit $?",
            ""
        );
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
