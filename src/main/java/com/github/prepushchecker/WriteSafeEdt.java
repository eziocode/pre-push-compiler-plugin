package com.github.prepushchecker;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs model-changing code on the EDT from a background thread without stalling on an open
 * dialog.
 *
 * <p>{@code invokeLater/invokeAndWait(..., ModalityState.any())} runs even while a modal dialog
 * is open, but {@code any()} is write-unsafe: saving documents, starting a build or setting the
 * commit message there fails with "Write-unsafe context! Model changes are allowed from
 * write-safe contexts only" (asserted in 2026.3). Here a first hop with {@code any()} only reads
 * the EDT's current modality (no model change); the work then runs under that real modality,
 * which is write-safe and still runs inside an open dialog.</p>
 */
public final class WriteSafeEdt {
    private static final Logger LOG = Logger.getInstance(WriteSafeEdt.class);
    public static final Duration SAVE_TIMEOUT = Duration.ofSeconds(10);

    private WriteSafeEdt() {}

    /** Schedules {@code work} write-safely; the future completes when it has run (or failed). */
    public static @NotNull CompletableFuture<Void> invokeLater(@NotNull Runnable work) {
        Application app = ApplicationManager.getApplication();
        CompletableFuture<Void> done = new CompletableFuture<>();
        app.invokeLater(() -> {
            ModalityState current = ModalityState.current();
            app.invokeLater(() -> {
                try {
                    work.run();
                    done.complete(null);
                } catch (Throwable t) {
                    done.completeExceptionally(t);
                }
            }, current);
        }, ModalityState.any());
        return done;
    }

    /**
     * Runs {@code work} write-safely and waits for it. On the EDT it runs directly. Waits at most
     * {@code timeout}; returns {@code false} when it did not finish in time or failed.
     */
    public static boolean invokeAndWait(@NotNull Runnable work, @NotNull Duration timeout) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            work.run();
            return true;
        }
        try {
            invokeLater(work).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            LOG.info("EDT work did not finish within " + timeout.toSeconds() + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            LOG.warn("EDT work failed", cause);
        }
        return false;
    }

    /** Saves all documents; when that does not finish in time, callers go on with files on disk. */
    public static void saveAllDocuments() {
        if (!invokeAndWait(() -> FileDocumentManager.getInstance().saveAllDocuments(), SAVE_TIMEOUT)) {
            LOG.info("Saving documents did not finish; continuing with files on disk");
        }
    }
}
