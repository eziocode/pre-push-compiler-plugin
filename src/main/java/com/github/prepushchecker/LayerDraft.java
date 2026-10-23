package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Editable form of one layer, used by the Layers dialog and the Ant importer. */
final class LayerDraft {
    @NotNull String name;
    final @NotNull List<String> roots = new ArrayList<>();
    final @NotNull List<String> includes = new ArrayList<>();
    final @NotNull List<String> excludes = new ArrayList<>();
    final @NotNull List<String> libs = new ArrayList<>();

    LayerDraft(@NotNull String name) {
        this.name = name;
    }

    static @NotNull List<LayerDraft> fromRules(@NotNull LayerRules rules) {
        List<LayerDraft> drafts = new ArrayList<>();
        for (LayerRules.Layer layer : rules.layers()) {
            LayerDraft draft = new LayerDraft(layer.name());
            layer.includes().forEach(p -> draft.includes.add(p.source()));
            layer.excludes().forEach(p -> draft.excludes.add(p.source()));
            layer.libs().forEach(p -> draft.libs.add(p.source()));
            drafts.add(draft);
        }
        return drafts;
    }

    @Override
    public String toString() {
        return name;
    }
}
