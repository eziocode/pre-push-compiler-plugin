package com.github.prepushchecker;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.compiler.CompileStatusNotification;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

final class IdeCompilationRunner {
    private static final Logger LOG = Logger.getInstance(IdeCompilationRunner.class);
    private static final long WAIT_SLICE_MILLIS = 250L;
    private static final long DEFAULT_TIMEOUT_SECONDS = 300L;

    private IdeCompilationRunner() {
    }

    static @NotNull List<String> runWithRecovery(
        @NotNull Project project,
        @NotNull ProgressIndicator indicator,
        boolean projectScope,
        @NotNull Map<String, Long> stamps,
        @NotNull CompilerManager compilerManager,
        @NotNull CompilationStarter initialCompilation
    ) {
        try {
            return runWithRecovery(
                project,
                indicator,
                projectScope,
                stamps,
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
        boolean projectScope,
        @NotNull Map<String, Long> stamps,
        @NotNull CompilerManager compilerManager,
        @NotNull CompilationStarter initialCompilation,
        long timeout,
        @NotNull TimeUnit unit
    ) throws TimeoutException {
        try {
            long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
            AttemptResult initial =
                runCompilation(project, indicator, initialCompilation, deadlineNanos);
            RecoveryOutcome outcome = recover(initial, () -> {
                CompilationFailureClassifier.RecoveryDecision decision =
                    CompilationFailureClassifier.classify(initial.errors());
                LOG.info("IDE compilation reported likely stale compiler state ("
                    + decision.reason() + "); rebuilding project once.");
                indicator.setText("Rebuilding project to verify compiler errors");
                return runCompilation(
                    project, indicator, compilerManager::rebuild, deadlineNanos);
            });

            boolean finalProjectScope = projectScope || outcome.rebuilt();
            Map<String, Long> finalStamps =
                outcome.rebuilt() ? Collections.emptyMap() : stamps;
            record(project, finalProjectScope, finalStamps, outcome.finalResult());
            return outcome.finalResult().errors();
        } catch (TimeoutException timeoutException) {
            recordUnavailable(project, timeoutException.getMessage());
            throw timeoutException;
        }
    }

    static @NotNull List<String> runOnce(
        @NotNull Project project,
        @NotNull ProgressIndicator indicator,
        boolean projectScope,
        @NotNull Map<String, Long> stamps,
        @NotNull CompilationStarter compilation
    ) {
        try {
            AttemptResult result = runCompilation(
                project,
                indicator,
                compilation,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(DEFAULT_TIMEOUT_SECONDS));
            record(project, projectScope, stamps, result);
            return result.errors();
        } catch (TimeoutException timeout) {
            recordUnavailable(project, timeout.getMessage());
            return Collections.singletonList(timeout.getMessage());
        }
    }

    static @NotNull RecoveryOutcome recover(
        @NotNull AttemptResult initial,
        @NotNull AttemptSupplier cleanRebuild
    ) throws TimeoutException {
        if (initial.aborted() || initial.errorCount() <= 0) {
            return new RecoveryOutcome(initial, false, "");
        }
        CompilationFailureClassifier.RecoveryDecision decision =
            CompilationFailureClassifier.classify(initial.errors());
        if (!decision.shouldRecover()) {
            return new RecoveryOutcome(initial, false, "");
        }
        return new RecoveryOutcome(cleanRebuild.get(), true, decision.reason());
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
                    errors = PrePushCompilationHandler.formatCompilerMessages(
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

        ModalityState modality = indicator.getModalityState();
        application.invokeAndWait(
            startCompilation,
            modality != null ? modality : ModalityState.defaultModalityState());

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
        boolean projectScope,
        @NotNull Map<String, Long> stamps,
        @NotNull AttemptResult result
    ) {
        CompilationErrorService service = CompilationErrorService.getInstance(project);
        if (result.aborted()) {
            service.invalidateFreshness();
            service.setErrors(result.errors());
        } else {
            service.recordCompletion(projectScope, stamps, result.errors());
        }
    }

    private static void recordUnavailable(
        @NotNull Project project,
        @NotNull String message
    ) {
        CompilationErrorService service = CompilationErrorService.getInstance(project);
        service.invalidateFreshness();
        service.setErrors(Collections.singletonList(message));
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
