package com.github.prepushchecker;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns the "bypass push check" switch.
 *
 * <p>The switch lives in memory only, so it is always off when the IDE starts. While on,
 * a token file tells the terminal hook to skip the compilation check for every push. The
 * switch turns itself off after {@link PrePushCheckerSettings#bypassMaxAgeMillis()}; the
 * hook enforces the same limit, so a token left behind by a crashed IDE also expires.
 */
@Service(Service.Level.PROJECT)
public final class BypassController implements Disposable {

    private static final Logger LOG = Logger.getInstance(BypassController.class);

    private final Project project;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    // Token file writes run in order on one background thread, so a quick on/off never
    // leaves a stale token behind.
    private final ExecutorService fileExecutor =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("PrePushChecker-Bypass", 1);

    private volatile long activatedAtMillis;
    private ScheduledFuture<?> expiryFuture;

    public BypassController(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull BypassController getInstance(@NotNull Project project) {
        return project.getService(BypassController.class);
    }

    public boolean isActive() {
        return remainingMillis() > 0;
    }

    /** Milliseconds until the switch turns itself off, or {@code 0} when it is off. */
    public long remainingMillis() {
        long activatedAt = activatedAtMillis;
        if (activatedAt == 0) return 0;
        long remaining = activatedAt + PrePushCheckerSettings.bypassMaxAgeMillis()
            - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    public synchronized void enable() {
        long now = System.currentTimeMillis();
        activatedAtMillis = now;
        cancelExpiry();
        expiryFuture = AppExecutorUtil.getAppScheduledExecutorService().schedule(
            this::expire, PrePushCheckerSettings.bypassMaxAgeMillis(), TimeUnit.MILLISECONDS);
        fileExecutor.execute(() -> PrePushCheckerSettings.setForcePushBypass(project, now));
        fireListeners();
    }

    public synchronized void disable() {
        if (activatedAtMillis == 0) return;
        turnOff();
        fireListeners();
    }

    /**
     * Removes any token left by a previous IDE session so the switch starts off. Does
     * nothing if the user already turned the switch on in this session.
     */
    synchronized void resetOnStartup() {
        if (activatedAtMillis != 0) return;
        fileExecutor.execute(() -> PrePushCheckerSettings.clearForcePushBypass(project));
    }

    public void addListener(@NotNull Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull Runnable listener) {
        listeners.remove(listener);
    }

    private synchronized void expire() {
        if (activatedAtMillis == 0 || project.isDisposed()) return;
        turnOff();
        fireListeners();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Pre-Push Compilation Checker")
                .createNotification(
                    "Push check bypass turned off",
                    "The 1-hour limit was reached. Pushes are compiled again.",
                    NotificationType.INFORMATION)
                .notify(project);
        });
    }

    private void turnOff() {
        activatedAtMillis = 0;
        cancelExpiry();
        fileExecutor.execute(() -> PrePushCheckerSettings.clearForcePushBypass(project));
    }

    private void cancelExpiry() {
        if (expiryFuture != null) {
            expiryFuture.cancel(false);
            expiryFuture = null;
        }
    }

    private void fireListeners() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            for (Runnable listener : listeners) {
                try {
                    listener.run();
                } catch (Exception e) {
                    LOG.warn("BypassController listener threw", e);
                }
            }
        });
    }

    @Override
    public synchronized void dispose() {
        cancelExpiry();
        listeners.clear();
        // Drop queued writes first so a pending "enable" cannot recreate the token.
        fileExecutor.shutdownNow();
        if (activatedAtMillis != 0) {
            activatedAtMillis = 0;
            PrePushCheckerSettings.clearForcePushBypass(project);
        }
    }
}
