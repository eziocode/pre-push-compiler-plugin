package com.github.prepushchecker.commitgen;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Writes the commit message before the user asks for it: while the commit panel is shown (the
 * Generate action's {@code update()} reports the checked files), once the set has been quiet for
 * {@link #DEBOUNCE_MS}, the message for that diff is generated in the background lane and cached.
 * Clicking Generate then returns it at once, or joins the call still running.
 *
 * <p>Only for users who clicked Generate before ({@link CommitMessageSettings.State#commitGenerateUsed}),
 * at most one call per distinct diff, never with unsaved documents (the diff would be stale), and
 * re-checked at most every {@link #RECHECK_MS} while the checked files stay the same.</p>
 */
@Service(Service.Level.PROJECT)
public final class CommitMessageSpeculator implements Disposable {

    private static final Logger LOG = Logger.getInstance(CommitMessageSpeculator.class);
    static final long DEBOUNCE_MS = 1_500;
    static final long RECHECK_MS = 15_000;

    private final Project project;
    private final Object lock = new Object();
    private List<Change> changes = List.of();
    private List<Path> unversioned = List.of();
    private int signature;
    private int lastRunSignature;
    private long lastRunAt;
    private ScheduledFuture<?> pending;
    private boolean running;

    public CommitMessageSpeculator(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull CommitMessageSpeculator getInstance(@NotNull Project project) {
        return project.getService(CommitMessageSpeculator.class);
    }

    /** The commit panel shows these checked files; cheap, may be called on every action update. */
    public void panelShown(@NotNull List<Change> included, @NotNull List<Path> includedUnversioned) {
        if (!CommitMessageSettings.getInstance().settings().commitGenerateUsed) return;
        if (included.isEmpty() && includedUnversioned.isEmpty()) return;
        int sig = 31 * included.hashCode() + includedUnversioned.hashCode();
        long now = System.currentTimeMillis();
        synchronized (lock) {
            boolean changed = sig != signature;
            changes = List.copyOf(included);
            unversioned = List.copyOf(includedUnversioned);
            signature = sig;
            if (running) return;
            if (changed) {
                if (pending != null) pending.cancel(false);
            } else if (pending != null || (sig == lastRunSignature && now - lastRunAt < RECHECK_MS)) {
                return;
            }
            pending = AppExecutorUtil.getAppScheduledExecutorService()
                .schedule(this::runIfQuiet, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void runIfQuiet() {
        List<Change> c;
        List<Path> u;
        synchronized (lock) {
            pending = null;
            if (running || project.isDisposed()) return;
            running = true;
            c = changes;
            u = unversioned;
            lastRunSignature = signature;
            lastRunAt = System.currentTimeMillis();
        }
        try {
            // Unsaved edits are not in the git diff yet; wait until they are saved.
            if (FileDocumentManager.getInstance().getUnsavedDocuments().length > 0) return;
            CommitMessageGeneratorService.getInstance(project).pregenerate(c, u);
        } catch (CancellationException e) {
            LOG.debug("Speculative commit message yielded to an interactive AI call");
        } catch (Exception e) {
            LOG.debug("Speculative commit message failed", e);
        } finally {
            synchronized (lock) {
                running = false;
            }
        }
    }

    @Override
    public void dispose() {
        synchronized (lock) {
            if (pending != null) pending.cancel(false);
            pending = null;
        }
    }
}
