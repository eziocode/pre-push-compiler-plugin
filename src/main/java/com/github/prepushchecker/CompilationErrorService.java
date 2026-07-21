package com.github.prepushchecker;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Project-level service that holds the most recent compilation error list produced
 * by either the pre-push handler or the tool-window "Run Check" action.
 *
 * <p>Also acts as a freshness cache: when a compile completes, the scope (project vs.
 * file list) and a snapshot of the input files' {@link VirtualFile#getTimeStamp()} is
 * recorded, so subsequent checks over the same (or subset) scope can reuse the result
 * when nothing on disk has moved.
 *
 * <p>Listeners are notified on the EDT whenever the error list changes.
 */
@Service(Service.Level.PROJECT)
public final class CompilationErrorService {

    private static final Logger LOG = Logger.getInstance(CompilationErrorService.class);
    static final int MAX_RETAINED_ERRORS = 200;
    private static final int MAX_RETAINED_ERROR_CHARS = 4_000;

    private volatile List<String> errors = Collections.emptyList();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    // Freshness cache state. Guarded by `this`.
    private long lastComputedAt = 0L;
    private boolean lastScopeProject = false;
    private Map<String, Long> lastFileStamps = Collections.emptyMap();
    private String lastPrePushKey = "";
    private List<String> lastPrePushErrors = Collections.emptyList();

