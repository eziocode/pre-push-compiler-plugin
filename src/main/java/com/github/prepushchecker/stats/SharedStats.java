package com.github.prepushchecker.stats;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sharing of anonymous Push Stats counts with the public total on the project's GitHub
 * page. Counting itself is done by {@code PushStats} (local, with its dedupe rules); every push it
 * counts is passed to {@link #counted}. Only the counts gathered since the last upload are kept
 * here, and only while sharing is not declined. Never paths, code, names or messages.
 * <p>
 * On by default: a user who never answered ({@link Consent#UNSET}) shares, and is told once
 * with a notification that offers to turn it off. An explicit "no" is never overridden.
 */
@State(name = "PrePushSharedStats", storages = @Storage("prePushSharedStats.xml"))
public final class SharedStats implements PersistentStateComponent<SharedStats.SharedState>, Disposable {
    public enum Consent { UNSET, YES, NO }

    /** Verdicts as {@code PushStats.Result} names them. */
    public enum Verdict { ALLOWED, BLOCKED, TIMEOUT, OTHER }

    public static final class SharedState {
        public String installId = "";
        public String consent = Consent.UNSET.name();
        public boolean consentAsked;
        public long pendingOk;
        public long pendingBlocked;
        public long pendingIssues;
        public long pendingTimeouts;
    }

    /** Counts gathered since the last successful upload. */
    public record Delta(long ok, long blocked, long issues, long timeouts) {
        public boolean isEmpty() {
            return ok == 0 && blocked == 0 && issues == 0 && timeouts == 0;
        }
    }

    private static final long UPLOAD_INTERVAL_MINUTES = 30;
    private static final String NOTIFICATION_GROUP_ID = "Pre-Push Compilation Checker";

    private SharedState state = new SharedState();
    private volatile ScheduledFuture<?> uploads;

    public static @NotNull SharedStats getInstance() {
        return ApplicationManager.getApplication().getService(SharedStats.class);
    }

    /**
     * Called by {@code PushStats} for each push it counted. Safe without a running application
     * (plain unit tests of PushStats): then it does nothing.
     */
    public static void counted(@NotNull Verdict verdict, int errorCount) {
        Application application = ApplicationManager.getApplication();
        if (application == null || application.isDisposed()) return;
        SharedStats stats = application.getService(SharedStats.class);
        if (stats != null) stats.add(verdict, errorCount);
    }

    @Override
    public @NotNull SharedState getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull SharedState loaded) {
        state = loaded;
    }

    synchronized void add(@NotNull Verdict verdict, int errorCount) {
        if (!isSharing()) return;
        switch (verdict) {
            case ALLOWED -> state.pendingOk++;
            case BLOCKED -> {
                state.pendingBlocked++;
                state.pendingIssues += Math.max(1, errorCount);
            }
            case TIMEOUT -> state.pendingTimeouts++;
            case OTHER -> {
                return;
            }
        }
        ensureUploads();
    }

    public synchronized @NotNull Delta pendingDelta() {
        return new Delta(state.pendingOk, state.pendingBlocked, state.pendingIssues, state.pendingTimeouts);
    }

    /** After an upload the server answered: subtract exactly what was sent (more may have arrived). */
    public synchronized void markUploaded(@NotNull Delta sent) {
        state.pendingOk = Math.max(0, state.pendingOk - sent.ok());
        state.pendingBlocked = Math.max(0, state.pendingBlocked - sent.blocked());
        state.pendingIssues = Math.max(0, state.pendingIssues - sent.issues());
        state.pendingTimeouts = Math.max(0, state.pendingTimeouts - sent.timeouts());
    }

    public synchronized @NotNull String installId() {
        if (state.installId == null || state.installId.isBlank()) {
            state.installId = UUID.randomUUID().toString();
        }
        return state.installId;
    }

    // ── Consent ────────────────────────────────────────────────────────────

    public synchronized @NotNull Consent getConsent() {
        try {
            return Consent.valueOf(state.consent);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Consent.UNSET;
        }
    }

    public synchronized void setConsent(boolean share) {
        state.consent = (share ? Consent.YES : Consent.NO).name();
        state.consentAsked = true;
        if (!share) {
            state.pendingOk = state.pendingBlocked = state.pendingIssues = state.pendingTimeouts = 0;
        }
        ensureUploads();
    }

    /** Consent as it applies: never answered counts as {@link Consent#YES}. */
    public synchronized @NotNull Consent effectiveConsent() {
        return effective(getConsent());
    }

    public boolean isSharing() {
        return effectiveConsent() == Consent.YES;
    }

    static @NotNull Consent effective(@NotNull Consent stored) {
        return stored == Consent.NO ? Consent.NO : Consent.YES;
    }

    /**
     * Settings checkbox: records an answer only when it differs from the effective consent, so
     * applying the page without touching the box never turns sharing off or pins a default.
     */
    public synchronized void setSharingFromSettings(boolean share) {
        if (share == isSharing()) return;
        setConsent(share);
    }

    /**
     * Tells once, after the first checked push, that anonymous counts are shared by default, with
     * a Turn off action. Nothing is shown to a user who already answered.
     */
    public void maybeAskConsent(@NotNull Project project) {
        synchronized (this) {
            if (state.consentAsked || getConsent() != Consent.UNSET) return;
            state.consentAsked = true;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(
                    "Anonymous usage counts are shared",
                    "This IDE's Push Stats counts are added to the public total on the project's "
                        + "GitHub page. Only counts are sent (checks passed, pushes blocked, errors "
                        + "caught, timeouts, plugin and IDE version) with a random install ID. No "
                        + "code, file names, paths, messages or project names. Change it any time "
                        + "under Settings | Tools | Pre-Push Checker | Usage Statistics.",
                    NotificationType.INFORMATION);
            notification.addAction(new NotificationAction("Turn off") {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification n) {
                    setConsent(false);
                    n.expire();
                }
            });
            notification.notify(project);
        });
    }

    // ── Upload scheduling ──────────────────────────────────────────────────

    private void ensureUploads() {
        if (uploads != null || !isSharing() || !StatsEndpoint.isConfigured()) return;
        synchronized (this) {
            if (uploads != null) return;
            uploads = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                () -> StatsUploader.uploadPending(this),
                1, UPLOAD_INTERVAL_MINUTES * 60, TimeUnit.SECONDS);
        }
    }

    @Override
    public void dispose() {
        ScheduledFuture<?> scheduled = uploads;
        if (scheduled != null) scheduled.cancel(false);
    }
}
