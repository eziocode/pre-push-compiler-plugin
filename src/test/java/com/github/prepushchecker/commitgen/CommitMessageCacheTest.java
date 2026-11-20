package com.github.prepushchecker.commitgen;

import junit.framework.TestCase;

import java.util.concurrent.CompletableFuture;

public class CommitMessageCacheTest extends TestCase {

    @Override
    protected void tearDown() throws Exception {
        CommitMessageCache.clear();
        super.tearDown();
    }

    public void testKeyDependsOnDiffRulesAndProviderSettings() {
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        String k = CommitMessageCache.key("rules", "diff", s);
        assertEquals(k, CommitMessageCache.key("rules", "diff", s));
        assertFalse(k.equals(CommitMessageCache.key("rules", "diff2", s)));
        assertFalse(k.equals(CommitMessageCache.key("rules2", "diff", s)));
        s.claudeModel = "opus";
        assertFalse(k.equals(CommitMessageCache.key("rules", "diff", s)));
    }

    public void testRunningGenerationIsCachedOnSuccess() {
        CompletableFuture<String> f = new CompletableFuture<>();
        assertTrue(CommitMessageCache.startRunning("k", f));
        assertFalse(CommitMessageCache.startRunning("k", new CompletableFuture<>()));
        assertSame(f, CommitMessageCache.running("k"));
        f.complete("feat: x");
        assertNull(CommitMessageCache.running("k"));
        assertEquals("feat: x", CommitMessageCache.get("k"));
    }

    public void testFailedGenerationIsNotCached() {
        CompletableFuture<String> f = new CompletableFuture<>();
        CommitMessageCache.startRunning("k2", f);
        f.completeExceptionally(new RuntimeException("x"));
        assertNull(CommitMessageCache.get("k2"));
        assertNull(CommitMessageCache.running("k2"));
    }
}
