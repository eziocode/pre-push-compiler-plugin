package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A jar diagnostic from {@link CrossLayerImportCheck}, read back so the tool window can offer a fix. */
record LayerDiagnostic(@NotNull Kind kind, @NotNull String layer, @NotNull List<String> jars) {
    enum Kind { FORBIDDEN_JAR, LATER_LAYER_JAR }

    private static final Pattern FORBIDDEN = Pattern.compile(
        "Forbidden jar: '(.+?)' file imports \\S+, available only from jars layer '\\1' unchecked: (.+?)\\."
            + " Check one in Layers or fix the import$");
    private static final Pattern LATER = Pattern.compile(
        "Layer violation: '(.+?)' file imports \\S+, found only in jar (.+?) of later layer '.+?';");
    private static final Pattern MORE = Pattern.compile(" and \\d+ more$");

    LayerDiagnostic {
        jars = List.copyOf(jars);
    }

    static @Nullable LayerDiagnostic parse(@NotNull String diagnostic) {
        Matcher forbidden = FORBIDDEN.matcher(diagnostic);
        if (forbidden.find()) {
            String listed = MORE.matcher(forbidden.group(2)).replaceFirst("");
            return new LayerDiagnostic(Kind.FORBIDDEN_JAR, forbidden.group(1), List.of(listed.split(", ")));
        }
        Matcher later = LATER.matcher(diagnostic);
        if (later.find()) return new LayerDiagnostic(Kind.LATER_LAYER_JAR, later.group(1), List.of(later.group(2)));
        return null;
    }
}
