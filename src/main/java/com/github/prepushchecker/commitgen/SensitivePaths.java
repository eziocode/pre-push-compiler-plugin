package com.github.prepushchecker.commitgen;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Files never sent to an AI provider: the plugin's own state (its {@code server.port} holds the
 * hook's auth token), IDE workspace files, and files that usually hold secrets.
 */
public final class SensitivePaths {

    private static final Pattern DIFF_HEADER = Pattern.compile("^diff --git a/(.+?) b/(.+)$", Pattern.MULTILINE);

    private SensitivePaths() {}

    /** {@code true} when {@code path} (relative or absolute, any separator) must not reach the AI. */
    public static boolean isExcluded(@NotNull String path) {
        String p = ("/" + path.replace('\\', '/')).replaceAll("/{2,}", "/");
        String lower = p.toLowerCase(Locale.ROOT);
        if (lower.contains("/.idea/pre-push-checker/") || lower.endsWith("/.idea/pre-push-checker")) return true;
        if (lower.contains("/.git/")) return true;
        if (lower.endsWith("/.idea/workspace.xml") || lower.contains("/.idea/shelf/")
            || lower.endsWith("/.idea/usage.statistics.xml") || lower.contains("/.idea/datasources")) {
            return true;
        }
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        return name.equals(".env") || name.startsWith(".env.")
            || name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12") || name.endsWith(".pfx")
            || name.endsWith(".jks") || name.endsWith(".keystore")
            || name.startsWith("id_rsa") || name.startsWith("id_ed25519") || name.startsWith("id_ecdsa")
            || name.equals(".netrc") || name.equals(".npmrc") || name.equals(".pypirc")
            || name.equals("credentials") || name.equals("credentials.json") || name.equals("secrets.json");
    }

    /** Removes the {@code diff --git} sections of excluded files from a unified diff. */
    public static @NotNull String dropExcluded(@NotNull String diff) {
        if (!diff.contains("diff --git ")) return diff;
        StringBuilder out = new StringBuilder();
        Matcher m = DIFF_HEADER.matcher(diff);
        int sectionStart = -1;
        boolean keep = true;
        int prefixEnd = -1;
        while (m.find()) {
            if (sectionStart < 0) {
                prefixEnd = m.start();
            } else if (keep) {
                out.append(diff, sectionStart, m.start());
            }
            sectionStart = m.start();
            keep = !isExcluded(m.group(1)) && !isExcluded(m.group(2));
        }
        if (sectionStart < 0) return diff;
        String prefix = diff.substring(0, prefixEnd);
        if (keep) out.append(diff, sectionStart, diff.length());
        return prefix + out;
    }
}
