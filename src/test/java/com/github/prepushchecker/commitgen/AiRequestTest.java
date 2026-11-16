package com.github.prepushchecker.commitgen;

import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AiRequestTest {

    @Test
    public void structuredOnlyForReviewAndFix() {
        assertFalse(AiRequest.commitMessage("s", "u").structured());
        assertTrue(AiRequest.review("s", "u").structured());
        assertTrue(AiRequest.fix("s", "u").structured());
        assertFalse(AiRequest.completion("s", "u", null, AiRequest.COMPLETION_FAST_TIMEOUT).structured());
    }

    @Test
    public void completionDefaults() {
        AiRequest r = AiRequest.completion("s", "u", "m1", AiRequest.COMPLETION_FAST_TIMEOUT);
        assertEquals(256, r.maxOutputTokens());
        assertEquals(Duration.ofSeconds(15), r.timeout());
        assertEquals("m1", r.modelOr("fallback"));
        assertEquals("fallback", AiRequest.commitMessage("s", "u").modelOr("fallback"));
        assertEquals("fallback", AiRequest.completion("s", "u", "  ", r.timeout()).modelOr("fallback"));
        assertEquals("m1", r.withTimeout(Duration.ofSeconds(60)).model());
        assertEquals(Duration.ofSeconds(60), AiRequest.COMPLETION_SLOW_TIMEOUT);
    }
}
