package com.github.prepushchecker;

import junit.framework.TestCase;

public class LayerRulesEditTest extends TestCase {
    public void testAllowJarRemovesMatchingForbid() {
        String before = "root s\nscan lib/*.jar\nlayer a\n  include s/a/**\n  forbid lib/x.jar\n  forbid lib/y.jar\n"
            + "layer b\n  include s/**\n";
        LayerRules rules = LayerRulesParser.parse(LayerRulesEdit.allowJar(before, "a", "lib/x.jar")).rules();
        assertEquals(1, rules.layers().get(0).forbids().size());
        assertEquals("lib/y.jar", rules.layers().get(0).forbids().get(0).source());
        assertEquals("lib/*.jar", rules.scans().get(0).source());
    }

    public void testEditKeepsLayersAndDropsComments() {
        String before = "# mine\nroot s\nlayer a\n  include s/a/**\nlayer b\n  include s/**\n";
        String after = LayerRulesEdit.addLib(before, "a", "lib/x.jar");
        LayerRules rules = LayerRulesParser.parse(after).rules();
        assertEquals("lib/x.jar", rules.layers().get(0).libs().get(0).source());
        assertEquals(2, rules.layers().size());
        assertEquals(java.util.List.of("s"), rules.roots());
        assertFalse(after, after.contains("# mine"));
        assertTrue(LayerRulesEdit.hasOwnComments(before));
        assertFalse(LayerRulesEdit.hasOwnComments(after));
    }

    public void testUnknownLayerOrBadTextIsRejected() {
        String ok = "root s\nlayer a\n  include s/a/**\nlayer b\n  include s/**\n";
        try {
            LayerRulesEdit.addLib(ok, "missing", "lib/x.jar");
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("missing"));
        }
        try {
            LayerRulesEdit.addLib("layer a\n", "a", "lib/x.jar");
            fail();
        } catch (IllegalArgumentException expected) {
            // Rules that don't parse are not rewritten.
        }
    }
}