    // Clean-commit ledger: (repoRoot, headSha) -> scope under which that snapshot was
    // verified clean. Bounded LRU. We only ever store *clean* results — a missing entry
    // means "unknown, must recompile". This is the foundation for skipping post-rebase
    // recompiles when the resulting commit was already verified by an earlier check.
    static final int CLEAN_LEDGER_MAX_ENTRIES = 256;
    /** Scope tag recorded alongside a clean ledger entry. */
    enum CompileScopeKind { FILE_SCOPE, PROJECT }
    private final LinkedHashMap<String, CompileScopeKind> cleanLedger =
        new LinkedHashMap<>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CompileScopeKind> eldest) {
                return size() > CLEAN_LEDGER_MAX_ENTRIES;
            }
        };

    public static CompilationErrorService getInstance(@NotNull Project project) {
        return project.getService(CompilationErrorService.class);
    }

    public void setErrors(@NotNull List<String> newErrors) {
        List<String> snapshot = compactErrors(newErrors);
        if (snapshot.equals(this.errors)) {
            return;
        }
        this.errors = snapshot;
        ApplicationManager.getApplication().invokeLater(this::fireListeners);
    }

    static @NotNull List<String> compactErrors(@NotNull List<String> newErrors) {
        if (newErrors.isEmpty()) {
            return Collections.emptyList();
        }

        int retained = Math.min(newErrors.size(), MAX_RETAINED_ERRORS);
        List<String> snapshot = new ArrayList<>(retained + (newErrors.size() > retained ? 1 : 0));
        for (int i = 0; i < retained; i++) {
            snapshot.add(compactError(newErrors.get(i)));
        }
        if (newErrors.size() > retained) {
            snapshot.add(omittedErrorsMessage(newErrors.size() - retained));
        }
        return List.copyOf(snapshot);
    }

    static @NotNull String compactError(@Nullable String error) {
        String value = error == null ? "" : error;
        if (value.length() <= MAX_RETAINED_ERROR_CHARS) {
            return value;
        }
        return value.substring(0, MAX_RETAINED_ERROR_CHARS)
            + "... [truncated to limit memory usage]";
    }

    static @NotNull String omittedErrorsMessage(int omittedCount) {
        return "... " + omittedCount + " more compiler error(s) omitted to limit memory usage.";
    }

    public @NotNull List<String> getErrors() {
        return errors;
    }

    /**
     * Record the outcome of a compile. {@code stamps} should be the per-file timestamps
     * observed just before (or during) compilation; pass an empty map for a full project
     * compile (in which case any file modified after {@code now} invalidates the cache).
     */
    public synchronized void recordCompletion(
        boolean projectScope,
        @NotNull Map<String, Long> stamps,
        @NotNull List<String> newErrors
    ) {
        this.lastComputedAt = System.currentTimeMillis();
        this.lastScopeProject = projectScope;
        this.lastFileStamps = stamps.isEmpty() ? Collections.emptyMap() : Map.copyOf(stamps);
        setErrors(newErrors);
    }

    /**
     * Invalidate the freshness cache (e.g. if the user expects the next check to
     * actually run the compiler).
     */
    public synchronized void invalidateFreshness() {
        this.lastComputedAt = 0L;
        this.lastScopeProject = false;
        this.lastFileStamps = Collections.emptyMap();
        this.lastPrePushKey = "";
        this.lastPrePushErrors = Collections.emptyList();
        this.cleanLedger.clear();
    }

    /**
     * Return the cached error list if it is still valid for every file in {@code files}.
     * "Valid" means either:
     *   - the last compile was project-scope and no requested file has a timestamp newer
     *     than {@code lastComputedAt}; or
     *   - the last compile was file-scope, every requested file was part of the cached
     *     set, and each file's current {@link VirtualFile#getTimeStamp()} equals the
     *     recorded value.
     * Returns {@code null} if the cache cannot be reused.
     */
    public synchronized @Nullable List<String> tryReuse(@NotNull Collection<VirtualFile> files) {
        if (lastComputedAt == 0L) return null;
        if (lastScopeProject) {
            for (VirtualFile f : files) {
                if (f == null || !f.isValid()) return null;
                if (f.getTimeStamp() > lastComputedAt) return null;
            }
            return errors;
        }
        if (lastFileStamps.isEmpty()) return null;
        for (VirtualFile f : files) {
            if (f == null || !f.isValid()) return null;
            Long cached = lastFileStamps.get(f.getPath());
            if (cached == null) return null;
            if (f.getTimeStamp() != cached) return null;
        }
        return errors;
    }

    public synchronized @Nullable List<String> tryReusePrePushResult(@NotNull String key) {
        return !key.isBlank() && key.equals(lastPrePushKey) ? lastPrePushErrors : null;
    }

    public void recordPrePushResult(@NotNull String key, @NotNull List<String> newErrors) {
        List<String> snapshot = compactErrors(newErrors);
        synchronized (this) {
            this.lastPrePushKey = key;
            this.lastPrePushErrors = snapshot;
        }
        setErrors(snapshot);
    }

    /**
     * Record that {@code repoRoot} at {@code headSha} compiled cleanly under
     * {@code scope}. Callers must only invoke this after verifying:
     * <ul>
     *   <li>all open documents were saved before compile started;</li>
     *   <li>the working tree was clean both immediately before and immediately
     *       after compile, and the HEAD SHA did not change during compile;</li>
     *   <li>compile produced zero errors.</li>
     * </ul>
     * No-ops on blank inputs.
     */
    public synchronized void recordCleanCommit(
        @NotNull String repoRoot,
        @NotNull String headSha,
        @NotNull CompileScopeKind scope
    ) {
        if (repoRoot.isBlank() || headSha.isBlank()) return;
        cleanLedger.put(ledgerKey(repoRoot, headSha), scope);
    }

    /**
     * Returns the scope under which {@code (repoRoot, headSha)} was last
     * recorded clean, or {@code null} if no entry exists. The caller is
     * responsible for deciding whether the recorded scope is sufficient for
     * the current check (e.g. a FILE_SCOPE hit does not satisfy a check that
     * requires PROJECT-level confidence).
     */
    public synchronized @Nullable CompileScopeKind tryReuseCleanCommit(
        @NotNull String repoRoot,
        @NotNull String headSha
    ) {
        if (repoRoot.isBlank() || headSha.isBlank()) return null;
        return cleanLedger.get(ledgerKey(repoRoot, headSha));
    }

    /** Visible for tests / diagnostics. */
    synchronized int cleanLedgerSize() {
        return cleanLedger.size();
    }

    private static String ledgerKey(String repoRoot, String headSha) {
        return repoRoot + '\u0000' + headSha;
    }

    /** Capture timestamps for a collection of files. */
    public static @NotNull Map<String, Long> snapshotStamps(@NotNull Collection<VirtualFile> files) {
        if (files.isEmpty()) return Collections.emptyMap();
        Map<String, Long> m = new HashMap<>(files.size() * 2);
        for (VirtualFile f : files) {
            if (f != null && f.isValid()) {
                m.put(f.getPath(), f.getTimeStamp());
            }
        }
        return m;
    }

    public void addListener(@NotNull Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull Runnable listener) {
        listeners.remove(listener);
    }

    private void fireListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Exception e) {
                LOG.warn("CompilationErrorService listener threw", e);
            }
        }
    }
}
