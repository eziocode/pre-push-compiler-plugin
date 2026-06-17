package com.github.prepushchecker.commitgen;

import junit.framework.TestCase;

public class JsonUtilTest extends TestCase {
    public void testExtractStringAtPathSelectsProviderMessageContent() {
        String json = """
            {
              "metadata": {"content": "wrong"},
              "choices": [
                {"message": {"content": "right\\nvalue"}}
              ]
            }
            """;

        assertEquals(
            "right\nvalue",
            JsonUtil.extractStringAtPath(json, "choices", 0, "message", "content")
        );
    }

    public void testExtractStringAtPathHandlesEscapedUnicode() {
        String json = "{\"content\":[{\"text\":\"feat: add \\\"ok\\\" \\u2713\"}]}";

        assertEquals(
            "feat: add \"ok\" \u2713",
            JsonUtil.extractStringAtPath(json, "content", 0, "text")
        );
    }

    public void testLegacyExtractStringSearchesNestedObjects() {
        String json = "{\"outer\":[{\"text\":\"hello\"}]}";

        assertEquals("hello", JsonUtil.extractString(json, "text"));
    }
}
