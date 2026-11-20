package com.github.prepushchecker.review;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Entry point for fixes generated ahead of time; see {@link FixPrefetchService}. */
final class FixPrefetch {
    private FixPrefetch() {}

    /** A ready fix for {@code finding} made from exactly {@code content}, removed from the cache; else {@code null}. */
    static @Nullable ReviewFixer.Result takeOrNull(@NotNull Project project, @NotNull ReviewFinding finding,
                                                   @NotNull String content) {
        if (project.isDisposed()) return null;
        return FixPrefetchService.getInstance(project).take(finding.id, content);
    }
}
