package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Expands {@code lib} patterns (repo-relative Ant globs) to jar files on disk. */
final class LayerJars {
    /** A jar and the first layer whose classpath contains it. */
    record Jar(@NotNull String relativePath, @NotNull Path path, int layerIndex) {
    }

    private LayerJars() {
    }

    /** Every jar matched by any layer's {@code lib} patterns, assigned to its earliest layer. */
    static @NotNull List<Jar> resolve(@NotNull Path repositoryRoot, @NotNull LayerRules rules) {
        Map<String, Jar> jars = new LinkedHashMap<>();
        for (LayerRules.Layer layer : rules.layers()) {
            List<String> patterns = new ArrayList<>();
            layer.libs().forEach(p -> patterns.add(p.source()));
            for (String relative : expand(repositoryRoot, patterns)) {
                jars.putIfAbsent(relative, new Jar(relative, repositoryRoot.resolve(relative), layer.index()));
            }
        }
        return List.copyOf(jars.values());
    }

    /** Repo-relative paths of {@code .jar} files matching any pattern; patterns match the relative path. */
    static @NotNull Set<String> expand(@NotNull Path repositoryRoot, @NotNull List<String> patterns) {
        Set<String> found = new LinkedHashSet<>();
        for (String raw : patterns) {
            String pattern = raw.trim().replace('\\', '/');
            while (pattern.startsWith("./")) pattern = pattern.substring(2);
            if (pattern.isEmpty()) continue;
            AntPathPattern compiled = AntPathPattern.compile(pattern);

            String[] segments = pattern.split("/");
            int fixed = 0;
            while (fixed < segments.length && !hasWildcard(segments[fixed])) fixed++;
            if (fixed == segments.length) {
                if (isJar(pattern) && Files.isRegularFile(repositoryRoot.resolve(pattern))) found.add(pattern);
                continue;
            }
            String prefix = String.join("/", java.util.Arrays.copyOfRange(segments, 0, fixed));
            boolean deep = pattern.contains("**");
            int depth = deep ? Integer.MAX_VALUE : segments.length - fixed;
            Path base = prefix.isEmpty() ? repositoryRoot : repositoryRoot.resolve(prefix);
            if (!Files.isDirectory(base)) continue;
            try (Stream<Path> walk = Files.walk(base, depth)) {
                walk.filter(Files::isRegularFile).forEach(file -> {
                    String relative = repositoryRoot.relativize(file).toString().replace('\\', '/');
                    if (isJar(relative) && compiled.matches(relative)) found.add(relative);
                });
            } catch (IOException | RuntimeException ignored) {
                // Unreadable directory: its jars stay unknown rather than failing the push check.
            }
        }
        return found;
    }

    private static boolean hasWildcard(String segment) {
        return segment.indexOf('*') >= 0 || segment.indexOf('?') >= 0;
    }

    private static boolean isJar(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".jar");
    }
}
