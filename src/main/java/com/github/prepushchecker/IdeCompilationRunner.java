package com.github.prepushchecker;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompileStatusNotification;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

final class IdeCompilationRunner {
    private static final Logger LOG = Logger.getInstance(IdeCompilationRunner.class);
    private static final long WAIT_SLICE_MILLIS = 250L;
    private static final long DEFAULT_TIMEOUT_SECONDS = 300L;
    private static final java.util.regex.Pattern THROWABLE =
        java.util.regex.Pattern.compile("\\b(?:java|kotlin)\\.[\\w.]*(?:exception|error)\\b");

    private IdeCompilationRunner() {
    }

    static @NotNull List<String> runWithRecovery(
        @NotNull Project project,
        @NotNull ProgressIndicator indicator,
        @NotNull CompilerManager compilerManager,
        @NotNull CompilationStarter initialCompilation
    ) {
        try {
            return runWithRecovery(
                project,
                indicator,
                compilerManager,
                initialCompilation,
                DEFAULT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            return Collections.singletonList(timeout.getMessage());
        }
    }

    static @NotNull List<String> runWithRecovery(
        @NotNull Project project,
        @NotNull ProgressIndicator indicator,
        @NotNull CompilerManager compilerManager,
        @NotNull CompilationStarter initialCompilation,
        long timeout,
        @NotNull TimeUnit unit
    ) throws TimeoutException {
        try {
            long startNanos = System.nanoTime();
            long deadlineNanos = startNanos + unit.toNanos(timeout);
            AttemptResult initial =
                runCompilation(project, indicator, initialCompilation, deadlineNanos);
            RecoveryOutcome outcome = recover(project, initial, () -> {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining < (deadlineNanos - startNanos) / 2) {
                    LOG.info("IDE compilation failed without file locations, but too little "
                        + "time is left for a clean rebuild; reporting the incremental result.");
                    return initial;
                }
                LOG.info("IDE compilation failed without file locations; rebuilding project "
                    + "once before publishing an authoritative result.");
                indicator.setText("Rebuilding project to verify compiler errors");
                return runCompilation(
                    project, indicator, compilerManager::rebuild, deadlineNanos);
            });

            record(project, outcome.finalResult());
            return DiagnosticPathMatcher.filterIgnored(project, outcome.finalResult().errors());
        } catch (TimeoutException timeoutException) {
            recordUnavailable(project, timeoutException.getMessage());
            throw timeoutException;
        }
    }

    static @NotNull List<String> runOnce(
        @NotNull Project project,
        @NotNull ProgressIndicator indicator,
        @NotNull CompilationStarter compilation
    ) {
        try {
            AttemptResult result = runCompilation(
                project,
                indicator,
                compilation,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(DEFAULT_TIMEOUT_SECONDS));
            record(project, result);
            return DiagnosticPathMatcher.filterIgnored(project, result.errors());
        } catch (TimeoutException timeout) {
            recordUnavailable(project, timeout.getMessage());
            return Collections.singletonList(timeout.getMessage());
        }
    }

    /**
     * A clean rebuild wipes the JPS caches and recompiles the whole project. That is only worth
     * its cost when the incremental build failed without telling us where (internal compiler or
     * JPS cache trouble). Ordinary file-located errors are real and are returned immediately.
     */
    static @NotNull RecoveryOutcome recover(
        @NotNull AttemptResult initial,
        @NotNull AttemptSupplier cleanRebuild
    ) throws TimeoutException {
        if (initial.aborted() || initial.errorCount() <= 0) {
            return new RecoveryOutcome(initial, false, "");
        }
        if (!looksLikeInfrastructureFailure(initial.errors())) {
            return new RecoveryOutcome(initial, false, "file-located compiler errors");
        }
        return new RecoveryOutcome(cleanRebuild.get(), true, "compiler infrastructure failure");
    }

    static @NotNull RecoveryOutcome recover(
        @NotNull Project project,
        @NotNull AttemptResult initial,
        @NotNull AttemptSupplier cleanRebuild
    ) throws TimeoutException {
        if (initial.aborted() || initial.errorCount() <= 0
                || DiagnosticPathMatcher.filterIgnored(project, initial.errors()).isEmpty()) {
            return new RecoveryOutcome(initial, false,
                initial.errorCount() > 0 ? "only ignored compiler errors" : "");
        }
        return recover(initial, cleanRebuild);
    }

