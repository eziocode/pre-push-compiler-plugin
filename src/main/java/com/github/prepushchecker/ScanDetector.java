package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Suggests {@code scan} locations: zips that hold jars (like {@code TP_COMPONENTS.zip}) and folders
 * of jars IntelliJ does not have on its classpath. Build output and hidden folders are skipped.
 */
final class ScanDetector {
    /** A pattern to add as a {@code scan} line, and why it was suggested. */
    record Suggestion(@NotNull String pattern, @NotNull String reason) {
    }

    private static final int MAX_DEPTH = 8;
    private static final int MAX_SUGGESTIONS = 200;
    private static final Set<String> SKIPPED = Set.of("out", "build", "target", "node_modules");

    private ScanDetector() {
    }

    static @NotNull List<Suggestion> detect(@NotNull Path repositoryRoot, @NotNull Set<String> knownJars) {
        Map<String, Integer> zips = new TreeMap<>();
        Map<String, Integer> jarDirs = new TreeMap<>();
        try {
            Files.walkFileTree(repositoryRoot, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (dir.equals(repositoryRoot)) return FileVisitResult.CONTINUE;
                        String name = dir.getFileName().toString();
                        return name.startsWith(".") || SKIPPED.contains(name)
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String relative = repositoryRoot.relativize(file).toString().replace('\\', '/');
                        if (ScanJars.isZip(relative)) {
                            int jars = countJars(file);
                            if (jars > 0) zips.put(relative, jars);
                        } else if (LayerJars.isJar(relative) && !knownJars.contains(relative)) {
                            int slash = relative.lastIndexOf('/');
                            jarDirs.merge(slash < 0 ? "" : relative.substring(0, slash), 1, Integer::sum);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                });
        } catch (IOException ignored) {
            // Partial results are still useful suggestions.
        }

        List<Suggestion> suggestions = new ArrayList<>();
        zips.forEach((zip, jars) -> suggestions.add(new Suggestion(zip, "zip with " + jars + " jars")));
        Map<String, Integer> topDirs = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : jarDirs.entrySet()) {
            String ancestor = null;
            for (String top : topDirs.keySet()) {
                if (top.isEmpty() || entry.getKey().startsWith(top + "/")) ancestor = top;
            }
            topDirs.merge(ancestor == null ? entry.getKey() : ancestor, entry.getValue(), Integer::sum);
        }
        topDirs.forEach((dir, jars) -> suggestions.add(new Suggestion(dir.isEmpty() ? "*.jar" : dir + "/**",
            jars + " jars not on IntelliJ's classpath")));
        suggestions.sort(java.util.Comparator.comparing(Suggestion::pattern));
        return suggestions.size() > MAX_SUGGESTIONS ? List.copyOf(suggestions.subList(0, MAX_SUGGESTIONS)) : suggestions;
    }

    private static int countJars(Path zipFile) {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            int jars = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && LayerJars.isJar(entry.getName())) jars++;
            }
            return jars;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }
}
