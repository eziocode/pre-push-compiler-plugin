package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Renders {@code .prepush-layers} text. Shared by the Ant importer and the Layers dialog. */
final class LayerRulesWriter {
    private LayerRulesWriter() {
    }

    /**
     * @param roots   source roots for resolving import packages; the layers' own roots are added
     * @param headers extra {@code #} comment lines for the top of the file (without the {@code #})
     */
    static @NotNull String render(
        @NotNull List<String> roots,
        @NotNull List<LayerDraft> layers,
        @NotNull List<String> headers
    ) {
        StringBuilder out = new StringBuilder();
        out.append("# Layers in compile order: a file may import only from its own or earlier layers.\n");
        out.append("# First matching layer wins. 'lib' jars are on that layer's classpath and later ones.\n");
        for (String header : headers) out.append('#').append(' ').append(header).append('\n');
        out.append('\n');

        Set<String> allRoots = new LinkedHashSet<>(roots);
        for (LayerDraft layer : layers) allRoots.addAll(layer.roots);
        for (String root : allRoots) out.append("root ").append(root.isEmpty() ? "." : root).append('\n');

        for (LayerDraft layer : layers) {
            out.append('\n').append("layer ").append(layer.name).append('\n');
            for (String include : layer.includes) out.append("  include ").append(include).append('\n');
            for (String exclude : layer.excludes) out.append("  exclude ").append(exclude).append('\n');
            for (String lib : layer.libs) out.append("  lib ").append(lib).append('\n');
        }
        return out.toString();
    }
}
