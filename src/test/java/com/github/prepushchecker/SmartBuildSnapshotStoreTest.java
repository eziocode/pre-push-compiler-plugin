package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class SmartBuildSnapshotStoreTest extends BasePlatformTestCase {
    private static String key(String branch) {
        return BuildFingerprint.encodeBranchKey(List.of(new BuildFingerprint.BranchRef("/repo", branch)));
    }

    public void testFindReturnsRecordedVerdict() {
        SmartBuildSnapshotStore store = new SmartBuildSnapshotStore();
        store.record(key("main"), "fp1", List.of("A.java:1: error"), SmartBuildSnapshotStore.Source.SMART, 1L);
        SmartBuildSnapshotStore.Entry entry = store.find("fp1", 2L);
        assertNotNull(entry);
        assertEquals(1, entry.errorCount);
        assertFalse(entry.isClean());
        assertNull(store.find("other", 2L));
    }

    public void testRecordingSameFingerprintReplacesEntry() {
        SmartBuildSnapshotStore store = new SmartBuildSnapshotStore();
        store.record(key("main"), "fp1", List.of("err"), SmartBuildSnapshotStore.Source.SMART, 1L);
        store.record(key("main"), "fp1", List.of(), SmartBuildSnapshotStore.Source.IDE_BUILD, 2L);
        assertEquals(1, store.size());
        assertTrue(store.find("fp1", 3L).isClean());
    }

    public void testKeepsOnlyRecentFingerprintsPerBranch() {
        SmartBuildSnapshotStore store = new SmartBuildSnapshotStore();
        for (int i = 0; i < SmartBuildSnapshotStore.MAX_PER_BRANCH + 2; i++) {
            store.record(key("main"), "fp" + i, List.of(), SmartBuildSnapshotStore.Source.SMART, i);
        }
        store.record(key("feature"), "f0", List.of(), SmartBuildSnapshotStore.Source.SMART, 100L);
        assertEquals(SmartBuildSnapshotStore.MAX_PER_BRANCH + 1, store.size());
        assertNull(store.find("fp0", 200L));
        assertNotNull(store.find("fp" + (SmartBuildSnapshotStore.MAX_PER_BRANCH + 1), 200L));
        assertNotNull("other branches are untouched", store.find("f0", 200L));
    }

    public void testCapsDetachedHeadSnapshots() {
        SmartBuildSnapshotStore store = new SmartBuildSnapshotStore();
        for (int i = 0; i < SmartBuildSnapshotStore.MAX_DETACHED + 3; i++) {
            store.record(key(BuildFingerprint.DETACHED_PREFIX + i), "d" + i, List.of(),
                SmartBuildSnapshotStore.Source.SMART, i);
        }
        assertEquals(SmartBuildSnapshotStore.MAX_DETACHED, store.size());
    }

    public void testPruneDropsOnlyDeletedBranches() {
        SmartBuildSnapshotStore store = new SmartBuildSnapshotStore();
        store.record(key("main"), "m", List.of(), SmartBuildSnapshotStore.Source.SMART, 1L);
        store.record(key("gone"), "g", List.of(), SmartBuildSnapshotStore.Source.SMART, 2L);
        store.record(key(BuildFingerprint.DETACHED_PREFIX + "abc1234"), "d", List.of(),
            SmartBuildSnapshotStore.Source.SMART, 3L);
        Set<String> existing = Set.of("main");
        assertEquals(1, store.pruneDeletedBranches(ref -> existing.contains(ref.branch())));
        assertNotNull(store.find("m", 4L));
        assertNull(store.find("g", 4L));
        assertNotNull("detached snapshots are not branch-pruned", store.find("d", 4L));
    }

    public void testStoredErrorsAreCapped() {
        SmartBuildSnapshotStore store = new SmartBuildSnapshotStore();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < SmartBuildSnapshotStore.MAX_STORED_ERRORS + 10; i++) {
            errors.add("x".repeat(SmartBuildSnapshotStore.MAX_STORED_ERROR_CHARS + 50));
        }
        store.record(key("main"), "fp", errors, SmartBuildSnapshotStore.Source.SMART, 1L);
        SmartBuildSnapshotStore.Entry entry = store.find("fp", 2L);
        assertEquals(errors.size(), entry.errorCount);
        assertEquals(SmartBuildSnapshotStore.MAX_STORED_ERRORS + 1, entry.errorList().size());
        assertTrue(entry.errorList().get(0).length() < SmartBuildSnapshotStore.MAX_STORED_ERROR_CHARS + 50);
    }

    public void testStateSurvivesXmlRoundTrip() {
        SmartBuildSnapshotStore store = new SmartBuildSnapshotStore();
        store.record(key("main"), "fp", List.of("A.java:3: error"), SmartBuildSnapshotStore.Source.AUTOMAKE, 7L);
        Element xml = XmlSerializer.serialize(store.getState());
        SmartBuildSnapshotStore.StoreState restored =
            XmlSerializer.deserialize(xml, SmartBuildSnapshotStore.StoreState.class);
        SmartBuildSnapshotStore reloaded = new SmartBuildSnapshotStore();
        reloaded.loadState(restored);
        SmartBuildSnapshotStore.Entry entry = reloaded.find("fp", 8L);
        assertNotNull(entry);
        assertEquals(List.of("A.java:3: error"), entry.errorList());
        assertEquals(key("main"), entry.branchKey);
        assertEquals(7L, entry.finishedAt);
    }
}
