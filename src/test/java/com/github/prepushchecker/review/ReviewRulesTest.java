package com.github.prepushchecker.review;

import junit.framework.TestCase;

import java.util.List;
import java.util.regex.Pattern;

public class ReviewRulesTest extends TestCase {

    public void testFrontMatterInlineLists() {
        ReviewRules r = ReviewRules.parse("""
            ---
            mandatory: true
            paths: ["**/testcases/**/*.java", 'src/**']
            exclude: [**/generated/**]
            ---
            Description must match steps.
            """);
        assertEquals(Boolean.TRUE, r.mandatory());
        assertEquals(List.of("**/testcases/**/*.java", "src/**"), r.includeGlobs());
        assertEquals(List.of("**/generated/**"), r.excludeGlobs());
        assertEquals("Description must match steps.", r.body());
        assertTrue(r.inScope("crm/testcases/leads/LeadTest.java"));
        assertTrue(r.inScope("src/a.md"));
        assertFalse(r.inScope("src/generated/X.java"));
        assertFalse(r.inScope("docs/readme.md"));
    }

    public void testBlockListAndComments() {
        ReviewRules r = ReviewRules.parse("""
            ---
            # who must review
            mandatory: no
            paths:
              - "*.java"
              - lib/**
            ---
            Rules
            """);
        assertEquals(Boolean.FALSE, r.mandatory());
        assertEquals(List.of("*.java", "lib/**"), r.includeGlobs());
        assertTrue(r.inScope("A.java"));
        assertFalse(r.inScope("x/A.java"));
    }

    public void testNoFrontMatterCoversEverything() {
        ReviewRules r = ReviewRules.parse("Just rules\n--- not front matter");
        assertNull(r.mandatory());
        assertTrue(r.inScope("any/file.txt"));
        assertTrue(r.body().startsWith("Just rules"));
    }

    public void testTemplateParses() {
        ReviewRules r = ReviewRules.parse(ReviewRules.template());
        // The template must not set the mandate: an explicit line would hide each developer's toggle.
        assertNull(r.mandatory());
        assertTrue(r.inScope("a/b/C.java"));
        assertFalse(r.inScope("a/generated/C.java"));
        assertTrue(r.body().contains("addRowToReport"));
    }

    public void testExtendedRegexMatchesLikeTheGlobs() {
        String ere = ReviewRules.toExtendedRegex(List.of("**/testcases/**/*.java", "*.md", "docs/"));
        Pattern p = Pattern.compile(ere);
        assertTrue(p.matcher("crm/testcases/x/LeadTest.java").matches());
        assertTrue(p.matcher("testcases/LeadTest.java").matches());
        assertFalse(p.matcher("crm/testcases/x/Lead.kt").matches());
        assertTrue(p.matcher("README.md").matches());
        assertFalse(p.matcher("a/README.md").matches());
        assertTrue(p.matcher("docs/a/b.txt").matches());
        assertEquals("", ReviewRules.toExtendedRegex(List.of()));
    }
}
