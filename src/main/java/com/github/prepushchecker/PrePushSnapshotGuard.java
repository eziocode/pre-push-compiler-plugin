package com.github.prepushchecker;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class PrePushSnapshotGuard {
    private static final int MAX_REPORTED_LOCAL_CHANGES = 50;

    private PrePushSnapshotGuard() {
    }

    static @NotNull List<String> collectBlockingMessages(@NotNull Project project) {
        if (!PrePushCheckerSettings.isStrictSnapshotGuardEnabled(project)) {
            return List.of();
        }

        Set<String> relevantLocalPaths = new LinkedHashSet<>();
        ChangeListManager changeListManager = ChangeListManager.getInstance(project);
        for (Change change : changeListManager.getAllChanges()) {
            addRelevantPath(project, extractPath(change), relevantLocalPaths);
        }

        if (relevantLocalPaths.isEmpty()) {
            return List.of();
        }

        List<String> messages = new ArrayList<>(Math.min(
            relevantLocalPaths.size(), MAX_REPORTED_LOCAL_CHANGES) + 1);
        int emitted = 0;
        for (String path : relevantLocalPaths) {
            if (emitted >= MAX_REPORTED_LOCAL_CHANGES) {
                break;
            }
            messages.add(blockingMessage(path));
            emitted++;
        }
        int omitted = relevantLocalPaths.size() - emitted;
        if (omitted > 0) {
            messages.add(CompilationErrorService.omittedErrorsMessage(omitted));
        }
        return List.copyOf(messages);
    }

    private static void addRelevantPath(
        @NotNull Project project,
        @NotNull String path,
        @NotNull Set<String> relevantPaths
    ) {
        String displayPath = toProjectRelativePath(project, path);
        if (!displayPath.isEmpty() && PushValidationPaths.isRelevantPath(displayPath)) {
            relevantPaths.add(displayPath);
        }
    }

    static @NotNull String toProjectRelativePath(@NotNull Project project, @NotNull String path) {
        String normalizedPath = PushValidationPaths.normalizePath(path);
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return normalizedPath;
        }

        String normalizedBase = PushValidationPaths.normalizePath(basePath);
        String relativePath = FileUtil.getRelativePath(normalizedBase, normalizedPath, '/');
        return relativePath != null && !relativePath.startsWith("../") ? relativePath : normalizedPath;
    }

    static @NotNull String blockingMessage(@NotNull String path) {
        return "[" + path + "] Strict A/B dependency guard is enabled, but this local " +
            "source/build change is not guaranteed to be in the pushed snapshot. Commit or stash " +
            "it, or disable the guard from the Compilation Checker tool window.";
    }

    private static @NotNull String extractPath(@NotNull Change change) {
        ContentRevision afterRevision = change.getAfterRevision();
        if (afterRevision != null) {
            return afterRevision.getFile().getPath();
        }

        ContentRevision beforeRevision = change.getBeforeRevision();
        return beforeRevision != null ? beforeRevision.getFile().getPath() : "";
    }
}
