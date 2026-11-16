package com.github.prepushchecker.inline;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class InlineSuggestionCacheTest {

    @Test
    public void evictsOldestBeyondCapacity() {
        var c = new InlineSuggestionCache();
        for (int i = 0; i <= InlineSuggestionCache.CAPACITY; i++) c.put("f", "p" + i, "s" + i);
        assertNull(c.get("f", "p0"));
        assertEquals("s20", c.get("f", "p20"));
        assertEquals("s1", c.get("f", "p1"));
    }

    @Test
    public void accessRefreshesEntry() {
        var c = new InlineSuggestionCache();
        for (int i = 0; i < InlineSuggestionCache.CAPACITY; i++) c.put("f", "p" + i, "s" + i);
        assertEquals("s0", c.get("f", "p0"));          // p0 is now most recently used
        c.put("f", "new", "n");
        assertEquals("s0", c.get("f", "p0"));
        assertNull(c.get("f", "p1"));
    }

    @Test
    public void keyUsesPrefixTailAndPath() {
        var c = new InlineSuggestionCache();
        c.put("f", "x".repeat(500) + "tail", "s");
        assertEquals("s", c.get("f", "y".repeat(300) + "x".repeat(196) + "tail"));
        assertNull(c.get("g", "x".repeat(500) + "tail"));
    }

    @Test
    public void pathAndPrefixDoNotCollide() {
        var c = new InlineSuggestionCache();
        c.put("ab", "c", "1");
        assertNull(c.get("a", "bc"));
    }

    @Test
    public void clearEmpties() {
        var c = new InlineSuggestionCache();
        c.put("f", "p", "s");
        c.clear();
        assertNull(c.get("f", "p"));
    }
}
