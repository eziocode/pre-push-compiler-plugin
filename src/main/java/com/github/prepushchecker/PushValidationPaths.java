package com.github.prepushchecker;

import java.util.Locale;
import java.util.Set;

final class PushValidationPaths {
    private static final String[] SOURCE_EXTENSIONS = {".java", ".kt", ".groovy", ".scala"};
    private static final Set<String> BUILD_FILE_NAMES = Set.of(
        "pom.xml",
        "build.gradle",
        "build.gradle.kts",
        "settings.gradle",
        "settings.gradle.kts",
        "gradle.properties",
        "gradlew",
        "mvnw"
    );

    private PushValidationPaths() {
    }

    static boolean isRelevantPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String fileName = fileName(path);
        return BUILD_FILE_NAMES.contains(fileName) || hasSourceExtension(fileName);
    }

    static boolean isBuildFile(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        return BUILD_FILE_NAMES.contains(fileName(path));
    }

    static boolean isCompilableSource(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String fileName = fileName(path);
        return !BUILD_FILE_NAMES.contains(fileName) && hasSourceExtension(fileName);
    }

    static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private static boolean hasSourceExtension(String lowerFileName) {
        for (String ext : SOURCE_EXTENSIONS) {
            if (lowerFileName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static String fileName(String path) {
        String normalized = path.replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        String name = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
        return name.toLowerCase(Locale.ROOT);
    }
}
