package com.github.prepushchecker.inline;

import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class InlinePromptBuilderTest {

    private final InlineContext ctx = new InlineContext("src/A.java", "JAVA", "int a = ", ";\n}",
        List.of(new InlineContext.Snippet("B.java", "class B { int size(); }")));

    @Test
    public void userPromptHasCursorBetweenPrefixAndSuffix() {
        var p = InlinePromptBuilder.build(ctx, new InlineSuggestSettings.State());
        assertTrue(p.user().contains("int a = <CURSOR>;\n}"));
        assertTrue(p.user().contains("--- B.java ---"));
        assertTrue(p.user().contains("File: src/A.java (JAVA)"));
        assertTrue(p.user().indexOf("--- B.java ---") < p.user().indexOf("--- current file ---"));
    }

    @Test
    public void systemPromptReflectsSettings() {
        var s = new InlineSuggestSettings.State();
        s.maxLines = 5;
        s.extraInstructions = "Use tabs.";
        String sys = InlinePromptBuilder.build(ctx, s).system();
        assertTrue(sys.contains("at most 5 lines"));
        assertTrue(sys.contains("Use tabs."));
        s.multiLine = false;
        assertTrue(InlinePromptBuilder.build(ctx, s).system().contains("one line only"));
    }
}
