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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
    // Maven (surefire/failsafe skipped; javac via maven prints similar to javac).
    private static final Pattern JAVAC_PATTERN =
        Pattern.compile("^(?<path>[^\\s:][^:]*\\.(?:java|kt|groovy|scala)):(?<line>\\d+)(?::(?<col>\\d+))?:\\s*(?:error|warning):?\\s*(?<msg>.+)$");
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

            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            Integer exitCode = readExitCode(lines);
            if (exitCode == null || exitCode == 0) {
                return;
            }

            List<String> errors = parseErrors(project, lines);
            if (errors.isEmpty()) {
                errors = Collections.singletonList(
                    "External pre-push check failed (exit " + exitCode + "). See "
                        + GitHookInstaller.EXTERNAL_LOG_RELATIVE_PATH + " for full output.");
            }
            CompilationErrorService.getInstance(project).setErrors(errors);
            notifyUser(project, errors.size());
        } catch (IOException e) {
            LOG.warn("Failed to read pre-push hook log at " + logFile, e);
        }
    }

    @Nullable
    private static Integer readExitCode(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i).trim();
            if (line.startsWith(EXIT_TRAILER)) {
                try {
                    return Integer.parseInt(line.substring(EXIT_TRAILER.length()).trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    static List<String> parseErrors(@NotNull Project project, @NotNull List<String> lines) {
        String basePath = project.getBasePath();
        Set<String> seen = new LinkedHashSet<>();
        List<String> formatted = new ArrayList<>();

        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) continue;

            Matcher m = KOTLIN_PATTERN.matcher(line);
            if (!m.matches()) {
                m = JAVAC_PATTERN.matcher(line);
                if (!m.matches()) continue;
            }

            String path = m.group("path");
            String lineNo = m.group("line");
            String col = safeGroup(m, "col");
            String msg = m.group("msg").trim();

            String relativePath = toProjectRelative(basePath, path);
            String position = col != null && !col.isEmpty() ? lineNo + ":" + col : lineNo;
            String entry = "[" + relativePath + " (" + position.replace(":", ", ") + ")] " + msg;

            if (seen.add(entry)) {
                formatted.add(entry);
            }
        }
        return formatted;
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
