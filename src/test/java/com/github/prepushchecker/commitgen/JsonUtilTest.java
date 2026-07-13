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

    /**
     * Regression: a chat/completions response can carry a stray "content" key
     * (e.g. under prompt_filter_results) BEFORE the real message content.
     * The recursive first-match search picks the wrong one; the explicit path
     * must select choices[0].message.content. This mirrors the fix applied to
     * CodexCliProvider, which previously used the recursive search and returned
     * the wrong value (surfacing as a failed/garbled generation).
     */
    public void testChatCompletionsIgnoresStrayContentKey() {
        String json = """
            {
              "prompt_filter_results": [
                {"content_filter_results": {"content": "filtered"}}
              ],
              "choices": [
                {"message": {"role": "assistant", "content": "feat: real message"}}
              ]
            }
            """;

        // The old recursive search would match the stray filter "content" first.
        assertEquals("filtered", JsonUtil.extractString(json, "content"));

        // The explicit path used by the providers selects the correct value.
        assertEquals(
            "feat: real message",
            JsonUtil.extractStringAtPath(json, "choices", 0, "message", "content")
        );
    }
}
