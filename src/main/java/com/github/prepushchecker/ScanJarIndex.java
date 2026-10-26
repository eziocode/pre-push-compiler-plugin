package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.zip.ZipInputStream;

/**
 * Answers "which scanned jars contain this class or package" for jars from {@link ScanJars}.
 * Every hit is {@link LayerRules#UNLISTED_LAYER}: a scanned jar belongs to no layer's classpath.
 * Class names are cached per jar (file, size, mtime, entry), so repeated pushes skip unchanged zips.
 */
final class ScanJarIndex implements CrossLayerImportCheck.JarLookup, Closeable {
    private record Contents(@NotNull Set<String> classEntries, @NotNull Set<String> packages) {
        static final Contents EMPTY = new Contents(Set.of(), Set.of());
    }

    private static final Map<String, Contents> CONTENTS_BY_JAR = new ConcurrentHashMap<>();
    private static final int MAX_CACHED_JARS = 4000;

    private final List<ScanJars.ScannedJar> jars;
    private final Map<ScanJars.ScannedJar, Contents> contents = new HashMap<>();
    private final Map<Path, ZipFile> openZips = new HashMap<>();

    ScanJarIndex(@NotNull List<ScanJars.ScannedJar> jars) {
        this.jars = List.copyOf(jars);
    }

    @Override
    public @NotNull List<CrossLayerImportCheck.JarHit> classHits(@NotNull String fqn) {
        String entry = fqn.replace('.', '/') + ".class";
        List<CrossLayerImportCheck.JarHit> hits = new ArrayList<>();
        for (ScanJars.ScannedJar jar : jars) {
            if (contentsOf(jar).classEntries().contains(entry)) hits.add(hit(jar));
        }
        return hits;
    }

    @Override
    public @NotNull List<CrossLayerImportCheck.JarHit> packageHits(@NotNull String pkg) {
        List<CrossLayerImportCheck.JarHit> hits = new ArrayList<>();
        for (ScanJars.ScannedJar jar : jars) {
            if (contentsOf(jar).packages().contains(pkg)) hits.add(hit(jar));
        }
        return hits;
    }

    private static CrossLayerImportCheck.JarHit hit(ScanJars.ScannedJar jar) {
        return new CrossLayerImportCheck.JarHit(jar.displayPath(), LayerRules.UNLISTED_LAYER);
    }

    private Contents contentsOf(ScanJars.ScannedJar jar) {
        return contents.computeIfAbsent(jar, j -> {
            String key;
            try {
                key = j.file() + "|" + Files.size(j.file()) + "|" + Files.getLastModifiedTime(j.file()).toMillis()
                    + "|" + j.entry();
            } catch (IOException e) {
                return Contents.EMPTY;
            }
            Contents cached = CONTENTS_BY_JAR.get(key);
            if (cached != null) return cached;
            Contents computed = read(j);
            if (CONTENTS_BY_JAR.size() >= MAX_CACHED_JARS) CONTENTS_BY_JAR.clear();
            CONTENTS_BY_JAR.put(key, computed);
            return computed;
        });
    }

    private Contents read(ScanJars.ScannedJar jar) {
        ZipFile zip = zipOf(jar.file());
        if (zip == null) return Contents.EMPTY;
        Set<String> classes = new HashSet<>();
        try {
            if (jar.onDisk()) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) addClass(classes, entries.nextElement().getName());
            } else {
                ZipEntry nested = zip.getEntry(jar.entry());
                if (nested == null) return Contents.EMPTY;
                try (InputStream in = zip.getInputStream(nested); ZipInputStream jarStream = new ZipInputStream(in)) {
                    for (ZipEntry entry = jarStream.getNextEntry(); entry != null; entry = jarStream.getNextEntry()) {
                        addClass(classes, entry.getName());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return Contents.EMPTY;
        }
        Set<String> packages = new HashSet<>();
        for (String name : classes) {
            int slash = name.lastIndexOf('/');
            packages.add(slash < 0 ? "" : name.substring(0, slash).replace('/', '.'));
        }
        return new Contents(Set.copyOf(classes), Set.copyOf(packages));
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
