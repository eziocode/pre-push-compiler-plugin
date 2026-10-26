package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Answers "which scanned jars contain this class or package" for jars from {@link ScanJars}.
 * Every hit is {@link LayerRules#UNLISTED_LAYER}: a scanned jar belongs to no layer's classpath.
 * Like {@link JarClassIndex}, only package sets are cached for every jar (file, size, mtime, entry);
 * a class lookup reads entries only of jars holding its package. A jar inside a zip has to be
 * streamed to list it, so the class sets of a few such jars are kept too.
 */
final class ScanJarIndex implements CrossLayerImportCheck.JarLookup, Closeable {
    private static final int MAX_CACHED_PACKAGE_SETS = 4000;
    private static final int MAX_CACHED_NESTED_CLASS_SETS = 64;
    private static final Map<String, Set<String>> PACKAGES_BY_JAR = lruCache(MAX_CACHED_PACKAGE_SETS);
    private static final Map<String, Set<String>> CLASSES_BY_NESTED_JAR = lruCache(MAX_CACHED_NESTED_CLASS_SETS);

    private final List<ScanJars.ScannedJar> jars;
    private final Map<ScanJars.ScannedJar, String> keys = new HashMap<>();
    private final Map<Path, ZipFile> openZips = new HashMap<>();

    ScanJarIndex(@NotNull List<ScanJars.ScannedJar> jars) {
        this.jars = List.copyOf(jars);
    }

    /** A thread-safe map that drops the least recently used entry beyond {@code capacity}. */
    static <K, V> @NotNull Map<K, V> lruCache(int capacity) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity;
            }
        });
    }

    @Override
    public @NotNull List<CrossLayerImportCheck.JarHit> classHits(@NotNull String fqn) {
        int dot = fqn.lastIndexOf('.');
        String pkg = dot < 0 ? "" : fqn.substring(0, dot);
        String entry = fqn.replace('.', '/') + ".class";
        List<CrossLayerImportCheck.JarHit> hits = new ArrayList<>();
        for (ScanJars.ScannedJar jar : jars) {
            if (!packagesOf(jar).contains(pkg)) continue;
            if (hasClass(jar, entry)) hits.add(hit(jar));
        }
        return hits;
    }

    @Override
    public @NotNull List<CrossLayerImportCheck.JarHit> packageHits(@NotNull String pkg) {
        List<CrossLayerImportCheck.JarHit> hits = new ArrayList<>();
        for (ScanJars.ScannedJar jar : jars) {
            if (packagesOf(jar).contains(pkg)) hits.add(hit(jar));
        }
        return hits;
    }

    private static CrossLayerImportCheck.JarHit hit(ScanJars.ScannedJar jar) {
        return new CrossLayerImportCheck.JarHit(jar.displayPath(), LayerRules.UNLISTED_LAYER);
    }

    private @Nullable String keyOf(ScanJars.ScannedJar jar) {
        return keys.computeIfAbsent(jar, j -> {
            try {
                return j.file() + "|" + Files.size(j.file()) + "|" + Files.getLastModifiedTime(j.file()).toMillis()
                    + "|" + j.entry();
            } catch (IOException e) {
                return null;
            }
        });
    }

    private Set<String> packagesOf(ScanJars.ScannedJar jar) {
        String key = keyOf(jar);
        if (key == null) return Set.of();
        Set<String> cached = PACKAGES_BY_JAR.get(key);
        if (cached != null) return cached;
        Set<String> classes = readClasses(jar);
        Set<String> packages = new HashSet<>();
        for (String name : classes) {
            int slash = name.lastIndexOf('/');
            packages.add(slash < 0 ? "" : name.substring(0, slash).replace('/', '.'));
        }
        Set<String> result = Set.copyOf(packages);
        PACKAGES_BY_JAR.put(key, result);
        if (!jar.onDisk()) CLASSES_BY_NESTED_JAR.put(key, classes);
        return result;
    }

    private boolean hasClass(ScanJars.ScannedJar jar, String entry) {
        if (jar.onDisk()) {
            ZipFile zip = zipOf(jar.file());
            return zip != null && zip.getEntry(entry) != null;
        }
        String key = keyOf(jar);
        if (key == null) return false;
        Set<String> classes = CLASSES_BY_NESTED_JAR.get(key);
        if (classes == null) {
            classes = readClasses(jar);
            CLASSES_BY_NESTED_JAR.put(key, classes);
        }
        return classes.contains(entry);
    }

    /** Class entry names ({@code a/b/C.class}); empty when the jar cannot be read. */
    private Set<String> readClasses(ScanJars.ScannedJar jar) {
        ZipFile zip = zipOf(jar.file());
        if (zip == null) return Set.of();
        Set<String> classes = new HashSet<>();
        try {
            if (jar.onDisk()) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) addClass(classes, entries.nextElement().getName());
            } else {
                ZipEntry nested = zip.getEntry(jar.entry());
                if (nested == null) return Set.of();
                try (InputStream in = zip.getInputStream(nested); ZipInputStream jarStream = new ZipInputStream(in)) {
                    for (ZipEntry entry = jarStream.getNextEntry(); entry != null; entry = jarStream.getNextEntry()) {
                        addClass(classes, entry.getName());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return Set.of();
        }
        return Set.copyOf(classes);
    }

    private static void addClass(Set<String> classes, String name) {
        if (name.endsWith(".class") && !name.startsWith("META-INF/")) classes.add(name);
    }

    private @Nullable ZipFile zipOf(Path file) {
        if (openZips.containsKey(file)) return openZips.get(file);
        ZipFile zip;
        try {
            zip = new ZipFile(file.toFile());
        } catch (IOException e) {
            zip = null;
        }
        openZips.put(file, zip);
        return zip;
    }

    @Override
    public void close() {
        for (ZipFile zip : openZips.values()) {
            if (zip == null) continue;
            try {
                zip.close();
            } catch (IOException ignored) {
                // Nothing to recover; the handle goes away with the process.
            }
        }
        openZips.clear();
    }
}
