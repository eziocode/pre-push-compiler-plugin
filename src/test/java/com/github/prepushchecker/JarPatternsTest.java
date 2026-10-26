package com.github.prepushchecker;

import junit.framework.TestCase;

import java.util.List;

public class JarPatternsTest extends TestCase {
    public void testAnyVersion() {
        assertEquals("**/commons-collections-*.jar",
            JarPatterns.anyVersion("dist/TP.zip!/lib/commons-collections-3.2.1.jar"));
        assertEquals("**/commons-collections4-*.jar", JarPatterns.anyVersion("lib/commons-collections4-4.4.jar"));
        assertEquals("**/tools.jar", JarPatterns.anyVersion("lib/tools.jar"));
        assertEquals("**/guava-*.jar", JarPatterns.anyVersion("guava-33.0.0-jre.jar"));
    }

    public void testAnyVersionMatchesOtherVersionsOnly() {
        AntPathPattern pattern = AntPathPattern.compile(JarPatterns.anyVersion("lib/commons-collections-3.2.jar"));
        assertTrue(pattern.matches("dist/TP.zip!/lib/commons-collections-3.1.jar"));
        assertFalse(pattern.matches("lib/commons-collections4-4.4.jar"));
    }

    public void testDefaultScans() {
        assertEquals(List.of("*.jar", "lib/*.jar", "lib/ext/*.jar"),
            JarPatterns.defaultScans(List.of("lib/a.jar", "lib/ext/b.jar", "lib/c.jar", "root.jar")));
    }
}
