package com.github.prepushchecker.review;

import com.github.prepushchecker.ReadActions;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Prepares "Resolve with AI" fixes in the background after a review, so a click opens the
 * preview at once instead of waiting for the AI.
 *
 * <p>Token use is capped: at most {@link #MAX_PREFETCH} findings per review run (one per file
 * first), one AI call at a time, and never while the user waits on an AI fix. A fix is tied to
 * the exact file content it was made from; it is dropped when the finding is resolved, the file
 * is edited, the reviews are cleared, or after {@link #TTL_MILLIS}.</p>
 */
@Service(Service.Level.PROJECT)
public final class FixPrefetchService implements Disposable {

    private static final Logger LOG = Logger.getInstance(FixPrefetchService.class);
    public static final int MAX_PREFETCH = 3;
    static final long TTL_MILLIS = TimeUnit.MINUTES.toMillis(15);
    private static final String ENABLED_KEY = "prepushchecker.review.prefetchFixes";

    private record Ready(@NotNull String path, @NotNull String contentHash, @NotNull ReviewFixer.Result result, long at) {}

    private record Pending(@NotNull String path, @NotNull String contentHash,
                           @NotNull CompletableFuture<ReviewFixer.Result> future, @NotNull ProgressIndicator indicator) {}

    private final Project project;
    private final Map<String, Ready> ready = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final ExecutorService executor =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("PrePushChecker-FixPrefetch", 1);
    private final Runnable storeListener = this::dropStale;

    public FixPrefetchService(@NotNull Project project) {
        this.project = project;
        ReviewStateStore.getInstance(project).addListener(storeListener);
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                onEdit(event.getDocument());
            }
        }, this);
    }

    public static @NotNull FixPrefetchService getInstance(@NotNull Project project) {
        return project.getService(FixPrefetchService.class);
    }

    public static boolean isEnabled(@NotNull Project project) {
        return PropertiesComponent.getInstance(project).getBoolean(ENABLED_KEY, true);
    }

    public static void setEnabled(@NotNull Project project, boolean enabled) {
        PropertiesComponent.getInstance(project).setValue(ENABLED_KEY, enabled, true);
        if (!enabled) getInstance(project).flush();
    }

    /**
     * Queues fixes for up to {@link #MAX_PREFETCH} open findings of the given files. Returns at
     * once; the work runs in the background.
     */
    public void prefetch(@NotNull List<String> paths) {
        if (!isEnabled(project) || project.isDisposed() || paths.isEmpty()) return;
        ReviewStateStore store = ReviewStateStore.getInstance(project);
        List<ReviewFinding> candidates = new ArrayList<>();
        // One finding per file first, then the rest: spreads the budget over the most files.
        List<List<ReviewFinding>> perFile = new ArrayList<>();
        for (String path : paths) {
            ReviewStateStore.FileReview review = store.review(path);
            if (review == null) continue;
            List<ReviewFinding> open = review.findings.stream().filter(ReviewFinding::isOpen).toList();
            if (!open.isEmpty()) perFile.add(open);
        }
        for (int round = 0; candidates.size() < MAX_PREFETCH; round++) {
            boolean any = false;
            for (List<ReviewFinding> open : perFile) {
                if (round < open.size() && candidates.size() < MAX_PREFETCH) {
                    candidates.add(open.get(round));
                    any = true;
                }
            }
            if (!any) break;
        }
        for (ReviewFinding f : candidates) {
            if (ready.containsKey(f.id) || pending.containsKey(f.id)) continue;
            if (ready.size() + pending.size() >= MAX_PREFETCH) break;
            schedule(f);
        }
    }

    private void schedule(@NotNull ReviewFinding finding) {
        ProgressIndicator indicator = new EmptyProgressIndicator();
        CompletableFuture<ReviewFixer.Result> future = new CompletableFuture<>();
        // Registered before the content is known; the hash is filled in when the work starts.
        Pending placeholder = new Pending(finding.path, "", future, indicator);
        pending.put(finding.id, placeholder);
        executor.execute(() -> {
            try {
                ReviewFixer.Result r = run(finding, indicator);
                future.complete(r);
            } catch (ProcessCanceledException e) {
                future.cancel(false);
            } catch (Throwable t) {
                LOG.debug("AI fix prefetch failed for " + finding.path, t);
                future.complete(null);
            } finally {
                pending.remove(finding.id);
            }
        });
    }

    private @Nullable ReviewFixer.Result run(@NotNull ReviewFinding finding, @NotNull ProgressIndicator indicator) throws Exception {
        // Yield to fixes the user is waiting for; give up rather than queue behind them for long.
        for (int i = 0; ReviewFixer.INTERACTIVE.get() > 0; i++) {
            if (i > 600 || indicator.isCanceled()) return null;
            Thread.sleep(100);
        }
        ReviewFinding current = ReviewStateStore.getInstance(project).finding(finding.id);
        if (current == null || !current.isOpen() || indicator.isCanceled()) return null;
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(finding.path);
        if (vf == null) return null;
        String content = ReadActions.compute(() -> {
            Document d = FileDocumentManager.getInstance().getDocument(vf);
            return d == null ? null : d.getText();
        });
        if (content == null) return null;
        String hash = hash(content);
        pending.computeIfPresent(finding.id, (k, p) -> new Pending(p.path(), hash, p.future(), p.indicator()));
        ReviewFixer.Result result = ProgressManager.getInstance().runProcess(
            () -> {
                try {
                    // Background lane: yields to any AI call the user is waiting for.
                    return com.github.prepushchecker.commitgen.cli.AiCallScheduler.inBackground(
                        () -> ReviewFixer.generateFor(project, vf, current, content, null));
                } catch (ProcessCanceledException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, indicator);
        if (result == null || indicator.isCanceled()) return null;
        ReviewFinding still = ReviewStateStore.getInstance(project).finding(finding.id);
        if (still == null || !still.isOpen()) return null;
        ready.put(finding.id, new Ready(finding.path, hash, result, System.currentTimeMillis()));
        LOG.info("AI fix prefetched for " + vf.getName());
        return result;
    }

    /**
     * The prefetched fix for {@code findingId}, made from exactly {@code content}. Waits for one
     * still being made from that content (cheaper than asking again); {@code null} otherwise.
     */
    @Nullable ReviewFixer.Result take(@NotNull String findingId, @NotNull String content) {
        String hash = hash(content);
        Ready r = ready.remove(findingId);
        if (r != null) {
            if (r.contentHash().equals(hash) && System.currentTimeMillis() - r.at() < TTL_MILLIS) return r.result();
            return null;
        }
        Pending p = pending.get(findingId);
        if (p == null || !p.contentHash().equals(hash)) return null;
        try {
            while (true) {
                ProgressManager.checkCanceled();
                try {
                    ReviewFixer.Result result = p.future().get(200, TimeUnit.MILLISECONDS);
                    ready.remove(findingId);
                    return result;
                } catch (TimeoutException ignored) {
                    // keep waiting, cancellable
                }
            }
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    /** Drops every prepared and pending fix. */
    public void flush() {
        ready.clear();
        for (Pending p : pending.values()) p.indicator().cancel();
    }

    private void onEdit(@NotNull Document document) {
        if (ready.isEmpty() && pending.isEmpty()) return;
        VirtualFile vf = FileDocumentManager.getInstance().getFile(document);
        if (vf == null || !vf.isInLocalFileSystem()) return;
        String path = ReviewService.key(vf.toNioPath());
        ready.values().removeIf(r -> r.path().equals(path));
        for (Pending p : pending.values()) if (p.path().equals(path)) p.indicator().cancel();
    }

    private void dropStale() {
        if (ready.isEmpty() && pending.isEmpty()) return;
        ReviewStateStore store = ReviewStateStore.getInstance(project);
        Set<String> open = new HashSet<>();
        for (ReviewStateStore.FileReview r : store.reviews()) {
            for (ReviewFinding f : r.findings) if (f.isOpen()) open.add(f.id);
        }
        long now = System.currentTimeMillis();
        ready.entrySet().removeIf(e -> !open.contains(e.getKey()) || now - e.getValue().at() >= TTL_MILLIS);
        pending.forEach((id, p) -> {
            if (!open.contains(id)) p.indicator().cancel();
        });
    }

    static @NotNull String hash(@NotNull String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return content.length() + ":" + content.hashCode();
        }
    }

    @Override
    public void dispose() {
        ReviewStateStore.getInstance(project).removeListener(storeListener);
        flush();
        executor.shutdownNow();
    }
}
