package com.github.prepushchecker.review;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What was reviewed: per file, the content id (git blob id) that was reviewed and its findings.
 *
 * <p>A file is <b>approved</b> for a content id when the review of that id has no open
 * finding. Resolving the last finding re-stamps the id with the file's current content, so
 * the fix itself does not ask for a new review. Any later edit changes the id, and the file is
 * unreviewed again, unless the edit restores a version approved earlier (the last
 * {@link #MAX_APPROVED_HISTORY} are kept), e.g. a revert. Kept in the workspace file: personal,
 * never shared.</p>
 */
@Service(Service.Level.PROJECT)
@State(name = "PrePushReviewState", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class ReviewStateStore implements PersistentStateComponent<ReviewStateStore.StateBean> {

    static final int MAX_FILES = 2000;
    static final int MAX_APPROVED_HISTORY = 10;

    public static final class FileReview {
        public String path = "";
        public String contentId = "";
        /** Other blob IDs seen during resolution, including partial fixes and the original reviewed version. */
        public List<String> resolutionIds = new ArrayList<>();
        public long reviewedAt;
        public List<ReviewFinding> findings = new ArrayList<>();
        /** Earlier content ids of this file that were approved, oldest first. */
        public List<String> approvedIds = new ArrayList<>();

        private void recordResolution(@Nullable String blob) {
            if (blob == null || blob.isEmpty() || contentId.equals(blob)) return;
            if (hasOpenFindings()) {
                if (!resolutionIds.contains(blob)) resolutionIds.add(blob);
            } else {
                resolutionIds.add(contentId);
                resolutionIds.remove(blob);
                contentId = blob;
            }
        }

        public boolean hasOpenFindings() {
            for (ReviewFinding f : findings) if (f.isOpen()) return true;
            return false;
        }
    }

    public static final class StateBean {
        public List<FileReview> files = new ArrayList<>();
    }

    private StateBean state = new StateBean();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong modCount = new java.util.concurrent.atomic.AtomicLong();

    /** Changes on every record, resolve or clear. */
    public long modificationCount() {
        return modCount.get();
    }

    public static @NotNull ReviewStateStore getInstance(@NotNull Project project) {
        return project.getService(ReviewStateStore.class);
    }

    @Override
    public synchronized @NotNull StateBean getState() {
        return state;
    }

    @Override
    public synchronized void loadState(@NotNull StateBean loaded) {
        state = loaded;
    }

    /** Replaces the review of {@code path} with a new one for {@code contentId}. */
    public void record(@NotNull String path, @NotNull String contentId, @NotNull List<ReviewFinding> findings) {
        synchronized (this) {
            FileReview previous = find(path);
            List<String> history = new ArrayList<>();
            if (previous != null) {
                history.addAll(previous.approvedIds);
                if (!previous.hasOpenFindings()) remember(history, previous.contentId);
            }
            state.files.removeIf(f -> f.path.equals(path));
            FileReview review = new FileReview();
            review.approvedIds = history;
            review.path = path;
            review.contentId = contentId;
            review.reviewedAt = System.currentTimeMillis();
            review.findings = new ArrayList<>(findings);
            state.files.add(review);
            if (state.files.size() > MAX_FILES) {
                state.files.sort(Comparator.comparingLong(f -> f.reviewedAt));
                state.files.subList(0, state.files.size() - MAX_FILES).clear();
            }
            modCount.incrementAndGet();
        }
        notifyListeners();
    }

    /** {@code true} when {@code contentId} of {@code path} was reviewed and has no open finding. */
    public synchronized boolean isApproved(@NotNull String path, @Nullable String contentId) {
        if (contentId == null || contentId.isEmpty()) return false;
        FileReview review = find(path);
        if (review == null) return false;
        if (review.contentId.equals(contentId) && !review.hasOpenFindings()) return true;
        return review.approvedIds.contains(contentId);
    }

    synchronized boolean hasOpenReview(@NotNull String path, @NotNull String blob) {
        FileReview review = find(path);
        return review != null && (review.contentId.equals(blob) || review.resolutionIds.contains(blob)) && review.hasOpenFindings();
    }

    /** The approved fix for a previously flagged version, if both belong to the same review. */
    synchronized @Nullable String fixedVersionOf(@NotNull String path, @NotNull String blob) {
        FileReview review = find(path);
        if (review == null || !review.resolutionIds.contains(blob) || review.hasOpenFindings()
                || review.approvedIds.contains(blob)) return null;
        return review.contentId;
    }

    public synchronized @Nullable FileReview review(@NotNull String path) {
        FileReview r = find(path);
        return r == null ? null : copy(r);
    }

    /** All reviews, newest first. Copies: callers may not modify the store through them. */
    public synchronized @NotNull List<FileReview> reviews() {
        List<FileReview> out = new ArrayList<>();
        for (FileReview r : state.files) out.add(copy(r));
        out.sort(Comparator.comparingLong((FileReview r) -> r.reviewedAt).reversed());
        return out;
    }

    /**
     * Marks a finding resolved. When it was the file's last open finding and
     * {@code currentContentId} is known, the approval moves to that content.
     *
     * @return {@code true} when the finding existed and was open
     */
    public boolean resolve(@NotNull String findingId, @NotNull String by, @Nullable String currentContentId) {
        boolean changed = false;
        synchronized (this) {
            for (FileReview review : state.files) {
                for (ReviewFinding f : review.findings) {
                    if (!f.id.equals(findingId) || f.resolved) continue;
                    f.resolved = true;
                    f.resolvedBy = by;
                    changed = true;
                    review.recordResolution(currentContentId);
                }
            }
            if (changed) modCount.incrementAndGet();
        }
        if (changed) notifyListeners();
        return changed;
    }

    public synchronized @Nullable ReviewFinding finding(@NotNull String findingId) {
        for (FileReview review : state.files) {
            for (ReviewFinding f : review.findings) if (f.id.equals(findingId)) return f;
        }
        return null;
    }

    public void clear() {
        synchronized (this) {
            state.files.clear();
            modCount.incrementAndGet();
        }
        notifyListeners();
    }

    /**
     * Retires open findings on settled files under {@code rootPrefix} (a {@code /}-terminated store key
     * prefix) whose relative path is not in {@code keep}. Returns how many were dropped.
     */
    public int retainOnly(@NotNull String rootPrefix, @NotNull java.util.Set<String> keep) {
        int removed;
        synchronized (this) {
            removed = 0;
            for (var it = state.files.iterator(); it.hasNext();) {
                FileReview file = it.next();
                if (!file.hasOpenFindings() || !file.path.startsWith(rootPrefix)
                        || keep.contains(file.path.substring(rootPrefix.length()))) continue;
                if (file.approvedIds.isEmpty()) {
                    it.remove();
                } else {
                    // Retire this unapproved version, keeping the user's prior approvals.
                    file.contentId = file.approvedIds.remove(file.approvedIds.size() - 1);
                    file.findings.clear();
                    file.resolutionIds.clear();
                }
                removed++;
            }
            if (removed > 0) modCount.incrementAndGet();
        }
        if (removed > 0) notifyListeners();
        return removed;
    }

    public synchronized boolean hasOpenFindings() {
        return state.files.stream().anyMatch(FileReview::hasOpenFindings);
    }

    public synchronized boolean isEmpty() {
        return state.files.isEmpty();
    }

    public void addListener(@NotNull Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull Runnable listener) {
        listeners.remove(listener);
    }

    private static void remember(List<String> history, String contentId) {
        if (contentId == null || contentId.isEmpty()) return;
        history.remove(contentId);
        history.add(contentId);
        while (history.size() > MAX_APPROVED_HISTORY) history.remove(0);
    }

    private @Nullable FileReview find(@NotNull String path) {
        for (FileReview f : state.files) if (f.path.equals(path)) return f;
        return null;
    }

    private static FileReview copy(FileReview r) {
        FileReview c = new FileReview();
        c.path = r.path;
        c.contentId = r.contentId;
        c.resolutionIds = new ArrayList<>(r.resolutionIds);
        c.reviewedAt = r.reviewedAt;
        c.approvedIds = new ArrayList<>(r.approvedIds);
        for (ReviewFinding f : r.findings) {
            ReviewFinding fc = new ReviewFinding(f.path, f.line, f.rule, f.message, f.suggestion);
            fc.id = f.id;
            fc.resolved = f.resolved;
            fc.resolvedBy = f.resolvedBy;
            c.findings.add(fc);
        }
        return c;
    }

    /** Re-notifies listeners without a data change, e.g. when a run ends having recorded nothing. */
    public void refresh() {
        notifyListeners();
    }

    private void notifyListeners() {
        if (listeners.isEmpty()) return;
        var app = ApplicationManager.getApplication();
        if (app == null) {
            listeners.forEach(Runnable::run);
            return;
        }
        app.invokeLater(() -> listeners.forEach(Runnable::run));
    }
}
