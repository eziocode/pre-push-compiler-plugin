package com.github.prepushchecker;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LayerJarsTest extends TestCase {
    private Path repo;

    @Override
    protected void setUp() throws Exception {
        repo = Files.createTempDirectory("layer-jars");
        TestFiles.jar(repo, "lib/core/a.jar", "a/A.class");
        TestFiles.jar(repo, "lib/core/sub/b.jar", "b/B.class");
        TestFiles.jar(repo, "lib/crm/c.jar", "c/C.class");
        TestFiles.write(repo, "lib/core/readme.txt", "x");
        TestFiles.write(repo, "lib/core/classes.jar.bak", "x");
    }

    @Override
    protected void tearDown() throws Exception {
        TestFiles.delete(repo);
    }

    public void testStarStaysInOneDirectoryAndOnlyJarsMatch() {
        assertEquals(java.util.Set.of("lib/core/a.jar"), LayerJars.expand(repo, List.of("lib/core/*.jar")));
        assertTrue(LayerJars.expand(repo, List.of("lib/core/*")).contains("lib/core/a.jar"));
        assertFalse(LayerJars.expand(repo, List.of("lib/core/*")).contains("lib/core/readme.txt"));
    }

    public void testDoubleStarAndExactPaths() {
        assertEquals(java.util.Set.of("lib/core/a.jar", "lib/core/sub/b.jar", "lib/crm/c.jar"),
            LayerJars.expand(repo, List.of("lib/**/*.jar")));
        assertEquals(java.util.Set.of("lib/crm/c.jar"), LayerJars.expand(repo, List.of("./lib/crm/c.jar")));
        assertTrue(LayerJars.expand(repo, List.of("lib/missing/*.jar")).isEmpty());
        assertTrue(LayerJars.expand(repo, List.of("lib/crm/gone.jar")).isEmpty());
    }

    public void testJarBelongsToEarliestLayerThatListsIt() {
        LayerRules rules = LayerRulesParser.parse(String.join("\n",
            "root source",
            "layer core", "  include source/core/**", "  lib lib/core/*.jar",
            "layer crm", "  include source/**", "  lib lib/**/*.jar", "")).rules();
        Map<String, Integer> layers = LayerJars.resolve(repo, rules).stream()
            .collect(Collectors.toMap(LayerJars.Jar::relativePath, LayerJars.Jar::layerIndex));
        assertEquals(Integer.valueOf(0), layers.get("lib/core/a.jar"));
        assertEquals(Integer.valueOf(1), layers.get("lib/core/sub/b.jar"));
        assertEquals(Integer.valueOf(1), layers.get("lib/crm/c.jar"));
        assertEquals(3, layers.size());
    }
}
