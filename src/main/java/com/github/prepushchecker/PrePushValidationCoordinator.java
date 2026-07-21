package com.github.prepushchecker;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Project-wide, snapshot-keyed validation coordinator. Requests are executed in FIFO order;
 * identical keys share one future, while queued requests recheck the shared freshness cache
 * immediately before deciding whether another validation is necessary.
 */
@Service(Service.Level.PROJECT)
public final class PrePushValidationCoordinator implements Disposable {
    static final long DEFAULT_TIMEOUT_SECONDS = 300L;

    enum Status { COMPLETED, TIMEOUT, FAILURE, CANCELED }

    record Outcome(@NotNull Status status, @NotNull List<String> errors, @NotNull String message) {
        static Outcome completed(@NotNull List<String> errors) {
            return new Outcome(Status.COMPLETED, List.copyOf(errors), "");
        }

        static Outcome timeout() {
            return new Outcome(Status.TIMEOUT, Collections.emptyList(), "validation timed out");
        }

        static Outcome failure(@NotNull String message) {
            return new Outcome(Status.FAILURE, Collections.emptyList(), message);
        }

        static Outcome canceled() {
            return new Outcome(Status.CANCELED, Collections.emptyList(), "validation wait canceled");
        }
    }

    @FunctionalInterface
    interface CacheProbe {
        @Nullable List<String> probe() throws Exception;
    }

    @FunctionalInterface
    interface Validation {
        @NotNull List<String> run() throws Exception;
    }

    private final Project project;
    private final ExecutorService executor;
    private final Map<String, Flight> flights = new LinkedHashMap<>();

    private static final class Flight {
        private final CompletableFuture<Outcome> future = new CompletableFuture<>();
        private int requestCount = 1;
    }

    public PrePushValidationCoordinator(@NotNull Project project) {
        this.project = project;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "PrePushChecker-Validation-" + project.getName());
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newSingleThreadExecutor(factory);
    }

    static @NotNull PrePushValidationCoordinator getInstance(@NotNull Project project) {
        return project.getService(PrePushValidationCoordinator.class);
    }

    @NotNull Outcome request(
        @NotNull String key,
        @NotNull CacheProbe cacheProbe,
        @NotNull Validation validation,
        @Nullable ProgressIndicator waiterIndicator
    ) {
        return request(key, cacheProbe, validation, waiterIndicator, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @NotNull Outcome request(
        @NotNull String key,
        @NotNull CacheProbe cacheProbe,
        @NotNull Validation validation,
        @Nullable ProgressIndicator waiterIndicator,
        long timeout,
        @NotNull TimeUnit unit
    ) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            return Outcome.failure("validation cannot wait on the EDT");
        }
        if (key.isBlank()) {
            return Outcome.failure("validation key is blank");
        }

        CompletableFuture<Outcome> future;
        synchronized (flights) {
            Flight flight = flights.get(key);
            if (flight == null) {
                flight = new Flight();
                flights.put(key, flight);
                Flight ownerFlight = flight;
                executor.execute(() -> execute(key, cacheProbe, validation, ownerFlight));
            } else {
                flight.requestCount++;
            }
            future = flight.future;
        }

        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            if (waiterIndicator != null && waiterIndicator.isCanceled()) {
                return Outcome.canceled();
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return Outcome.timeout();
            }
            try {
                return future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100L)),
                    TimeUnit.NANOSECONDS);
            } catch (TimeoutException ignored) {
                // Recheck waiter cancellation and the overall deadline.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Outcome.canceled();
            } catch (ExecutionException impossible) {
                Throwable cause = impossible.getCause();
                return Outcome.failure(messageOf(cause));
            }
        }
    }

    private void execute(
        String key,
        CacheProbe cacheProbe,
        Validation validation,
        Flight flight
    ) {
        Outcome outcome;
        try {
            if (project.isDisposed()) {
                outcome = Outcome.failure("project disposed");
            } else {
                List<String> cached = cacheProbe.probe();
                outcome = Outcome.completed(cached != null ? cached : validation.run());
            }
        } catch (ProcessCanceledException canceled) {
            outcome = Outcome.failure("validation owner canceled");
        } catch (TimeoutException timeout) {
            outcome = Outcome.timeout();
        } catch (Throwable failure) {
            outcome = Outcome.failure(messageOf(failure));
        }
        flight.future.complete(outcome);
        synchronized (flights) {
            flights.remove(key, flight);
        }
    }

    int requestCountForTest(@NotNull String key) {
        synchronized (flights) {
            Flight flight = flights.get(key);
            return flight == null ? 0 : flight.requestCount;
        }
    }

    private static String messageOf(@Nullable Throwable failure) {
        if (failure == null) return "unknown validation failure";
        String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName()
            : message;
    }

    @Override
    public void dispose() {
        executor.shutdownNow();
        synchronized (flights) {
            Outcome disposed = Outcome.failure("project disposed");
            flights.values().forEach(flight -> flight.future.complete(disposed));
            flights.clear();
        }
    }
}
