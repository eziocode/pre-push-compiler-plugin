package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered compile layers read from {@code .prepush-layers}. Layers are listed in build order;
 * a source file may import only from its own layer or an earlier one, mirroring Ant targets
 * where a later {@code <javac>} sees earlier output but not the other way round.
 * All paths are relative to the repository root.
 */
final class LayerRules {
    static final String FILE_NAME = ".prepush-layers";
    /** Layer index of a jar known only through a {@code scan} line, not any layer's {@code lib}. */
    static final int UNLISTED_LAYER = Integer.MAX_VALUE;

    record Layer(
        @NotNull String name,
        int index,
        @NotNull List<AntPathPattern> includes,
        @NotNull List<AntPathPattern> excludes,
        @NotNull List<AntPathPattern> libs,
        @NotNull List<AntPathPattern> forbids
    ) {
        Layer {
            includes = List.copyOf(includes);
            excludes = List.copyOf(excludes);
            libs = List.copyOf(libs);
            forbids = List.copyOf(forbids);
        }

        /** {@code true} when a {@code forbid} line matches the jar's display path. */
        boolean isForbidden(@NotNull String jarDisplayPath) {
            for (AntPathPattern forbid : forbids) {
                if (forbid.matches(jarDisplayPath)) return true;
            }
            return false;
        }

        boolean contains(@NotNull String relativePath) {
            for (AntPathPattern exclude : excludes) {
                if (exclude.matches(relativePath)) return false;
            }
            for (AntPathPattern include : includes) {
                if (include.matches(relativePath)) return true;
            }
            return false;
        }
    }

    private final List<String> roots;
    private final List<AntPathPattern> scans;
    private final List<Layer> layers;

    LayerRules(@NotNull List<String> roots, @NotNull List<AntPathPattern> scans, @NotNull List<Layer> layers) {
        this.roots = List.copyOf(roots);
        this.scans = List.copyOf(scans);
        this.layers = List.copyOf(layers);
    }

    @NotNull List<String> roots() {
        return roots;
    }

    /** Where jars may be found besides {@code lib} lines; every scanned jar is allowed unless forbidden. */
    @NotNull List<AntPathPattern> scans() {
        return scans;
    }

    @NotNull List<Layer> layers() {
        return layers;
    }

    /** First layer whose includes match and excludes don't; {@code null} when unassigned. */
    @Nullable Layer layerOf(@NotNull String relativePath) {
        String path = relativePath.replace('\\', '/');
        for (Layer layer : layers) {
            if (layer.contains(path)) return layer;
        }
        return null;
    }

    /** Deepest source root containing {@code relativePath}, or {@code null}. */
    @Nullable String rootOf(@NotNull String relativePath) {
        String path = relativePath.replace('\\', '/');
        String best = null;
        for (String root : roots) {
            if ((root.isEmpty() || path.startsWith(root + "/"))
                    && (best == null || root.length() > best.length())) {
                best = root;
            }
        }
        return best;
    }

    /** {@code true} when any layer lists jars, which turns on jar classpath enforcement. */
    boolean hasLibs() {
        for (Layer layer : layers) {
            if (!layer.libs().isEmpty()) return true;
        }
        return false;
    }

    /** {@code true} when any jar rule ({@code lib}, {@code forbid} or {@code scan}) is present. */
    boolean hasJarRules() {
        if (hasLibs() || !scans.isEmpty()) return true;
        for (Layer layer : layers) {
            if (!layer.forbids().isEmpty()) return true;
        }
        return false;
    }

    /** {@code true} when some layer after {@code layer} exists, so its files can be violated. */
    boolean hasLaterLayer(@NotNull Layer layer) {
        return layer.index() < layers.size() - 1;
    }

    /** Only the repository-root rules file is read, so only it counts. */
    static boolean isRulesFile(@NotNull String repositoryRelativePath) {
        String normalized = repositoryRelativePath.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return FILE_NAME.equals(normalized);
    }

    static @NotNull List<String> normalizeRoots(@NotNull List<String> roots) {
        List<String> result = new ArrayList<>();
        for (String root : roots) {
            String value = root.trim().replace('\\', '/');
            while (value.startsWith("./")) value = value.substring(2);
            while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            if (value.equals(".")) value = "";
            if (!result.contains(value)) result.add(value);
        }
        return result;
    }
}
