package com.github.prepushchecker;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.vfs.AsyncFileListener;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads errors produced by the <em>external</em> pre-push hook (terminal / Sublime Merge /
 * SourceTree / GitHub Desktop / ...) into the IntelliJ tool window, so the same errors the
 * user saw in the terminal are immediately navigable in the IDE.
 *
 * <p>Reads the log written by {@link GitHookInstaller#EXTERNAL_LOG_RELATIVE_PATH}. The hook
 * appends a final {@code [pre-push-checker] exit=N} trailer; we only surface entries when
 * {@code N != 0}.
 */
public final class ExternalPushErrorLoader implements StartupActivity {

    private static final Logger LOG = Logger.getInstance(ExternalPushErrorLoader.class);
    private static final String NOTIFICATION_GROUP_ID = "Pre-Push Compilation Checker";
    private static final String EXIT_TRAILER = "[pre-push-checker] exit=";

    // Per-project "last parsed" fingerprint (size + mtime) to avoid re-parsing the same log
    // when the VFS fires multiple events for one write.
    private static final java.util.Map<String, long[]> LAST_PARSED =
        new java.util.concurrent.ConcurrentHashMap<>();

    // Gradle/javac: "/abs/or/rel/Path.java:123: error: message"
    // Gradle also emits:    "/abs/.../Path.java:123:45: error: message"
    // Kotlin compile:       "e: file:///.../Path.kt:123:45 message"
    // Maven compiler plugin: "[ERROR] /abs/.../Path.java:[123,45] cannot find symbol".
    private static final Pattern JAVAC_PATTERN =
        Pattern.compile("^(?<path>[^\\s:][^:]*\\.(?:java|kt|groovy|scala)):(?<line>\\d+)(?::(?<col>\\d+))?:\\s*(?:error|warning):?\\s*(?<msg>.+)$");
    private static final Pattern MAVEN_COMPILER_PATTERN =
        Pattern.compile("^(?:\\[ERROR]\\s*)?(?<path>[^\\s].*?\\.(?:java|kt|groovy|scala)):\\[(?<line>\\d+),(?<col>\\d+)]\\s*(?<msg>.*)$");
    private static final Pattern KOTLIN_PATTERN =
        Pattern.compile("^e:\\s+(?:file://)?(?<path>[^:]+\\.(?:kt|kts)):(?<line>\\d+):(?<col>\\d+)\\s+(?<msg>.+)$");

    @Override
    public void runActivity(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return;
        }
        Path logFile = Path.of(basePath, GitHookInstaller.EXTERNAL_LOG_RELATIVE_PATH);

        // Initial load — covers the case where the user pushed externally before opening the IDE.
        loadFromLogIfFailed(project, logFile);

        // Listen for future hook runs while the IDE is open.
        VirtualFileManager.getInstance().addAsyncFileListener(
            new HookLogListener(project, logFile), project);
    }

    static void loadFromLogIfFailed(@NotNull Project project, @NotNull Path logFile) {
        if (project.isDisposed() || !Files.isRegularFile(logFile)) {
            return;
        }
        try {
            long size = Files.size(logFile);
            long mtime = Files.getLastModifiedTime(logFile).toMillis();
            String key = project.getLocationHash() + "::" + logFile.toAbsolutePath();
            long[] prev = LAST_PARSED.get(key);
            if (prev != null && prev[0] == size && prev[1] == mtime) {
                return; // identical snapshot -> nothing to do
            }
            LAST_PARSED.put(key, new long[] { size, mtime });

            HookLogParseResult parsedLog = parseHookLog(project, logFile);
            Integer exitCode = parsedLog.exitCode();
            if (exitCode == null || exitCode == 0) {
                return;
            }

            List<String> errors = parsedLog.errors();
            if (errors.isEmpty()) {
                errors = buildRawLogFallback(exitCode, parsedLog.rawLines());
            }
            CompilationErrorService.getInstance(project).setErrors(errors);
            notifyUser(project, errors.size());
        } catch (IOException e) {
            LOG.warn("Failed to read pre-push hook log at " + logFile, e);
        }
    }

    @Nullable
    private static Integer parseExitCode(@NotNull String line) {
        try {
            return Integer.parseInt(line.substring(EXIT_TRAILER.length()).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static HookLogParseResult parseHookLog(@NotNull Project project, @NotNull Path logFile)
        throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
            Integer exitCode = null;
            List<String> compilerLines = new ArrayList<>();

            String raw;
            while ((raw = reader.readLine()) != null) {
                String line = raw.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith(EXIT_TRAILER)) {
                    exitCode = parseExitCode(line);
                    continue;
                }

                compilerLines.add(line);
            }
            return new HookLogParseResult(exitCode, parseErrors(project, compilerLines), compilerLines);
        }
    }

    private static List<String> buildRawLogFallback(int exitCode, @NotNull List<String> rawLines) {
        List<String> result = new ArrayList<>();
        result.add("External pre-push check failed (exit " + exitCode + "). Hook output from "
            + GitHookInstaller.EXTERNAL_LOG_RELATIVE_PATH + ":");
        int limit = Math.min(rawLines.size(), CompilationErrorService.MAX_RETAINED_ERRORS - 1);
        for (int i = 0; i < limit; i++) {
            result.add(CompilationErrorService.compactError(rawLines.get(i)));
        }
        int omitted = rawLines.size() - limit;
        if (omitted > 0) {
            result.add(CompilationErrorService.omittedErrorsMessage(omitted));
        }
        return List.copyOf(result);
    }

    static List<String> parseErrors(@NotNull Project project, @NotNull List<String> lines) {
        String basePath = project.getBasePath();
        ErrorCollector collector = new ErrorCollector();
        String pending = null;
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) continue;
            String parsed = parseErrorLine(basePath, line);
            if (parsed != null) {
                collector.add(pending);
                pending = parsed;
                continue;
            }

            String detail = parseMavenDetail(line);
            if (pending != null && detail != null) {
                pending = pending + " | " + detail;
                continue;
            }

            collector.add(pending);
            pending = null;
        }
        collector.add(pending);
        return collector.toList();
    }

    @Nullable
    private static String parseErrorLine(@Nullable String basePath, @NotNull String line) {
        Matcher m = KOTLIN_PATTERN.matcher(line);
        if (!m.matches()) {
            m = MAVEN_COMPILER_PATTERN.matcher(line);
            if (!m.matches()) {
                m = JAVAC_PATTERN.matcher(line);
                if (!m.matches()) return null;
            }
        }

        String path = m.group("path");
        String lineNo = m.group("line");
        String col = safeGroup(m, "col");
        String msg = m.group("msg").trim();

        String relativePath = toProjectRelative(basePath, path);
        String position = col != null && !col.isEmpty() ? lineNo + ":" + col : lineNo;
        return "[" + relativePath + " (" + position.replace(":", ", ") + ")] " + msg;
    }

    @Nullable
    private static String parseMavenDetail(@NotNull String line) {
        String detail = line;
        if (detail.startsWith("[ERROR]")) {
            detail = detail.substring("[ERROR]".length()).trim();
        }
        return detail.startsWith("symbol:") || detail.startsWith("location:") ? detail : null;
    }

    private static final class ErrorCollector {
        private final Set<String> seen = new LinkedHashSet<>();
        private final List<String> formatted = new ArrayList<>();
        private int omitted;

        private void add(@Nullable String entry) {
            if (entry == null) return;
            if (formatted.size() >= CompilationErrorService.MAX_RETAINED_ERRORS) {
                omitted++;
                return;
            }
            if (seen.add(entry)) {
                formatted.add(CompilationErrorService.compactError(entry));
            }
        }

        private List<String> toList() {
            if (omitted == 0) {
                return List.copyOf(formatted);
            }
            List<String> result = new ArrayList<>(formatted.size() + 1);
            result.addAll(formatted);
            result.add(CompilationErrorService.omittedErrorsMessage(omitted));
            return List.copyOf(result);
        }
    }

    private static final class HookLogParseResult {
        private final Integer exitCode;
        private final List<String> errors;
        private final List<String> rawLines;

        private HookLogParseResult(@Nullable Integer exitCode, @NotNull List<String> errors,
                                   @NotNull List<String> rawLines) {
            this.exitCode = exitCode;
            this.errors = errors;
            this.rawLines = rawLines;
        }

        private @Nullable Integer exitCode() {
            return exitCode;
        }

        private @NotNull List<String> errors() {
            return errors;
        }

        private @NotNull List<String> rawLines() {
            return rawLines;
        }
    }

    @Nullable
    private static String safeGroup(Matcher m, String name) {
        try {
            return m.group(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String toProjectRelative(@Nullable String basePath, String path) {
        if (basePath == null) return path;
        String normalizedBase = basePath.replace('\\', '/');
        String normalizedPath = path.replace('\\', '/');
        if (normalizedPath.startsWith(normalizedBase + "/")) {
            return normalizedPath.substring(normalizedBase.length() + 1);
        }
        if (normalizedPath.startsWith("/private" + normalizedBase + "/")) {
            return normalizedPath.substring(("/private" + normalizedBase).length() + 1);
        }
        if (normalizedBase.startsWith("/private/")) {
            String publicBase = normalizedBase.substring("/private".length());
            if (normalizedPath.startsWith(publicBase + "/")) {
                return normalizedPath.substring(publicBase.length() + 1);
            }
        }
        return normalizedPath;
    }

    private static void notifyUser(@NotNull Project project, int errorCount) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(
                    "External push blocked by Pre-Push Compilation Checker",
                    errorCount + " compilation error(s) detected while pushing from an external git client. "
                        + "Open the Compilation Checker tool window to see and navigate them.",
                    NotificationType.WARNING
                );
            notification.addAction(new NotificationAction("Open Compilation Checker") {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification n) {
                    ToolWindow tw = ToolWindowManager.getInstance(project)
                        .getToolWindow("Compilation Checker");
                    if (tw != null) {
                        tw.show(null);
                    }
                    n.expire();
                }
            });
            notification.notify(project);
        });
    }

    private static final class HookLogListener implements AsyncFileListener {
        private final Project project;
        private final String watchedPath;

        HookLogListener(@NotNull Project project, @NotNull Path logFile) {
            this.project = project;
            this.watchedPath = logFile.toAbsolutePath().normalize().toString().replace('\\', '/');
        }

        @Nullable
        @Override
        public ChangeApplier prepareChange(@NotNull List<? extends VFileEvent> events) {
            boolean hit = false;
            for (VFileEvent event : events) {
                String eventPath = event.getPath();
                if (eventPath != null && eventPath.replace('\\', '/').equals(watchedPath)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) return null;

            return new ChangeApplier() {
                @Override
                public void afterVfsChange() {
                    if (project.isDisposed()) return;
                    ApplicationManager.getApplication().executeOnPooledThread(() ->
                        loadFromLogIfFailed(project, Path.of(watchedPath)));
                }
            };
        }
    }
}
