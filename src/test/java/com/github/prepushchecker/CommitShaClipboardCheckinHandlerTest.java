package com.github.prepushchecker;

import junit.framework.TestCase;

/**
 * Regression tests for the "After Commit" clipboard SHA path. These cover the
 * repository-agnostic root selection logic used to decide which git working tree
 * to read {@code HEAD} from, so the copied SHA belongs to the correct repository
 * (multi-root / submodule safe) rather than the first panel root that happens to
 * be returned.
 */
public class CommitShaClipboardCheckinHandlerTest extends TestCase {

    public void testIsUnderRootPathMatchesExactRoot() {
        assertTrue(CommitShaClipboardCheckinHandler.isUnderRootPath(
            "/repo", "/repo"));
    }

    public void testIsUnderRootPathMatchesNestedFile() {
        assertTrue(CommitShaClipboardCheckinHandler.isUnderRootPath(
            "/repo/src/App.java", "/repo"));
    }

    public void testIsUnderRootPathRejectsSiblingPrefix() {
        // "/repo-two/..." must NOT be considered under "/repo" — a naive startsWith
        // check would wrongly match and read HEAD from the wrong repository.
        assertFalse(CommitShaClipboardCheckinHandler.isUnderRootPath(
            "/repo-two/src/App.java", "/repo"));
    }

    public void testIsUnderRootPathHandlesTrailingSlashRoot() {
        assertTrue(CommitShaClipboardCheckinHandler.isUnderRootPath(
            "/repo/src/App.java", "/repo/"));
    }
}
