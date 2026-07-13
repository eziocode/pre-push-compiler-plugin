package com.github.prepushchecker;

import com.intellij.testFramework.LightVirtualFile;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    public void testSelectCommitRootsPrefersDeepestNestedRepo() {
        LightVirtualFile parentRoot = new LightVirtualFile("/repo");
        LightVirtualFile nestedRoot = new LightVirtualFile("/repo/submodule");
        LightVirtualFile committedFile = new LightVirtualFile("/repo/submodule/src/App.java");

        List<?> selected = CommitShaClipboardCheckinHandler.selectCommitRoots(
            List.of(parentRoot, nestedRoot),
            List.of(committedFile));

        assertEquals(List.of(nestedRoot), selected);
    }

    public void testPreferredRepositoryLookupLocationsUsesCommittedFilesFirst() {
        LightVirtualFile committedRoot = new LightVirtualFile("/repo/submodule");
        LightVirtualFile fallbackRoot = new LightVirtualFile("/repo");

        List<?> ordered = CommitShaClipboardCheckinHandler.preferredRepositoryLookupLocations(
            List.of(committedRoot),
            List.of(fallbackRoot, committedRoot)
        );

        // The committed file (/repo/submodule) is listed first. The enclosing parent
        // (/repo) is intentionally NOT appended: selectCommitRoots reduces the fallback
        // roots to the deepest one that owns the commit, so a nested repository is never
        // shadowed by its parent when HEAD is read (the 1.9.2 nested-repo fix).
        assertEquals(List.of(committedRoot), ordered);
    }

    public void testPreferredRepositoryLookupLocationsKeepsDeepestFallbackRepo() {
        LightVirtualFile parentRoot = new LightVirtualFile("/repo");
        LightVirtualFile nestedRoot = new LightVirtualFile("/repo/submodule");
        LightVirtualFile committedFile = new LightVirtualFile("/repo/submodule/src/App.java");

        List<?> ordered = CommitShaClipboardCheckinHandler.preferredRepositoryLookupLocations(
            List.of(),
            List.of(parentRoot, nestedRoot)
        );

        assertEquals(List.of(nestedRoot), ordered);

        List<?> withCommittedFile = CommitShaClipboardCheckinHandler.preferredRepositoryLookupLocations(
            List.of(committedFile),
            List.of(parentRoot, nestedRoot)
        );

        assertEquals(List.of(committedFile, nestedRoot), withCommittedFile);
    }

    public void testWatchStableShaEmitsInitialAndLaterRebasedSha() {
        String first = "1111111111111111111111111111111111111111";
        String rebased = "2222222222222222222222222222222222222222";
        String finalSha = "3333333333333333333333333333333333333333";
        List<String> reads = List.of(
            first, first, first,
            first,
            rebased, rebased, rebased,
            rebased,
            finalSha, finalSha, finalSha
        );
        AtomicInteger index = new AtomicInteger();
        List<String> emitted = new ArrayList<>();

        CommitShaClipboardCheckinHandler.watchStableSha(
            () -> reads.get(index.getAndIncrement()), reads.size(), 0,
            () -> {}, emitted::add);

        assertEquals(List.of(first, rebased, finalSha), emitted);
    }

    public void testWatchStableShaIgnoresInvalidReadsAndDuplicateStableSha() {
        String sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        List<String> reads = List.of(
            "not-a-sha", "not-a-sha",
            sha, sha, sha,
            sha, sha
        );
        AtomicInteger index = new AtomicInteger();
        List<String> emitted = new ArrayList<>();

        CommitShaClipboardCheckinHandler.watchStableSha(
            () -> reads.get(index.getAndIncrement()), reads.size(), 0,
            () -> {}, emitted::add);

        assertEquals(List.of(sha), emitted);
    }
}
