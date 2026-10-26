package com.github.prepushchecker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Temp-dir helpers shared by the layer tests. */
final class TestFiles {
    private TestFiles() {
    }

    static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** A jar whose entries are empty {@code .class} files named by the given internal paths. */
    static void jar(Path root, String relative, String... classEntries) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out)) {
            for (String entry : classEntries) {
                jar.putNextEntry(new ZipEntry(entry));
                jar.write(new byte[]{(byte) 0xCA, (byte) 0xFE});
                jar.closeEntry();
            }
        }
    }

    /** Bytes of a jar whose entries are empty {@code .class} files, for nesting inside a zip. */
    static byte[] jarBytes(String... classEntries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            for (String entry : classEntries) {
                jar.putNextEntry(new ZipEntry(entry));
                jar.write(new byte[]{(byte) 0xCA, (byte) 0xFE});
                jar.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** A zip with the given entries, written in name order. */
    static void zip(Path root, String relative, Map<String, byte[]> entries) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : new java.util.TreeSet<>(entries.keySet())) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(entries.get(name));
                zip.closeEntry();
            }
        }
    }

    static void delete(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
