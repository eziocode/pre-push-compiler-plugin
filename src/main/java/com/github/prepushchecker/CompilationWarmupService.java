package com.github.prepushchecker;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ide.util.PropertiesComponent;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a debounced background compile of files the user has recently saved so that
 * {@link CompilationErrorService#tryReuse} almost always hits at push time — turning
 * most pushes into zero-cost operations even on large projects.
 *
 * <p>Guarded against noise:
 * <ul>
 *   <li>Debounced (see {@link #DEBOUNCE_MS}); typing quickly coalesces into one compile.</li>
 *   <li>Skipped in {@link PowerSaveMode} and while {@link DumbService#isDumb} is true.</li>
 *   <li>Single-flight per project; overlapping requests are coalesced after the current
 *       compile finishes.</li>
 *   <li>Disabled via the {@code prepushchecker.warmup.enabled} registry key.</li>
 * </ul>
 */
@Service(Service.Level.PROJECT)
public final class CompilationWarmupService implements Disposable {

    private static final Logger LOG = Logger.getInstance(CompilationWarmupService.class);
    private static final String REGISTRY_KEY = "prepushchecker.warmup.enabled";
    private static final String INITIALIZED_KEY = "prepushchecker.initialized";
    private static final long DEBOUNCE_MS = 4_000L;
    /** Minimum gap between two repo-change-driven warmups, to absorb bursts. */
    private static final long REPO_CHANGE_COOLDOWN_MS = 30_000L;

    private final Project project;
    private final Set<VirtualFile> dirty = Collections.synchronizedSet(new LinkedHashSet<>());
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicBoolean rerun = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong lastRepoChangeTriggerNanos =
        new java.util.concurrent.atomic.AtomicLong(0L);
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PrePushChecker-Warmup");
            t.setDaemon(true);
            return t;
        });
    private volatile ScheduledFuture<?> pending;

    public CompilationWarmupService(@NotNull Project project) {
        this.project = project;
    }

    public static CompilationWarmupService getInstance(@NotNull Project project) {
        return project.getService(CompilationWarmupService.class);
    }

    /** Listener on the app-level message bus; routes saves into per-project dirty sets. */
    public static final class SaveListener implements FileDocumentManagerListener {
        @Override
        public void beforeDocumentSaving(@NotNull Document document) {
            VirtualFile file = FileDocumentManager.getInstance().getFile(document);
            if (file == null || !file.isInLocalFileSystem()) return;
            if (!PushValidationPaths.isCompilableSource(file.getPath())) return;
            // Fan out to every open project that contains this file.
            for (Project project : com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()) {
                if (project.isDisposed()) continue;
                CompilationWarmupService svc = project.getServiceIfCreated(CompilationWarmupService.class);
                if (svc == null) continue;
                if (com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isInSourceContent(file)) {
                    svc.markDirty(file);
                }
            }
        }
    }

    private void markDirty(VirtualFile file) {
        if (!isEnabled()) return;
        dirty.add(file);
        schedule();
    }

    private boolean isEnabled() {
        try {
            return Registry.is(REGISTRY_KEY, true);
        } catch (Throwable t) {
            return true;
        }
    }

    private void schedule() {
        ScheduledFuture<?> prev = pending;
        if (prev != null) prev.cancel(false);
        try {
            pending = scheduler.schedule(this::fire, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // disposed; drop silently
        }
    }

    private void fire() {
        if (project.isDisposed() || !isEnabled()) return;
        if (com.intellij.ide.PowerSaveMode.isEnabled()) return;
        if (DumbService.getInstance(project).isDumb()) {
            // Try again after indexing finishes.
            DumbService.getInstance(project).runWhenSmart(this::schedule);
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            rerun.set(true);
            return;
        }

        VirtualFile[] snapshot;
        synchronized (dirty) {
            if (dirty.isEmpty()) {
                inFlight.set(false);
                return;
            }
            snapshot = dirty.toArray(VirtualFile.EMPTY_ARRAY);
            dirty.clear();
        }

        ApplicationManager.getApplication().invokeLater(() -> runCompile(snapshot),
            ModalityState.defaultModalityState());
    }

    private void runCompile(VirtualFile[] files) {
        try {
            if (project.isDisposed()) return;
            CompilerManager cm = CompilerManager.getInstance(project);
            // Keep only files that are still valid and in a source root.
            com.intellij.openapi.roots.ProjectFileIndex idx =
                com.intellij.openapi.roots.ProjectFileIndex.getInstance(project);
            java.util.List<VirtualFile> live = new java.util.ArrayList<>(files.length);
            for (VirtualFile f : files) {
                if (f != null && f.isValid() && idx.isInSourceContent(f)) live.add(f);
            }
            if (live.isEmpty()) {
                finish();
                return;
            }
            VirtualFile[] arr = live.toArray(VirtualFile.EMPTY_ARRAY);
            com.intellij.openapi.compiler.CompileScope scope = cm.createFilesCompileScope(arr);
            cm.make(scope, (aborted, errorCount, warnings, ctx) -> {
                try {
                    if (aborted) return;
                    List<String> result = errorCount > 0
                        ? PrePushCompilationHandler.formatCompilerMessages(
                            project, ctx.getMessages(CompilerMessageCategory.ERROR))
                        : Collections.emptyList();
                    CompilationErrorService.getInstance(project).recordCompletion(
                        false,
                        CompilationErrorService.snapshotStamps(java.util.Arrays.asList(arr)),
                        result
                    );
                } finally {
                    finish();
                }
            });
        } catch (Throwable t) {
            LOG.debug("Warmup compile failed", t);
            finish();
        }
    }

    private void finish() {
        inFlight.set(false);
        if (rerun.compareAndSet(true, false)) schedule();
    }

    @Override
    public void dispose() {
        scheduler.shutdownNow();
        dirty.clear();
    }

    /** Creates the project service on startup so the listener has somewhere to route events. */
    public static void runStartup(@NotNull Project project) {
        CompilationWarmupService svc = getInstance(project);
        Disposer.register(project, svc);

        // Subscribe to git repository state changes so branch switches, fetches,
        // pulls, and rebases trigger a debounced warmup. By the time the user
        // initiates their next push, the incremental compiler will usually be
        // hot for the integrated tree, turning the pre-push check into a no-op.
        try {
            project.getMessageBus().connect(svc).subscribe(
                git4idea.repo.GitRepository.GIT_REPO_CHANGE,
                (git4idea.repo.GitRepositoryChangeListener) repo -> svc.onRepoChange()
            );
        } catch (Throwable t) {
            // Git4Idea is a hard plugin dependency, but if a future IDE moves or
            // renames the listener interface we don't want startup to fail.
            LOG.debug("Could not subscribe to GIT_REPO_CHANGE", t);
        }

        PropertiesComponent props = PropertiesComponent.getInstance(project);
        if (!props.getBoolean(INITIALIZED_KEY, false)) {
            props.setValue(INITIALIZED_KEY, true);
            svc.triggerInitialWarmup();
        }
    }

    /**
     * Repo-change hook: a single git operation often fires several state-change
     * events (e.g. {@code git pull --rebase} updates HEAD, then the index, then
     * the reflog). Absorb the burst with a coarse cooldown and reuse the
     * existing project warmup machinery for the actual compile.
     */
    private void onRepoChange() {
        if (project.isDisposed() || !isEnabled()) return;
        long now = System.nanoTime();
        long last = lastRepoChangeTriggerNanos.get();
        long cooldownNanos = TimeUnit.MILLISECONDS.toNanos(REPO_CHANGE_COOLDOWN_MS);
        if (last != 0L && (now - last) < cooldownNanos) return;
        if (!lastRepoChangeTriggerNanos.compareAndSet(last, now)) return;
        LOG.info("Pre-Push Checker: warming compile after git repo change for '"
            + project.getName() + "'");
        triggerProjectWarmup();
    }

    /**
     * Run a project-scope warmup compile in the background. Safe to call from
     * any thread; single-flight (overlapping calls coalesce); skipped in
     * power-save and dumb mode just like the on-save path.
     */
    public void triggerProjectWarmup() {
        if (project.isDisposed() || !isEnabled()) return;
        if (com.intellij.ide.PowerSaveMode.isEnabled()) return;
        if (DumbService.getInstance(project).isDumb()) {
            DumbService.getInstance(project).runWhenSmart(this::triggerProjectWarmup);
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            rerun.set(true);
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                if (project.isDisposed()) { finish(); return; }
                CompilerManager.getInstance(project).make((aborted, errorCount, warnings, ctx) -> {
                    try {
                        if (aborted) return;
                        List<String> result = errorCount > 0
                            ? PrePushCompilationHandler.formatCompilerMessages(
                                project, ctx.getMessages(CompilerMessageCategory.ERROR))
                            : Collections.emptyList();
                        CompilationErrorService.getInstance(project).recordCompletion(
                            true, Collections.emptyMap(), result);
                    } finally {
                        finish();
                    }
                });
            } catch (Throwable t) {
                LOG.debug("Project warmup compile failed", t);
                finish();
            }
        }, ModalityState.defaultModalityState());
    }

    /**
     * Runs a full-project compile once — on the first project open after plugin install —
     * so the compiler cache is hot before the user's first push from a terminal or GUI git client.
     * Deferred until indexing finishes and runs in the background without blocking the UI.
     */
    private void triggerInitialWarmup() {
        if (!isEnabled()) return;
        DumbService.getInstance(project).runWhenSmart(() -> {
            if (project.isDisposed() || com.intellij.ide.PowerSaveMode.isEnabled()) return;
            if (!inFlight.compareAndSet(false, true)) {
                rerun.set(true);
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    if (project.isDisposed()) { finish(); return; }
                    LOG.info("Pre-Push Checker: running first-time warmup compile for '" + project.getName() + "'");
                    CompilerManager.getInstance(project).make((aborted, errorCount, warnings, ctx) -> {
                        try {
                            if (aborted) return;
                            List<String> result = errorCount > 0
                                ? PrePushCompilationHandler.formatCompilerMessages(
                                    project, ctx.getMessages(CompilerMessageCategory.ERROR))
                                : Collections.emptyList();
                            CompilationErrorService.getInstance(project).recordCompletion(
                                true, Collections.emptyMap(), result);
                        } finally {
                            finish();
                        }
                    });
                } catch (Throwable t) {
                    LOG.debug("First-time warmup compile failed", t);
                    finish();
                }
            }, ModalityState.defaultModalityState());
        });
    }
}
