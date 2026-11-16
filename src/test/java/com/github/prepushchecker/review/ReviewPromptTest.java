package com.github.prepushchecker.review;

import com.github.prepushchecker.commitgen.AiOutputSanitizer;
import junit.framework.TestCase;

import java.util.List;
import java.util.Map;

public class ReviewPromptTest extends TestCase {

    private static final String DIFF = """
        diff --git a/LeadTest.java b/LeadTest.java
        index 1..2 100644
        --- a/LeadTest.java
        +++ b/LeadTest.java
        @@ -10,3 +10,5 @@ public void test_x() {
             if (FeatureChecks.translationSupportForCustomButton()) {
        +        translationUtil.enableTranslationAndAddLanguage(lang);
        +        userInfo.setLocaleInfo(lang, "", "", "");
        -        old();
             }
        """;

    public void testNumberDiffUsesNewFileLines() {
        String numbered = ReviewPrompt.numberDiff(DIFF);
        assertTrue(numbered, numbered.contains("   10       if (FeatureChecks"));
        assertTrue(numbered, numbered.contains("   11 +         translationUtil"));
        assertTrue(numbered, numbered.contains("   12 +         userInfo"));
        assertTrue(numbered, numbered.contains("      -         old();"));
        assertTrue(numbered, numbered.contains("   13       }"));
        assertFalse(numbered, numbered.contains("index 1..2"));
    }

    public void testNumberNewFile() {
        assertEquals("@@ new file @@\n    1 + a\n    2 + b\n", ReviewPrompt.numberNewFile("a\nb\n"));
    }

    public void testParseFindingsKeepsOnlyReviewedFiles() {
        Map<?, ?> answer = AiOutputSanitizer.cleanStructured("""
            {"findings":[
              {"file":"crm/LeadTest.java","line":12,"rule":"Description","message":"Steps added without description","suggestion":"Add description.add(...)"},
              {"file":"Other.java","line":1,"message":"not reviewed"},
              {"file":"./crm/LeadTest.java","line":"7","message":"second"},
              {"file":"crm/LeadTest.java","message":""}
            ]}""", "findings");
        List<ReviewFinding> f = ReviewPrompt.parseFindings(answer, Map.of("crm/LeadTest.java", "/r/crm/LeadTest.java"));
        assertEquals(2, f.size());
        assertEquals("/r/crm/LeadTest.java", f.get(0).path);
        assertEquals(12, f.get(0).line);
        assertEquals("Description", f.get(0).rule);
        assertEquals(7, f.get(1).line);
    }

    public void testParseCountsDroppedAndAcceptsAlternativeKeys() {
        Map<?, ?> answer = AiOutputSanitizer.cleanStructured("""
            {"findings":[
              {"path":"crm/LeadTest.java","line":3,"description":"alt keys","fix":"do it"},
              {"line":5,"message":"no file named"},
              {"file":"Other.java","line":1,"message":"not reviewed"},
              {"file":"crm/LeadTest.java","message":""}
            ]}""", "findings");
        ReviewPrompt.Parsed p = ReviewPrompt.parse(answer, Map.of("crm/LeadTest.java", "/r/crm/LeadTest.java"));
        assertEquals(2, p.findings().size());
        assertEquals(2, p.dropped());
        assertEquals("alt keys", p.findings().get(0).message);
        assertEquals("do it", p.findings().get(0).suggestion);
        assertEquals("/r/crm/LeadTest.java", p.findings().get(1).path);
        assertEquals(5, p.findings().get(1).line);
    }

    public void testNoFileFallbackOnlyForSingleFileBatch() {
        Map<?, ?> answer = Map.of("findings", List.of(Map.of("line", 1, "message", "m")));
        ReviewPrompt.Parsed p = ReviewPrompt.parse(answer, Map.of("a/A.java", "/r/a/A.java", "b/B.java", "/r/b/B.java"));
        assertTrue(p.findings().isEmpty());
        assertEquals(1, p.dropped());
    }

    public void testReviewPromptCoversBothSidesAndAccurateMessages() {
        String p = ReviewPrompt.reviewSystemPrompt("rules");
        assertTrue(p, p.contains("broken by a change on either side"));
        assertTrue(p, p.contains("Never claim code changed when only a description"));
        assertTrue(p, p.contains("{\"findings\":[]}"));
    }

    public void testBatchesSplitLargeInput() {
        String big = "x".repeat(ReviewPrompt.MAX_BATCH_CHARS / 2);
        List<List<ReviewPrompt.FileDiff>> b = ReviewPrompt.batches(List.of(
            new ReviewPrompt.FileDiff("a", big), new ReviewPrompt.FileDiff("b", big), new ReviewPrompt.FileDiff("c", "y")));
        assertEquals(2, b.size());
        assertEquals(1, b.get(0).size());
        assertEquals(2, b.get(1).size());
    }

