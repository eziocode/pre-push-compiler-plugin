package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Expands {@code scan} patterns (repo-relative Ant globs) to jars: {@code .jar} files on disk,
 * and the {@code .jar} entries of matched {@code .zip} files, shown as {@code zip!/entry}.
 * Only one level of nesting is read: a jar inside a zip inside a zip is not listed.
 */
final class ScanJars {
    /** A scanned jar: on disk when {@code entry} is {@code null}, else that entry of the zip {@code file}. */
    record ScannedJar(@NotNull String displayPath, @NotNull Path file, @Nullable String entry) {
        boolean onDisk() {
            return entry == null;
        }
    }

    static final String NESTED_SEPARATOR = "!/";

    private ScanJars() {
    }

    /** Every jar the patterns reach, once per display path, in pattern order. */
    static @NotNull List<ScannedJar> resolve(@NotNull Path repositoryRoot, @NotNull List<String> patterns) {
        Map<String, ScannedJar> jars = new LinkedHashMap<>();
        for (String relative : LayerJars.walk(repositoryRoot, patterns, path -> LayerJars.isJar(path) || isZip(path))) {
            Path file = repositoryRoot.resolve(relative);
            if (LayerJars.isJar(relative)) {
                jars.putIfAbsent(relative, new ScannedJar(relative, file, null));
                continue;
            }
            try (ZipFile zip = new ZipFile(file.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !LayerJars.isJar(entry.getName())) continue;
                    String display = relative + NESTED_SEPARATOR + entry.getName();
                    jars.putIfAbsent(display, new ScannedJar(display, file, entry.getName()));
                }
            } catch (IOException | RuntimeException ignored) {
                // Unreadable zip: its jars stay unknown rather than failing the push check.
            }
        }
        return List.copyOf(jars.values());
    }

    static boolean isZip(@NotNull String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".zip");
    }
}
