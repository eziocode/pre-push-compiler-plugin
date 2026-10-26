package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses {@code .prepush-layers}:
 * <pre>
 * root source                         # source root(s) used to resolve import packages
 * scan dist/TP_COMPONENTS.zip         # global: where jars may be found (jars inside a .zip too)
 * layer crmcore                       # layers in compile order
 *   include source/com/zoho/crm/core/**
 *   exclude source/com/zoho/crm/core/ui/**
 *   lib lib/core/*.jar                  # jars on this layer's classpath (inherited by later layers)
 *   forbid **&#47;commons-collections-3*.jar # jars this layer must not use (not inherited)
 * layer crm
 *   include source/**
 * </pre>
 * Indentation is optional; {@code #} at the line start or after whitespace starts a comment.
 */
final class LayerRulesParser {

    record Problem(int line, @NotNull String message) {
    }

    record Result(@Nullable LayerRules rules, @NotNull List<Problem> problems) {
        Result {
            problems = List.copyOf(problems);
        }

        boolean ok() {
            return rules != null && problems.isEmpty();
        }
    }

    private LayerRulesParser() {
    }

    static @NotNull Result parse(@NotNull String text) {
        List<Problem> problems = new ArrayList<>();
        List<String> roots = new ArrayList<>();
        List<AntPathPattern> scans = new ArrayList<>();
        List<LayerRules.Layer> layers = new ArrayList<>();
        Set<String> names = new HashSet<>();

        String currentName = null;
        int currentLine = 0;
        List<AntPathPattern> includes = new ArrayList<>();
        List<AntPathPattern> excludes = new ArrayList<>();
        List<AntPathPattern> libs = new ArrayList<>();
        List<AntPathPattern> forbids = new ArrayList<>();

        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = stripComment(lines[i]).trim();
            if (line.isEmpty()) continue;

            int space = indexOfWhitespace(line);
            String directive = space < 0 ? line : line.substring(0, space);
            String argument = space < 0 ? "" : line.substring(space).trim();
            if (argument.isEmpty() && !directive.equals("root")) {
                problems.add(new Problem(lineNo, "'" + directive + "' needs a value"));
                continue;
            }

            switch (directive) {
                case "root" -> roots.add(argument.isEmpty() ? "." : argument);
                case "scan" -> scans.add(AntPathPattern.compile(argument));
                case "layer" -> {
                    if (currentName != null) {
                        addLayer(layers, currentName, currentLine, includes, excludes, libs, forbids, problems);
                    }
                    if (!names.add(argument)) {
                        problems.add(new Problem(lineNo, "duplicate layer '" + argument + "'"));
                    }
                    currentName = argument;
                    currentLine = lineNo;
                    includes = new ArrayList<>();
                    excludes = new ArrayList<>();
                    libs = new ArrayList<>();
                    forbids = new ArrayList<>();
                }
                case "include", "exclude", "lib", "forbid" -> {
                    if (currentName == null) {
                        problems.add(new Problem(lineNo, "'" + directive + "' before any 'layer'"));
                    } else if (directive.equals("include")) {
                        includes.add(AntPathPattern.compile(argument));
                    } else if (directive.equals("exclude")) {
                        excludes.add(AntPathPattern.compile(argument));
                    } else if (directive.equals("lib")) {
                        libs.add(AntPathPattern.compile(argument));
                    } else {
                        forbids.add(AntPathPattern.compile(argument));
                    }
                }
                default -> problems.add(new Problem(lineNo, "unknown directive '" + directive + "'"));
            }
        }
        if (currentName != null) {
            addLayer(layers, currentName, currentLine, includes, excludes, libs, forbids, problems);
        }

        if (roots.isEmpty()) problems.add(new Problem(1, "no 'root' declared"));
        if (layers.size() < 2) problems.add(new Problem(1, "at least two layers are needed"));
        if (!problems.isEmpty()) return new Result(null, problems);
        return new Result(new LayerRules(LayerRules.normalizeRoots(roots), scans, layers), problems);
    }

    private static void addLayer(
        List<LayerRules.Layer> layers,
        String name,
        int line,
        List<AntPathPattern> includes,
        List<AntPathPattern> excludes,
        List<AntPathPattern> libs,
        List<AntPathPattern> forbids,
        List<Problem> problems
    ) {
        if (includes.isEmpty()) {
            problems.add(new Problem(line, "layer '" + name + "' has no 'include'"));
        }
        layers.add(new LayerRules.Layer(name, layers.size(), includes, excludes, libs, forbids));
    }

    /** A {@code #} starts a comment only at the line start or after whitespace, so {@code C#} paths survive. */
    private static String stripComment(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int indexOfWhitespace(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i))) return i;
        }
        return -1;
    }
}
