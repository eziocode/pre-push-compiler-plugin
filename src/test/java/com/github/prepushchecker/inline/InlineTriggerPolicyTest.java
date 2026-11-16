package com.github.prepushchecker.inline;

import com.github.prepushchecker.inline.InlineSuggestSettings.Trigger;
import com.github.prepushchecker.inline.InlineTriggerPolicy.EditorFacts;
import org.junit.Test;

import static com.github.prepushchecker.inline.InlineTriggerPolicy.Decision.CACHE_ONLY;
import static com.github.prepushchecker.inline.InlineTriggerPolicy.Decision.NONE;
import static com.github.prepushchecker.inline.InlineTriggerPolicy.Decision.REQUEST;
import static com.github.prepushchecker.inline.InlineTriggerPolicy.Event.BACKSPACE;
import static com.github.prepushchecker.inline.InlineTriggerPolicy.Event.ENTER;
import static com.github.prepushchecker.inline.InlineTriggerPolicy.Event.OTHER;
import static com.github.prepushchecker.inline.InlineTriggerPolicy.Event.TYPED;
import static com.github.prepushchecker.inline.InlineTriggerPolicy.decide;
import static com.github.prepushchecker.inline.InlineTriggerPolicy.eligible;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class InlineTriggerPolicyTest {

    private final EditorFacts ok = new EditorFacts(true, 1, false, true, false, false, true, false, false);

    @Test
    public void typedAndEnterRequestInAuto() {
        assertEquals(REQUEST, decide(TYPED, ok, true, Trigger.AUTO, false));
        assertEquals(REQUEST, decide(ENTER, ok, true, Trigger.AUTO, false));
    }

    @Test
    public void backspaceIsCacheOnly() {
        assertEquals(CACHE_ONLY, decide(BACKSPACE, ok, true, Trigger.AUTO, false));
        assertEquals(CACHE_ONLY, decide(BACKSPACE, ok, true, Trigger.MANUAL, true));
    }

    @Test
    public void otherNeverTriggers() {
        assertEquals(NONE, decide(OTHER, ok, true, Trigger.AUTO, false));
    }

    @Test
    public void manualOrPausedIsCacheOnly() {
        assertEquals(CACHE_ONLY, decide(TYPED, ok, true, Trigger.MANUAL, false));
        assertEquals(CACHE_ONLY, decide(TYPED, ok, true, Trigger.AUTO, true));
        assertEquals(CACHE_ONLY, decide(ENTER, ok, true, Trigger.MANUAL, false));
        assertEquals(CACHE_ONLY, decide(ENTER, ok, true, Trigger.AUTO, true));
    }

    @Test
    public void disabledOrIneligibleIsNone() {
        assertTrue(eligible(ok));
        assertEquals(NONE, decide(TYPED, ok, false, Trigger.AUTO, false));
        assertNone(new EditorFacts(false, 1, false, true, false, false, true, false, false));
        assertNone(new EditorFacts(true, 2, false, true, false, false, true, false, false));
        assertNone(new EditorFacts(true, 1, true, true, false, false, true, false, false));
        assertNone(new EditorFacts(true, 1, false, false, false, false, true, false, false));
        assertNone(new EditorFacts(true, 1, false, true, true, false, true, false, false));
        assertNone(new EditorFacts(true, 1, false, true, false, true, true, false, false));
        assertNone(new EditorFacts(true, 1, false, true, false, false, false, false, false));
        assertNone(new EditorFacts(true, 1, false, true, false, false, true, true, false));
        assertNone(new EditorFacts(true, 1, false, true, false, false, true, false, true));
    }

    private static void assertNone(EditorFacts f) {
        for (InlineTriggerPolicy.Event e : InlineTriggerPolicy.Event.values()) {
            assertEquals(f + " / " + e, NONE, decide(e, f, true, Trigger.AUTO, false));
        }
    }
}
