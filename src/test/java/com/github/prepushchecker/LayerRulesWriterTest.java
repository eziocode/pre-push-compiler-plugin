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

        String text = LayerRulesWriter.render(List.of(), List.of(), List.of(core, crm), List.of("TODO check me"));
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

    public void testScanAndForbidRoundTrip() {
        LayerDraft core = new LayerDraft("core");
        core.roots.add("source");
        core.includes.add("source/core/**");
        core.forbids.add("**/old-*.jar");
        LayerDraft app = new LayerDraft("app");
        app.includes.add("source/**");
        String text = LayerRulesWriter.render(List.of(), List.of("dist/TP.zip"), List.of(core, app), List.of());
        assertTrue(text, text.contains("\nscan dist/TP.zip\n") && text.contains("  forbid **/old-*.jar\n"));
        LayerRules back = LayerRulesParser.parse(text).rules();
        assertEquals("dist/TP.zip", back.scans().get(0).source());
        assertEquals(List.of("**/old-*.jar"), LayerDraft.fromRules(back).get(0).forbids);
    }

    public void testReloadedDraftsKeepTheirRoots() {
        LayerRules rules = LayerRulesParser.parse(
            "root mod1/src\nroot mod2/src\nlayer a\n  include mod1/src/**\nlayer b\n  include mod2/src/**\n").rules();
        List<LayerDraft> drafts = LayerDraft.fromRules(rules);
        assertEquals(List.of("mod1/src"), drafts.get(0).roots);
        LayerRules back = LayerRulesParser.parse(LayerRulesWriter.render(List.of(), List.of(), drafts, List.of())).rules();
        assertNotNull(back);
        assertEquals(List.of("mod1/src", "mod2/src"), back.roots());
    }
}
