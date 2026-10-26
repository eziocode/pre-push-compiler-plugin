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
}