    static boolean looksLikeInfrastructureFailure(@NotNull List<String> errors) {
        if (errors.isEmpty()) return true;
        for (String error : errors) {
            if (error == null) continue;
            String lower = error.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("[unknown]")
                    || lower.contains("unknown compiler error")
                    || lower.contains("internal error")
                    || lower.contains("cannot start compiler")
                    || lower.contains("caches are corrupted")
                    || lower.contains("cache corrupted")
                    || (!lower.startsWith("[") && THROWABLE.matcher(lower).find())
                    || lower.contains("outofmemoryerror")) {
                return true;
            }
        }
        return false;
    }

    private static @NotNull AttemptResult runCompilation(
        @NotNull Project project,
        @NotNull ProgressIndicator indicator,
        @NotNull CompilationStarter compilation,
        long deadlineNanos
    ) throws TimeoutException {
        Application application = ApplicationManager.getApplication();
        if (application.isDispatchThread()) {
            throw new IllegalStateException("IDE compilation cannot wait on the EDT");
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AttemptResult> result =
            new AtomicReference<>(AttemptResult.clean());
        Runnable startCompilation = () -> compilation.start(
            (aborted, errorCount, warnings, compileContext) -> {
                List<String> errors;
                if (aborted) {
                    errors = Collections.singletonList("Compilation was aborted.");
                } else if (errorCount > 0 && compileContext != null) {
                    errors = CompilationSupport.formatCompilerMessages(
                        project,
                        compileContext.getMessages(CompilerMessageCategory.ERROR));
                } else if (errorCount > 0) {
                    errors = Collections.singletonList(
                        "Compilation failed with an unknown compiler error.");
                } else {
                    errors = Collections.emptyList();
                }
                result.set(new AttemptResult(errors, aborted, errorCount));
                latch.countDown();
            });

        // An open modal dialog (Settings, commit) must not hold a push check hostage until it is
        // closed, and make() saves documents, so it needs a write-safe context (see WriteSafeEdt).
        // Not awaited here: the loop below waits for the result within the deadline.
        java.util.concurrent.CompletableFuture<Void> started = WriteSafeEdt.invokeLater(startCompilation);

        try {
            while (true) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    throw new TimeoutException("IDE compiler validation timed out.");
                }
                long waitMillis = Math.max(
                    1L,
                    Math.min(
                        WAIT_SLICE_MILLIS,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                if (latch.await(waitMillis, TimeUnit.MILLISECONDS)) {
                    return result.get();
                }
                if (started.isCompletedExceptionally()) {
                    try {
                        started.join();
                    } catch (java.util.concurrent.CompletionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException re) throw re;
                        if (cause instanceof Error err) throw err;
                        throw new IllegalStateException(cause);
                    }
                }
                indicator.checkCanceled();
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return new AttemptResult(
                Collections.singletonList("Compilation check was interrupted."), true, 0);
        }
    }

    private static void record(
        @NotNull Project project,
        @NotNull AttemptResult result
    ) {
        CompilationErrorService.getInstance(project).setErrors(result.errors());
    }

    private static void recordUnavailable(
        @NotNull Project project,
        @NotNull String message
    ) {
        CompilationErrorService.getInstance(project)
            .setErrors(Collections.singletonList(message));
    }

    @FunctionalInterface
    interface CompilationStarter {
        void start(@NotNull CompileStatusNotification notification);
    }

    @FunctionalInterface
    interface AttemptSupplier {
        @NotNull AttemptResult get() throws TimeoutException;
    }

    record AttemptResult(@NotNull List<String> errors, boolean aborted, int errorCount) {
        AttemptResult {
            errors = List.copyOf(errors);
        }

        private static @NotNull AttemptResult clean() {
            return new AttemptResult(Collections.emptyList(), false, 0);
        }
    }

    record RecoveryOutcome(
        @NotNull AttemptResult finalResult,
        boolean rebuilt,
        @NotNull String recoveryReason
    ) {
    }
}
