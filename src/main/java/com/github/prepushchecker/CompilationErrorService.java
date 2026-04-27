package com.github.prepushchecker;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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

    private volatile List<String> errors = Collections.emptyList();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    // Freshness cache state. Guarded by `this`.
    private long lastComputedAt = 0L;
    private boolean lastScopeProject = false;
    private Map<String, Long> lastFileStamps = Collections.emptyMap();

    public static CompilationErrorService getInstance(@NotNull Project project) {
        return project.getService(CompilationErrorService.class);
    }

    public void setErrors(@NotNull List<String> newErrors) {
        List<String> snapshot = List.copyOf(newErrors);
        if (snapshot.equals(this.errors)) {
            return;
        }
        this.errors = snapshot;
        ApplicationManager.getApplication().invokeLater(this::fireListeners);
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
