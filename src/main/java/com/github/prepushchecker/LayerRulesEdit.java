package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One-click edits of {@code .prepush-layers} text for the tool window's quick fixes. The file is
 * re-rendered like the Layers dialog does, so the user's own comments are not kept.
 */
final class LayerRulesEdit {
    static final String HEADER = "Edited from the Compilation Checker.";

    private LayerRulesEdit() {
    }

    /** Puts {@code jar} on {@code layer}'s classpath. */
    static @NotNull String addLib(@NotNull String rulesText, @NotNull String layer, @NotNull String jar) {
        return edit(rulesText, layer, draft -> {
            if (!draft.libs.contains(jar)) draft.libs.add(jar);
        });
    }

    /** Removes every {@code forbid} line of {@code layer} that matches the jar, checking it again. */
    static @NotNull String allowJar(@NotNull String rulesText, @NotNull String layer, @NotNull String jarDisplayPath) {
        return edit(rulesText, layer,
            draft -> draft.forbids.removeIf(forbid -> AntPathPattern.compile(forbid).matches(jarDisplayPath)));
    }

    /**
     * {@code layer}'s wildcard {@code forbid} patterns that match the jar: {@link #allowJar} removes them
     * too, re-allowing every other jar they cover. Empty when the text does not parse.
     */
    static @NotNull List<String> wildcardForbidsMatching(
        @NotNull String rulesText,
        @NotNull String layer,
        @NotNull String jarDisplayPath
    ) {
        LayerRules rules = LayerRulesParser.parse(rulesText).rules();
        if (rules == null) return List.of();
        List<String> result = new ArrayList<>();
        for (LayerRules.Layer candidate : rules.layers()) {
            if (!candidate.name().equals(layer)) continue;
            for (AntPathPattern forbid : candidate.forbids()) {
                String source = forbid.source();
                boolean wildcard = source.indexOf('*') >= 0 || source.indexOf('?') >= 0 || source.indexOf('[') >= 0;
                if (wildcard && forbid.matches(jarDisplayPath)) result.add(source);
            }
        }
        return result;
    }

    /** {@code true} when the text has comments the writer would not put back. */
    static boolean hasOwnComments(@NotNull String rulesText) {
        Set<String> generated = new HashSet<>(List.of(
            LayerRulesWriter.render(List.of(), List.of(), List.of(), List.of(HEADER, "Edited with the Layers dialog."))
                .split("\n")));
        for (String line : rulesText.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") && !generated.contains(trimmed)) return true;
            if (!trimmed.startsWith("#") && (trimmed.contains(" #") || trimmed.contains("\t#"))) return true;
        }
        return false;
    }

    private interface DraftEdit {
        void apply(LayerDraft draft);
    }

    private static String edit(String rulesText, String layer, DraftEdit change) {
        LayerRulesParser.Result parsed = LayerRulesParser.parse(rulesText);
        if (!parsed.ok()) {
            throw new IllegalArgumentException(LayerRules.FILE_NAME + " has errors: " + parsed.problems());
        }
        List<LayerDraft> drafts = LayerDraft.fromRules(parsed.rules());
        LayerDraft target = drafts.stream().filter(d -> d.name.equals(layer)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No layer '" + layer + "' in " + LayerRules.FILE_NAME));
        change.apply(target);
        List<String> scans = new ArrayList<>();
        parsed.rules().scans().forEach(pattern -> scans.add(pattern.source()));
        return LayerRulesWriter.render(parsed.rules().roots(), scans, drafts, List.of(HEADER));
    }
}
