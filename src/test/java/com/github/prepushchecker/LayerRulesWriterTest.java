package com.github.prepushchecker;

import junit.framework.TestCase;

import java.util.List;

public class LayerRulesWriterTest extends TestCase {
    public void testRenderedDraftsParseBackWithLibs() {
        LayerDraft core = new LayerDraft("crmcore");
        core.roots.add("source");
        core.includes.add("source/com/zoho/crm/core/**");
        core.excludes.add("source/com/zoho/crm/core/ui/**");
        core.libs.add("lib/core/*.jar");
        LayerDraft crm = new LayerDraft("crm");
        crm.roots.add("source");
        crm.includes.add("source/**");

        String text = LayerRulesWriter.render(List.of(), List.of(core, crm), List.of("TODO check me"));
        assertTrue(text, text.contains("# TODO check me"));
        assertEquals(1, text.split("root source", -1).length - 1);

        LayerRulesParser.Result parsed = LayerRulesParser.parse(text);
        assertTrue(parsed.problems() + "\n" + text, parsed.ok());
        LayerRules rules = parsed.rules();
        assertEquals(List.of("source"), rules.roots());
        assertTrue(rules.hasLibs());
        assertEquals("lib/core/*.jar", rules.layers().get(0).libs().get(0).source());

        List<LayerDraft> back = LayerDraft.fromRules(rules);
        assertEquals("crmcore", back.get(0).name);
        assertEquals(List.of("source/com/zoho/crm/core/**"), back.get(0).includes);
        assertEquals(List.of("source/com/zoho/crm/core/ui/**"), back.get(0).excludes);
        assertEquals(List.of("lib/core/*.jar"), back.get(0).libs);
        assertTrue(back.get(1).libs.isEmpty());
    }

    public void testRulesWithoutLibsKeepJarCheckOff() {
        LayerRules rules = LayerRulesParser.parse("root s\nlayer a\n include s/a/**\nlayer b\n include s/**\n").rules();
        assertFalse(rules.hasLibs());
    }
}
