package com.github.prepushchecker;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Tells the user when the terminal pre-push hook is missing for a project and offers a
 * one-click install. At most one such notification is live per project.
 */
final class HookNotifier {
    static final String NOTIFICATION_GROUP_ID = "Pre-Push Compilation Checker";
    private static final Key<Notification> NOT_INSTALLED_NOTIFICATION =
        Key.create("prepushchecker.hookNotInstalledNotification");

    private HookNotifier() {
    }

    static void showNotInstalled(@NotNull Project project,
                                 @NotNull List<GitHookInstaller.HookRepairResult> failures) {
        if (failures.isEmpty()) return;
        String detail = failures.get(0).statusText();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            Notification existing = project.getUserData(NOT_INSTALLED_NOTIFICATION);
            if (existing != null && !existing.isExpired()) return;

            Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(
                    "Pre-push hook not installed",
                    "Compilation checks will not run on <code>git push</code> for "
                        + StringUtil.escapeXmlEntities(project.getName()) + ".<br>"
                        + StringUtil.escapeXmlEntities(detail),
                    NotificationType.WARNING
                );
            notification.addAction(new NotificationAction("Install Pre-Push Hook") {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification n) {
                    n.expire();
                    install(project);
                }
            });
            notification.addAction(new NotificationAction("Open Compilation Checker") {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification n) {
                    ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                        .getToolWindow("Compilation Checker");
                    if (toolWindow != null) {
                        toolWindow.show(null);
                    }
                }
            });
            project.putUserData(NOT_INSTALLED_NOTIFICATION, notification);
            notification.notify(project);
        });
    }

    static void clear(@NotNull Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            Notification existing = project.getUserData(NOT_INSTALLED_NOTIFICATION);
            if (existing != null) {
                existing.expire();
                project.putUserData(NOT_INSTALLED_NOTIFICATION, null);
            }
        });
    }

    static boolean hasActiveNotification(@NotNull Project project) {
        Notification existing = project.getUserData(NOT_INSTALLED_NOTIFICATION);
        return existing != null && !existing.isExpired();
    }

    private static void install(@NotNull Project project) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Installing pre-push hook", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                // installForProject re-raises this notification on failure and clears it on success.
                List<GitHookInstaller.HookRepairResult> results =
                    GitHookInstaller.installForProject(project, true);
                GitHookInstaller.HookRepairResult summary = GitHookInstaller.HookRepairResult.aggregate(results);
                if (results.isEmpty()) {
                    showNotInstalled(project, List.of(summary));
                } else if (summary.isSuccess()) {
                    notifyInstalled(project);
                }
            }
        });
    }

    private static void notifyInstalled(@NotNull Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(
                    "Pre-push hook installed",
                    "Compilation checks will run before each <code>git push</code> in "
                        + StringUtil.escapeXmlEntities(project.getName()) + ".",
                    NotificationType.INFORMATION
                )
                .notify(project);
        });
    }
}
