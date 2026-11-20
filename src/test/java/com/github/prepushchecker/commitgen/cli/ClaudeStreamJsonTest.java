package com.github.prepushchecker.commitgen.cli;

import junit.framework.TestCase;

public class ClaudeStreamJsonTest extends TestCase {

    private static final String D1 = "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,"
        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"feat: add\"}}}";
    private static final String D2 = "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,"
        + "\"delta\":{\"type\":\"text_delta\",\"text\":\" cache\"}}}";

    public void testDelta() {
        assertEquals("feat: add", ClaudeStreamJson.delta(D1));
        assertNull(ClaudeStreamJson.delta("{\"type\":\"system\",\"subtype\":\"init\"}"));
        assertNull(ClaudeStreamJson.delta("plain text"));
    }

    public void testFinalTextPrefersResult() {
        String out = "{\"type\":\"system\",\"subtype\":\"init\"}\n" + D1 + "\n" + D2 + "\n"
            + "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"feat: add cache\\n\\nBody\"}\n";
        assertEquals("feat: add cache\n\nBody", ClaudeStreamJson.finalText(out));
    }

    public void testFinalTextFallsBackToDeltas() {
        assertEquals("feat: add cache", ClaudeStreamJson.finalText(D1 + "\n" + D2 + "\n"));
    }

    public void testPlainTextIsNotStreamOutput() {
        assertNull(ClaudeStreamJson.finalText("feat: plain\n"));
    }

    public void testErrorResultThrows() {
        try {
            ClaudeStreamJson.finalText("{\"type\":\"result\",\"is_error\":true,\"result\":\"Not logged in\"}");
            fail();
        } catch (RuntimeException e) {
            assertEquals("Not logged in", e.getMessage());
        }
    }
}
