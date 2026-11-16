package com.github.prepushchecker.inline;

import static com.github.prepushchecker.inline.InlineSuggestionCleaner.clean;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class InlineSuggestionCleanerTest {

    @Test public void stripsFence() { assertEquals("b + c", clean("```java\nb + c\n```", "int a = ", ";", true, 8)); }
    @Test public void stripsEchoedPrefix() { assertEquals("b + c", clean("int a = b + c", "x();\nint a = ", ";", true, 8)); }
    @Test public void singleLineCuts() { assertEquals("b", clean("b\nc\nd", "", "", false, 8)); }
    @Test public void capsAtMaxLines() { assertEquals("1\n2", clean("1\n2\n3\n4", "", "", true, 2)); }
    @Test public void dropsRepeatedSuffix() { assertEquals("foo()", clean("foo();", "x = ", ";\n}", true, 8)); }

    @Test
    public void emptyIsNothing() {
        assertEquals("", clean("  \n", "", "", true, 8));
        assertEquals("", clean(null, "", "", true, 8));
    }

    @Test public void keepsLeadingIndent() { assertEquals("    return 1;", clean("    return 1;", "{\n", "\n}", true, 8)); }

    @Test
    public void oversizedProseAndFences() {
        String raw = "Here is the code:\n```java\n" + "line;\n".repeat(200) + "```\nHope this helps!";
        String out = clean(raw, "", "", true, 8);
        assertEquals(8, out.split("\n").length);
        assertFalse(out.contains("```"));
        assertFalse(out.contains("Hope"));
        assertFalse(out.contains("Here is"));
    }
}
