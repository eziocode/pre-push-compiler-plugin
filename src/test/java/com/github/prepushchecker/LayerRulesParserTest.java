package com.github.prepushchecker;

import junit.framework.TestCase;

public class LayerRulesParserTest extends TestCase {
    private static final String RULES = String.join("\n",
        "# compile order",
        "root source/",
        "",
        "layer crmcore",
        "  include source/com/zoho/crm/core/**",
        "  include source/com/adventnet/crm/common/util/**   # shared constants",
        "  exclude source/com/adventnet/crm/common/util/ui/**",
        "layer crm",
        "  include source/**");

    public void testParsesRootsAndLayersInOrder() {
        LayerRulesParser.Result result = LayerRulesParser.parse(RULES);
        assertTrue(result.problems().toString(), result.ok());
        LayerRules rules = result.rules();
        assertEquals(java.util.List.of("source"), rules.roots());
        assertEquals(2, rules.layers().size());
        assertEquals("crmcore", rules.layers().get(0).name());
        assertEquals(1, rules.layers().get(1).index());
    }

    public void testFirstMatchingLayerWinsAndExcludesFallThrough() {
        LayerRules rules = LayerRulesParser.parse(RULES).rules();
        assertEquals("crmcore", rules.layerOf("source/com/adventnet/crm/common/util/CrmConstants.java").name());
        assertEquals("crm", rules.layerOf("source/com/adventnet/crm/common/util/ui/View.java").name());
        assertEquals("crm", rules.layerOf("source/com/adventnet/crm/authorization/territory/util/X.java").name());
        assertNull(rules.layerOf("test/com/Foo.java"));
    }

    public void testReportsProblemsWithLineNumbers() {
        LayerRulesParser.Result result = LayerRulesParser.parse(String.join("\n",
            "include source/**",
            "root source",
            "layer a",
            "  include source/a/**",
            "layer a",
            "  include source/**",
            "bogus x"));
        assertFalse(result.ok());
        assertNull(result.rules());
        String problems = result.problems().toString();
        assertTrue(problems, problems.contains("line=1, message='include' before any 'layer'"));
        assertTrue(problems, problems.contains("line=5, message=duplicate layer 'a'"));
        assertTrue(problems, problems.contains("line=7, message=unknown directive 'bogus'"));
    }

    public void testNeedsRootAndTwoLayers() {
        LayerRulesParser.Result result = LayerRulesParser.parse("layer only\n include **\n");
        String problems = result.problems().toString();
        assertTrue(problems, problems.contains("no 'root' declared"));
        assertTrue(problems, problems.contains("at least two layers"));
    }

    public void testLayerWithoutIncludeIsAProblem() {
        LayerRulesParser.Result result = LayerRulesParser.parse("root .\nlayer a\nlayer b\n include **\n");
        assertTrue(result.problems().toString().contains("layer 'a' has no 'include'"));
    }

    public void testLibLinesAreParsedPerLayer() {
        LayerRules rules = LayerRulesParser.parse(String.join("\n",
            "root source",
            "layer a", "  include source/a/**", "  lib lib/core/*.jar  # core jars",
            "layer b", "  include source/**", "")).rules();
        assertEquals(1, rules.layers().get(0).libs().size());
        assertTrue(rules.layers().get(0).libs().get(0).matches("lib/core/x.jar"));
        assertTrue(rules.layers().get(1).libs().isEmpty());
        assertTrue(rules.hasLibs());
    }

    public void testHashInsidePathsAndNamesIsNotAComment() {
        LayerRules rules = LayerRulesParser.parse(String.join("\n",
            "# whole-line comment",
            "root source",
            "layer stage#1   # trailing comment",
            "  include source/C#/**",
            "  lib lib/C#/interop.jar  # also a comment",
            "layer b", "  include source/**", "")).rules();
        assertEquals("stage#1", rules.layers().get(0).name());
        assertEquals("source/C#/**", rules.layers().get(0).includes().get(0).source());
        assertEquals("lib/C#/interop.jar", rules.layers().get(0).libs().get(0).source());
    }
}
