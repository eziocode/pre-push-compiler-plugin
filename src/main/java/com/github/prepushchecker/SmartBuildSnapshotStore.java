package com.github.prepushchecker;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.annotations.XCollection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Per-branch compile verdicts keyed by {@link BuildFingerprint working-tree content}.
 *
 * <p>Stored in the workspace file so verdicts survive restarts and branch round-trips.
 * Entries for a branch are only dropped when the branch is deleted, or when the branch
 * exceeds {@link #MAX_PER_BRANCH} fingerprints (oldest first), or when the whole store
 * exceeds {@link #MAX_ENTRIES}. Error lists are capped hard so the workspace file stays small.
 */
@Service(Service.Level.PROJECT)
@State(name = "PrePushSmartBuildSnapshots", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class SmartBuildSnapshotStore implements PersistentStateComponent<SmartBuildSnapshotStore.StoreState> {
    static final int MAX_PER_BRANCH = 3;
    static final int MAX_DETACHED = 5;
    static final int MAX_ENTRIES = 64;
    static final int MAX_STORED_ERRORS = 30;
    static final int MAX_STORED_ERROR_CHARS = 1_000;

    enum Source { SMART, IDE_BUILD, AUTOMAKE }

    /** XML-serialisable snapshot row. Public mutable fields are required by the serializer. */
    public static final class Entry {
        public String branchKey = "";
        public String fingerprint = "";
        @XCollection(style = XCollection.Style.v2)
        public List<String> errors = new ArrayList<>();
        public int errorCount;
        public long finishedAt;
        public long lastUsedAt;
        public String source = Source.IDE_BUILD.name();

        boolean isClean() {
            return errorCount == 0;
        }

        @NotNull List<String> errorList() {
            return errors == null ? List.of() : List.copyOf(errors);
        }
    }

    public static final class StoreState {
        @XCollection(style = XCollection.Style.v2)
        public List<Entry> entries = new ArrayList<>();
    }

    private StoreState state = new StoreState();

    public SmartBuildSnapshotStore() {
    }

    static @NotNull SmartBuildSnapshotStore getInstance(@NotNull Project project) {
        return project.getService(SmartBuildSnapshotStore.class);
    }

    @Override
    public synchronized @NotNull StoreState getState() {
        return state;
    }

    @Override
    public synchronized void loadState(@NotNull StoreState loaded) {
        state = loaded;
        if (state.entries == null) state.entries = new ArrayList<>();
        state.entries.removeIf(e -> e == null || e.fingerprint == null || e.fingerprint.isEmpty());
        enforceCaps();
    }

    /** Returns the verdict recorded for {@code fingerprint}, marking it as recently used. */
    synchronized @Nullable Entry find(@NotNull String fingerprint, long now) {
        for (Entry entry : state.entries) {
            if (fingerprint.equals(entry.fingerprint)) {
                entry.lastUsedAt = now;
                return copy(entry);
            }
        }
        return null;
    }

    synchronized void record(
        @NotNull String branchKey,
        @NotNull String fingerprint,
        @NotNull List<String> rawErrors,
        @NotNull Source source,
        long now
    ) {
        state.entries.removeIf(e -> fingerprint.equals(e.fingerprint));
        Entry entry = new Entry();
        entry.branchKey = branchKey;
        entry.fingerprint = fingerprint;
        entry.errorCount = rawErrors.size();
        entry.errors = compact(rawErrors);
        entry.finishedAt = now;
        entry.lastUsedAt = now;
        entry.source = source.name();
        state.entries.add(entry);
        enforceCaps();
    }

    /**
     * Drops entries whose branch no longer exists. {@code branchExists} is only asked about
     * roots the caller knows; unknown roots keep their entries.
     */
    synchronized int pruneDeletedBranches(@NotNull Predicate<BuildFingerprint.BranchRef> branchExists) {
        int before = state.entries.size();
        state.entries.removeIf(entry -> {
            for (BuildFingerprint.BranchRef ref : BuildFingerprint.decodeBranchKey(entry.branchKey)) {
                if (!ref.isDetached() && !branchExists.test(ref)) return true;
            }
            return false;
        });
        return before - state.entries.size();
    }

    synchronized int size() {
        return state.entries.size();
    }

    synchronized void clear() {
        state.entries.clear();
    }

    private void enforceCaps() {
        // Per branch: keep the most recently used MAX_PER_BRANCH fingerprints.
        Map<String, Integer> perBranch = new HashMap<>();
        List<Entry> byRecency = new ArrayList<>(state.entries);
        byRecency.sort((a, b) -> Long.compare(b.lastUsedAt, a.lastUsedAt));
        List<Entry> keep = new ArrayList<>();
        int detached = 0;
        for (Entry entry : byRecency) {
            boolean isDetached = BuildFingerprint.decodeBranchKey(entry.branchKey).stream()
                .anyMatch(BuildFingerprint.BranchRef::isDetached);
            if (isDetached && ++detached > MAX_DETACHED) continue;
            int count = perBranch.merge(entry.branchKey == null ? "" : entry.branchKey, 1, Integer::sum);
            if (count > MAX_PER_BRANCH) continue;
            if (keep.size() >= MAX_ENTRIES) continue;
            keep.add(entry);
        }
        if (keep.size() != state.entries.size()) {
            Iterator<Entry> it = state.entries.iterator();
            while (it.hasNext()) {
                if (!keep.contains(it.next())) it.remove();
            }
        }
    }

    private static @NotNull List<String> compact(@NotNull List<String> errors) {
        int kept = Math.min(errors.size(), MAX_STORED_ERRORS);
        List<String> result = new ArrayList<>(kept + 1);
        for (int i = 0; i < kept; i++) {
            String value = errors.get(i) == null ? "" : errors.get(i);
            result.add(value.length() <= MAX_STORED_ERROR_CHARS
                ? value
                : value.substring(0, MAX_STORED_ERROR_CHARS) + "... [truncated]");
        }
        if (errors.size() > kept) {
            result.add(CompilationErrorService.omittedErrorsMessage(errors.size() - kept));
        }
        return result;
    }

    private static @NotNull Entry copy(@NotNull Entry source) {
        Entry copy = new Entry();
        copy.branchKey = source.branchKey;
        copy.fingerprint = source.fingerprint;
        copy.errors = source.errorList();
        copy.errorCount = source.errorCount;
        copy.finishedAt = source.finishedAt;
        copy.lastUsedAt = source.lastUsedAt;
        copy.source = source.source;
        return copy;
    }
}
