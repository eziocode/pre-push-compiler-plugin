package com.github.prepushchecker;

import junit.framework.TestCase;

public class AntPathPatternTest extends TestCase {
    public void testDoubleStarMatchesZeroOrMoreDirectories() {
        AntPathPattern pattern = AntPathPattern.compile("source/com/zoho/**");
        assertTrue(pattern.matches("source/com/zoho/A.java"));
        assertTrue(pattern.matches("source/com/zoho/crm/core/B.java"));
        assertFalse(pattern.matches("source/com/adventnet/C.java"));
    }

    public void testDoubleStarInTheMiddle() {
        AntPathPattern pattern = AntPathPattern.compile("source/**/util/*.java");
        assertTrue(pattern.matches("source/util/A.java"));
        assertTrue(pattern.matches("source/com/x/util/A.java"));
        assertFalse(pattern.matches("source/com/x/util/sub/A.java"));
    }

    public void testSingleStarStaysWithinSegment() {
        AntPathPattern pattern = AntPathPattern.compile("source/com/*/A.java");
        assertTrue(pattern.matches("source/com/x/A.java"));
        assertFalse(pattern.matches("source/com/x/y/A.java"));
        assertTrue(AntPathPattern.compile("source/*Util.java").matches("source/CrmUtil.java"));
        assertFalse(AntPathPattern.compile("source/*Util.java").matches("source/CrmUtils.java"));
    }

    public void testQuestionMarkMatchesOneCharacter() {
        AntPathPattern pattern = AntPathPattern.compile("a/B?.java");
        assertTrue(pattern.matches("a/B1.java"));
        assertFalse(pattern.matches("a/B12.java"));
    }

    public void testTrailingSlashMeansEverythingBelow() {
        AntPathPattern pattern = AntPathPattern.compile("./source/com/");
        assertTrue(pattern.matches("source/com/a/b/C.java"));
        assertFalse(pattern.matches("source/org/C.java"));
    }

    public void testDirectoryBoundaryIsRespected() {
        assertFalse(AntPathPattern.compile("source/core/**").matches("source/coreext/A.java"));
    }

    public void testCharacterClass() {
        AntPathPattern digit = AntPathPattern.compile("**/a-[0-9]*.jar");
        assertTrue(digit.matches("lib/a-1.jar"));
        assertFalse(digit.matches("lib/a-b.jar"));
        assertTrue(AntPathPattern.compile("x[ab].txt").matches("xb.txt"));
        assertTrue("unclosed bracket is literal", AntPathPattern.compile("x[a.txt").matches("x[a.txt"));
    }
}
