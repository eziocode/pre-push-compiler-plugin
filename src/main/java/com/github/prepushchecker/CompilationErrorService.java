package com.github.prepushchecker;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Project-level service that holds the most recent compilation error list produced
 * by either hook-driven validation or the tool-window "Run Check" action.
 *
 * <p>Listeners are notified on the EDT whenever the error list changes.
 * This service deliberately stores no validation verdict or freshness data.
 */
@Service(Service.Level.PROJECT)
public final class CompilationErrorService {

    private static final Logger LOG = Logger.getInstance(CompilationErrorService.class);
    static final int MAX_RETAINED_ERRORS = 200;
    private static final int MAX_RETAINED_ERROR_CHARS = 4_000;

    private volatile List<String> errors = Collections.emptyList();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static CompilationErrorService getInstance(@NotNull Project project) {
        return project.getService(CompilationErrorService.class);
    }

    public void setErrors(@NotNull List<String> newErrors) {
        List<String> snapshot = compactErrors(newErrors);
        if (snapshot.equals(this.errors)) {
            return;
        }
        this.errors = snapshot;
        ApplicationManager.getApplication().invokeLater(this::fireListeners);
    }

    static @NotNull List<String> compactErrors(@NotNull List<String> newErrors) {
        if (newErrors.isEmpty()) {
            return Collections.emptyList();
        }

        int retained = Math.min(newErrors.size(), MAX_RETAINED_ERRORS);
        List<String> snapshot = new ArrayList<>(retained + (newErrors.size() > retained ? 1 : 0));
        for (int i = 0; i < retained; i++) {
            snapshot.add(compactError(newErrors.get(i)));
        }
        if (newErrors.size() > retained) {
            snapshot.add(omittedErrorsMessage(newErrors.size() - retained));
        }
        return List.copyOf(snapshot);
    }

    static @NotNull String compactError(@Nullable String error) {
        String value = error == null ? "" : error;
        if (value.length() <= MAX_RETAINED_ERROR_CHARS) {
            return value;
        }
        return value.substring(0, MAX_RETAINED_ERROR_CHARS)
            + "... [truncated to limit memory usage]";
    }

    static @NotNull String omittedErrorsMessage(int omittedCount) {
        return "... " + omittedCount + " more compiler error(s) omitted to limit memory usage.";
    }

    public @NotNull List<String> getErrors() {
        return errors;
    }

    public void clearErrors() {
        setErrors(Collections.emptyList());
    }

    public void addListener(@NotNull Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull Runnable listener) {
        listeners.remove(listener);
    }

    private void fireListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Exception e) {
                LOG.warn("CompilationErrorService listener threw", e);
            }
        }
    }
}
