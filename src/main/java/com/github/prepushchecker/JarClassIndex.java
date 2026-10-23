package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Answers "which known jars contain this class or package". Package sets are cached per jar
 * (path, size, mtime) so repeated pushes only re-read changed jars; a class lookup opens only
 * the few jars that contain its package.
 */
final class JarClassIndex implements CrossLayerImportCheck.JarLookup, Closeable {
    private static final Map<String, Set<String>> PACKAGES_BY_JAR = new ConcurrentHashMap<>();
    private static final int MAX_CACHED_JARS = 4000;

    private final List<LayerJars.Jar> jars;
    private final Map<LayerJars.Jar, Set<String>> packages = new HashMap<>();
    private final Map<LayerJars.Jar, ZipFile> open = new HashMap<>();

    JarClassIndex(@NotNull List<LayerJars.Jar> jars) {
        this.jars = List.copyOf(jars);
    }

    @Override
    public @NotNull List<CrossLayerImportCheck.JarHit> classHits(@NotNull String fqn) {
        int dot = fqn.lastIndexOf('.');
        String pkg = dot < 0 ? "" : fqn.substring(0, dot);
        String entry = fqn.replace('.', '/') + ".class";
        List<CrossLayerImportCheck.JarHit> hits = new ArrayList<>();
        for (LayerJars.Jar jar : jars) {
            if (!packagesOf(jar).contains(pkg)) continue;
            ZipFile zip = zipOf(jar);
            if (zip != null && zip.getEntry(entry) != null) hits.add(hit(jar));
        }
        return hits;
    }

    @Override
    public @NotNull List<CrossLayerImportCheck.JarHit> packageHits(@NotNull String pkg) {
        List<CrossLayerImportCheck.JarHit> hits = new ArrayList<>();
        for (LayerJars.Jar jar : jars) {
            if (packagesOf(jar).contains(pkg)) hits.add(hit(jar));
        }
        return hits;
    }

    private static CrossLayerImportCheck.JarHit hit(LayerJars.Jar jar) {
        return new CrossLayerImportCheck.JarHit(jar.relativePath(), jar.layerIndex());
    }

    private Set<String> packagesOf(LayerJars.Jar jar) {
        return packages.computeIfAbsent(jar, j -> {
            String key;
            try {
                key = j.path() + "|" + Files.size(j.path()) + "|" + Files.getLastModifiedTime(j.path()).toMillis();
            } catch (IOException e) {
                return Set.of();
            }
            Set<String> cached = PACKAGES_BY_JAR.get(key);
            if (cached != null) return cached;
            Set<String> computed = readPackages(j);
            if (PACKAGES_BY_JAR.size() >= MAX_CACHED_JARS) PACKAGES_BY_JAR.clear();
            PACKAGES_BY_JAR.put(key, computed);
            return computed;
        });
    }

    private Set<String> readPackages(LayerJars.Jar jar) {
        ZipFile zip = zipOf(jar);
        if (zip == null) return Set.of();
        Set<String> result = new HashSet<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
            int slash = name.lastIndexOf('/');
            result.add(slash < 0 ? "" : name.substring(0, slash).replace('/', '.'));
        }
        return result;
    }

    private ZipFile zipOf(LayerJars.Jar jar) {
        ZipFile zip = open.get(jar);
        if (zip == null && !open.containsKey(jar)) {
            try {
                zip = new ZipFile(jar.path().toFile());
            } catch (IOException e) {
                zip = null;
            }
            open.put(jar, zip);
        }
        return zip;
    }

    @Override
    public void close() {
        for (ZipFile zip : open.values()) {
            if (zip == null) continue;
            try {
                zip.close();
            } catch (IOException ignored) {
                // Nothing to recover; the handle goes away with the process.
            }
        }
        open.clear();
    }
}
