package com.github.prepushchecker;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ScanJarsTest extends TestCase {
    private Path repo;

    @Override
    protected void setUp() throws Exception {
        repo = Files.createTempDirectory("scan-jars");
    }

    @Override
    protected void tearDown() throws Exception {
        TestFiles.delete(repo);
    }

    public void testResolvesDiskJarsAndJarsInsideZip() throws Exception {
        TestFiles.jar(repo, "lib/x.jar", "org/x/X.class");
        TestFiles.zip(repo, "dist/TP.zip", Map.of(
            "lib/commons-collections-3.2.jar",
            TestFiles.jarBytes("org/apache/commons/collections/CollectionUtils.class"),
            "readme.txt", new byte[]{1}));
        List<ScanJars.ScannedJar> jars = ScanJars.resolve(repo, List.of("lib/**", "dist/TP.zip"));
        assertEquals(List.of("lib/x.jar", "dist/TP.zip!/lib/commons-collections-3.2.jar"),
            jars.stream().map(ScanJars.ScannedJar::displayPath).toList());
        assertTrue(jars.get(0).onDisk());
        assertFalse(jars.get(1).onDisk());
        try (ScanJarIndex index = new ScanJarIndex(jars)) {
            List<CrossLayerImportCheck.JarHit> hits =
                index.classHits("org.apache.commons.collections.CollectionUtils");
            assertEquals(1, hits.size());
            assertEquals("dist/TP.zip!/lib/commons-collections-3.2.jar", hits.get(0).jarPath());
            assertEquals(LayerRules.UNLISTED_LAYER, hits.get(0).layerIndex());
            assertEquals(1, index.packageHits("org.x").size());
            assertTrue(index.classHits("org.x.Missing").isEmpty());
        }
    }

    public void testOverlappingPatternsListAJarOnce() throws Exception {
        TestFiles.jar(repo, "lib/x.jar", "org/x/X.class");
        assertEquals(1, ScanJars.resolve(repo, List.of("lib/*.jar", "lib/**")).size());
    }

    public void testCorruptZipAndNestedJarAreSkipped() throws Exception {
        TestFiles.write(repo, "dist/bad.zip", "not a zip");
        TestFiles.zip(repo, "dist/ok.zip", Map.of("bad.jar", new byte[]{1, 2, 3}));
        List<ScanJars.ScannedJar> jars = ScanJars.resolve(repo, List.of("dist/*.zip"));
        try (ScanJarIndex index = new ScanJarIndex(jars)) {
            assertTrue(index.classHits("a.B").isEmpty());
            assertTrue(index.packageHits("a").isEmpty());
        }
    }

    public void testCachesEvictLeastRecentlyUsedNotEverything() {
        Map<String, String> cache = ScanJarIndex.lruCache(2);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.get("a");
        cache.put("c", "3");
        assertEquals(java.util.Set.of("a", "c"), cache.keySet());
    }

    public void testNestedJarClassesAreFoundOnlyThroughTheirPackage() throws Exception {
        TestFiles.zip(repo, "dist/TP.zip", Map.of(
            "a.jar", TestFiles.jarBytes("org/a/A.class"),
            "b.jar", TestFiles.jarBytes("org/b/B.class")));
        try (ScanJarIndex index = new ScanJarIndex(ScanJars.resolve(repo, List.of("dist/TP.zip")))) {
            assertEquals("dist/TP.zip!/b.jar", index.classHits("org.b.B").get(0).jarPath());
            assertTrue(index.classHits("org.b.A").isEmpty());
            assertEquals(1, index.packageHits("org.a").size());
        }
    }
}
