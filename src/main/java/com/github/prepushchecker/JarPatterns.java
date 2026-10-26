package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds {@code scan} and {@code forbid} patterns for the Layers dialog, so nobody types them. */
final class JarPatterns {
    private static final Pattern VERSIONED = Pattern.compile("^(.*?)-\\d[^/]*\\.jar$", Pattern.CASE_INSENSITIVE);

    private JarPatterns() {
    }

    /**
     * A pattern for every version of the jar, anywhere: {@code lib/commons-collections-3.2.jar}
     * gives {@code **&#47;commons-collections-*.jar}. A name without a version matches only itself.
     */
    static @NotNull String anyVersion(@NotNull String jarDisplayPath) {
        String name = jarDisplayPath.substring(jarDisplayPath.lastIndexOf('/') + 1);
        Matcher matcher = VERSIONED.matcher(name);
        return matcher.matches() ? "**/" + matcher.group(1) + "-*.jar" : "**/" + name;
    }

    /** One {@code <dir>/*.jar} scan per folder holding IntelliJ's repo-relative library jars, sorted. */
    static @NotNull List<String> defaultScans(@NotNull Collection<String> ideJars) {
        TreeSet<String> scans = new TreeSet<>();
        for (String jar : ideJars) {
            int slash = jar.lastIndexOf('/');
            scans.add(slash < 0 ? "*.jar" : jar.substring(0, slash) + "/*.jar");
        }
        return List.copyOf(scans);
    }
}
