package com.github.prepushchecker;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * List cell renderer that displays a file-type icon next to each compilation entry.
 *
 * <p>Understands two entry formats produced by {@link PrePushCompilationHandler}:
 * <ul>
 *   <li>{@code [src/Foo.java 10:5] cannot find symbol} — compilation error</li>
 *   <li>{@code src/Foo.java} — IDE problem file</li>
 * </ul>
 */
final class CompilationEntryRenderer extends DefaultListCellRenderer {

    // Cache icons by lower-cased extension to avoid hitting FileTypeManager on every render.
    private static final Map<String, Icon> ICON_CACHE = new ConcurrentHashMap<>();

    @Override
    public Component getListCellRendererComponent(
        JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus
    ) {
        JLabel label = (JLabel) super.getListCellRendererComponent(
            list, value, index, isSelected, cellHasFocus
        );
        String entry = value instanceof String ? (String) value : "";
        label.setIcon(iconForEntry(entry));
        label.setText(displayText(entry));
        label.setToolTipText(entry.isEmpty() ? null : entry);
        return label;
    }

    /** Builds a human-friendly label for the list: "FileName:line:col — message". */
    static String displayText(String entry) {
        if (entry == null || entry.isBlank()) return "";
        String path = extractPath(entry);
        String position = extractPosition(entry);
        String message = extractMessage(entry);

        String fileName = path != null ? lastSegment(path) : null;
        if (fileName == null || fileName.isEmpty()) {
            return entry;
        }

        StringBuilder sb = new StringBuilder(fileName.length() + 64);
        sb.append(fileName);
        if (position != null && !position.isEmpty()) {
            sb.append(':').append(position);
        }
        if (message != null && !message.isEmpty()) {
            sb.append("  —  ").append(message);
        }
        return sb.toString();
    }

    private static String lastSegment(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    /**
     * Extracts just the "line:col" (or "line") portion from a bracketed entry, or
     * {@code null} if no position was present.
     */
    @Nullable
    static String extractPosition(String entry) {
        if (entry == null || !entry.startsWith("[")) return null;
        int end = entry.indexOf(']');
        if (end < 0) return null;
        String inner = entry.substring(1, end).trim();

        if (inner.endsWith(")")) {
            int openParen = inner.lastIndexOf('(');
            if (openParen > 0) {
                String body = inner.substring(openParen + 1, inner.length() - 1).trim();
                String compact = body.replace(" ", "");
                if (isLinePrefix(compact)) {
                    return compact.replace(',', ':');
                }
            }
        }

        int space = inner.lastIndexOf(' ');
        if (space >= 0) {
            String suffix = inner.substring(space + 1);
            if (isLinePrefix(suffix)) return suffix;
        }
        return null;
    }

    /** Extracts the message portion after the bracketed header, trimmed. */
    @Nullable
    static String extractMessage(String entry) {
        if (entry == null || !entry.startsWith("[")) return null;
        int end = entry.indexOf(']');
        if (end < 0 || end + 1 >= entry.length()) return null;
        return entry.substring(end + 1).trim();
    }

    static Icon iconForEntry(String entry) {
        String path = extractPath(entry);
        if (path == null || path.isBlank()) {
            return AllIcons.General.Error;
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return AllIcons.General.Error;
        }
        String ext = path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        Icon cached = ICON_CACHE.get(ext);
        if (cached != null) {
            return cached;
        }
        Icon typeIcon = FileTypeManager.getInstance().getFileTypeByFileName(path).getIcon();
        Icon resolved = typeIcon != null ? typeIcon : AllIcons.General.Error;
        ICON_CACHE.put(ext, resolved);
        return resolved;
    }

    /**
     * Extracts the file path from a compilation entry string.
     *
     * <ul>
     *   <li>{@code [src/Foo.java 10:5] msg} → {@code src/Foo.java}</li>
     *   <li>{@code [path with spaces/Foo.java 10:5] msg} → {@code path with spaces/Foo.java}</li>
     *   <li>{@code src/Foo.java}             → {@code src/Foo.java}</li>
     * </ul>
     */
    @Nullable
    static String extractPath(String entry) {
        if (entry == null) return null;
        if (entry.startsWith("[")) {
            int end = entry.indexOf(']');
            if (end < 0) return null;
            String inner = entry.substring(1, end).trim();

            // Strip a trailing "(line, col)" group, e.g. "src/Foo.java (10, 5)".
            if (inner.endsWith(")")) {
                int openParen = inner.lastIndexOf('(');
                if (openParen > 0) {
                    String parenBody = inner.substring(openParen + 1, inner.length() - 1);
                    if (isLinePrefix(parenBody.replace(" ", ""))) {
                        return inner.substring(0, openParen).trim();
                    }
                }
            }

            // Fallback: trailing "line:col" after the last space, e.g. "src/Foo.java 10:5".
            int space = inner.lastIndexOf(' ');
            if (space < 0) return inner;
            String suffix = inner.substring(space + 1);
            if (isLinePrefix(suffix)) {
                return inner.substring(0, space);
            }
            return inner;
        }
        return entry.isBlank() ? null : entry;
    }

    private static boolean isLinePrefix(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isDigit(c) || c == ':' || c == '-' || c == ',')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Opens the file referenced by {@code entry} in the editor.
     * Tries the path as-is (absolute), then relative to the project base.
     */
    static void navigateTo(@NotNull Project project, @Nullable String entry) {
        if (entry == null) return;
        String path = extractPath(entry);
        if (path == null) return;

        VirtualFile file = findFile(path);
        if (file == null && project.getBasePath() != null) {
            file = findFile(project.getBasePath() + "/" + path);
        }
        if (file != null) {
            new OpenFileDescriptor(project, file).navigate(true);
        }
    }

    @Nullable
    private static VirtualFile findFile(String path) {
        return LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(path));
    }
}
