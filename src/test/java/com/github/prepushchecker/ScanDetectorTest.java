package com.github.prepushchecker;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ScanDetectorTest extends TestCase {
    private Path repo;

    @Override
    protected void setUp() throws Exception {
        repo = Files.createTempDirectory("scan-detector");
    }

    @Override
    protected void tearDown() throws Exception {
        TestFiles.delete(repo);
    }

    public void testDetectsZipsWithJarsAndUnknownJarDirs() throws Exception {
        TestFiles.zip(repo, "dist/TP.zip", Map.of("a.jar", TestFiles.jarBytes("a/A.class")));
        TestFiles.zip(repo, "dist/docs.zip", Map.of("readme.txt", new byte[]{1}));
        TestFiles.jar(repo, "thirdparty/x/y.jar", "y/Y.class");
        TestFiles.jar(repo, "thirdparty/x/deeper/z.jar", "z/Z.class");
        TestFiles.jar(repo, "lib/known.jar", "k/K.class");
        TestFiles.jar(repo, "build/libs/out.jar", "o/O.class");
        TestFiles.jar(repo, ".gradle/cache.jar", "c/C.class");
        List<ScanDetector.Suggestion> suggestions = ScanDetector.detect(repo, Set.of("lib/known.jar"));
        assertEquals(List.of("dist/TP.zip", "thirdparty/x/**"),
            suggestions.stream().map(ScanDetector.Suggestion::pattern).toList());
        assertEquals("zip with 1 jars", suggestions.get(0).reason());
        assertEquals("2 jars not on IntelliJ's classpath", suggestions.get(1).reason());
    }
}
