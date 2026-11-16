package com.github.prepushchecker;

import junit.framework.TestCase;

import java.util.List;
import java.util.Set;

public class SymbolicAbCheckTest extends TestCase {
    public void testChangedSignatureIsNotANewDeclaration() {
        List<String> diff = List.of(
            "--- a/src/A.java",
            "+++ b/src/A.java",
            "-    public void process(String value) {",
            "+    public void process(String value, int retries) {");
        assertTrue(SymbolicAbCheck.addedDeclarations(diff).isEmpty());
    }

    public void testGenuinelyNewMethodIsReported() {
        List<String> diff = List.of(
            "+++ b/src/A.java",
            "+    public static String formatTotals(int count) {");
        assertEquals(Set.of("formatTotals"), SymbolicAbCheck.addedDeclarations(diff));
    }
}