    public void testParseAndApplyFixReplace() {
        String content = "a\nb\nc\n";
        ReviewPrompt.Fix fix = ReviewPrompt.parseFix(Map.of("startLine", 2, "endLine", 2, "replacement", "B1\nB2"), 3);
        assertNotNull(fix);
        assertEquals("a\nB1\nB2\nc\n", ReviewPrompt.apply(content, fix));
    }

    public void testApplyFixInsert() {
        ReviewPrompt.Fix fix = ReviewPrompt.parseFix(Map.of("startLine", 3, "endLine", 2, "replacement", "new\n"), 3);
        assertNotNull(fix);
        assertEquals("a\nb\nnew\nc\n", ReviewPrompt.apply("a\nb\nc\n", fix));
        assertEquals("a\r\nnew\r\nb\r\n",
            ReviewPrompt.apply("a\r\nb\r\n", new ReviewPrompt.Fix(2, 1, "new")));
    }

    public void testParseFixRejectsBadRanges() {
        assertNull(ReviewPrompt.parseFix(Map.of("startLine", 0, "endLine", 1, "replacement", "x"), 3));
        assertNull(ReviewPrompt.parseFix(Map.of("startLine", 2, "endLine", 9, "replacement", "x"), 3));
        assertNull(ReviewPrompt.parseFix(Map.of("startLine", 2, "endLine", 2), 3));
        assertNull(ReviewPrompt.parseFix(null, 3));
    }

    public void testFixPromptWindowsLargeFiles() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 5000; i++) sb.append("line").append(i).append('\n');
        ReviewFinding f = new ReviewFinding("/r/A.java", 4000, "R", "msg", "");
        String prompt = ReviewPrompt.fixUserPrompt("A.java", f, sb.toString());
        assertTrue(prompt.contains("(lines 3700-4300 of 5000)"));
        assertTrue(prompt.contains(" 4000  line4000"));
        assertFalse(prompt.contains("line10\n"));
    }

    public void testChangedRange() {
        assertArrayEquals(new int[]{2, 1}, ReviewFixer.changedRange("abXc", "abYZc"));
        assertArrayEquals(new int[]{3, 0}, ReviewFixer.changedRange("abc", "abc"));
    }

    private static void assertArrayEquals(int[] expected, int[] actual) {
        assertEquals(java.util.Arrays.toString(expected), java.util.Arrays.toString(actual));
    }

    private static final String FINALLY_BLOCK = """
        finally {
            List<String> desc = new ArrayList<String>();
            desc.add("Create a record in "+module+" module");
            desc.add("Verify record in Unaccounted stage with select Standard layout");
            desc.add("Delete the layout and go to kanban view and verify record");
            addRowToReport(desc, null, "");
        }
        """;

    public void testDroppedLinesCatchesRemovedSetupAndReportCall() {
        // The AI rewrote lines 2-6 but kept only the desc.add(...) lines (real incident).
        ReviewPrompt.Fix fix = new ReviewPrompt.Fix(2, 6, """
                desc.add("Create a record in "+module+" module");
                desc.add("Create a layout for "+module+" module");
                desc.add("Verify record in Unaccounted stage with select Standard layout");
                desc.add("Delete the layout and verify if kanban view still works");
            """);
        assertEquals(List.of("List<String> desc = new ArrayList<String>();", "addRowToReport(desc, null, \"\");"),
            ReviewPrompt.droppedLines(FINALLY_BLOCK, fix));
    }

    public void testDroppedLinesAllowsInsertAndInPlaceEdit() {
        assertTrue(ReviewPrompt.droppedLines(FINALLY_BLOCK,
            new ReviewPrompt.Fix(5, 4, "    desc.add(\"Create a layout\");")).isEmpty());
        assertTrue(ReviewPrompt.droppedLines(FINALLY_BLOCK, new ReviewPrompt.Fix(4, 5, """
                desc.add("Verify record in Unaccounted stage with select Standard layout");
                desc.add("Create a kanban view");
                desc.add("Delete the layout and go to kanban view and verify the record");
            """)).isEmpty());
        // Inline List.of(...) extended with one more entry counts as an edit, not a deletion.
        String inline = "addRowToReport(List.of(\"Create a record\", \"Verify record\"), null, \"\");\n";
        assertTrue(ReviewPrompt.droppedLines(inline, new ReviewPrompt.Fix(1, 1,
            "addRowToReport(List.of(\"Create a record\", \"Create a layout\", \"Verify record\"), null, \"\");")).isEmpty());
    }

    public void testFixPromptsAskToKeepLinesAndFollowPattern() {
        String system = ReviewPrompt.fixSystemPrompt("rules");
        assertTrue(system.contains("List.of("));
        assertTrue(system.contains("Never drop"));
        String retry = ReviewPrompt.fixRetryUserPrompt("USER", new ReviewPrompt.Fix(2, 6, "x"),
            List.of("addRowToReport(desc, null, \"\");"));
        assertTrue(retry.startsWith("USER"));
        assertTrue(retry.contains("replaced lines 2-6"));
        assertTrue(retry.contains("addRowToReport(desc, null, \"\");"));
    }
}
