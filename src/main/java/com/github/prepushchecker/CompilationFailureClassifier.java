package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CompilationFailureClassifier {
    private static final int CLASSPATH_FAILURE_MIN_ERRORS = 4;
    private static final int CLASSPATH_FAILURE_MIN_FILES = 3;
    private static final Pattern RAW_COMPILER_PATH = Pattern.compile(
        "(?:\\[ERROR]\\s+)?(.+?\\.(?:java|kt|kts|groovy|scala))"
            + "(?::\\[\\d+,\\d+]|:\\d+(?::\\d+)?)");

    private CompilationFailureClassifier() {
    }

    static @NotNull RecoveryDecision classify(@NotNull List<String> errors) {
        if (errors.isEmpty()) {
            return RecoveryDecision.noRecovery();
        }

        int classpathFailures = 0;
        Set<String> affectedFiles = new HashSet<>();
        boolean containsSyntaxFailure = false;

        for (String error : errors) {
            String normalized = error == null ? "" : error.toLowerCase(Locale.ROOT);
            if (isStrongStaleOutputSignal(normalized)) {
                return RecoveryDecision.recover("stale classpath/output diagnostic");
            }
            if (isSyntaxOrTypeFailure(normalized)) {
                containsSyntaxFailure = true;
            }
            if (isClasspathFailure(normalized)) {
                classpathFailures++;
                String path = extractAffectedPath(error);
                if (path != null && !path.isBlank()) {
                    affectedFiles.add(path);
                }
            }
        }

        if (!containsSyntaxFailure
                && classpathFailures >= CLASSPATH_FAILURE_MIN_ERRORS
                && affectedFiles.size() >= CLASSPATH_FAILURE_MIN_FILES) {
            return RecoveryDecision.recover(
                "widespread package/symbol failures across " + affectedFiles.size() + " files");
        }
        return RecoveryDecision.noRecovery();
    }

    private static boolean isStrongStaleOutputSignal(String error) {
        if (error.contains("bad class file:")
                || error.contains("cannot find annotation method")
                || error.contains("unknown enum constant")
                || error.contains("nosuchfileexception:")) {
            return true;
        }
        if (error.contains("class file for ") && error.contains(" not found")) {
            return true;
        }
        if (error.contains("cannot access ")) {
            return true;
        }
        return (error.contains("/target/classes/")
            || error.contains("/target/test-classes/")
            || error.contains("/build/classes/"))
            && (error.contains("not found") || error.contains("no such file"));
    }

    private static boolean isClasspathFailure(String error) {
        return (error.contains("package ") && error.contains(" does not exist"))
            || error.contains("cannot find symbol")
            || error.contains("cannot resolve symbol");
    }

    private static boolean isSyntaxOrTypeFailure(String error) {
        return error.contains("';' expected")
            || error.contains("illegal start")
            || error.contains("not a statement")
            || error.contains("reached end of file")
            || error.contains("unclosed ")
            || error.contains("identifier expected")
            || error.contains("incompatible types")
            || error.contains("cannot be applied to given types")
            || error.contains("might not have been initialized");
    }

    private static String extractAffectedPath(String error) {
        if (error == null) {
            return null;
        }
        Matcher rawPath = RAW_COMPILER_PATH.matcher(error);
        if (rawPath.find()) {
            return rawPath.group(1);
        }
        String path = CompilationEntryRenderer.extractPath(error);
        return "ERROR".equals(path) ? null : path;
    }

    record RecoveryDecision(boolean shouldRecover, @NotNull String reason) {
        private static @NotNull RecoveryDecision recover(@NotNull String reason) {
            return new RecoveryDecision(true, reason);
        }

        private static @NotNull RecoveryDecision noRecovery() {
            return new RecoveryDecision(false, "");
        }
    }
}
