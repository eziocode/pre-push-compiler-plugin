package com.github.prepushchecker;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class PrePushValidationCoordinatorTest extends BasePlatformTestCase {
    public void testWaitTimeoutDoesNotCancelValidation() throws Exception {
        PrePushValidationCoordinator coordinator =
            PrePushValidationCoordinator.getInstance(getProject());
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch validationStarted = new CountDownLatch(1);
        CountDownLatch releaseValidation = new CountDownLatch(1);
        try {
            Future<PrePushValidationCoordinator.Outcome> timedOut = caller.submit(() ->
                coordinator.request("timeout", () -> null, () -> {
                    validationStarted.countDown();
                    assertTrue(releaseValidation.await(5, TimeUnit.SECONDS));
                    return List.of();
                }, null, 20, TimeUnit.MILLISECONDS));
            assertTrue(validationStarted.await(5, TimeUnit.SECONDS));
            assertEquals(
                PrePushValidationCoordinator.Status.TIMEOUT,
                timedOut.get(5, TimeUnit.SECONDS).status());
            assertEquals(1, coordinator.requestCountForTest("timeout"));

            releaseValidation.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (coordinator.requestCountForTest("timeout") != 0
                    && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(0, coordinator.requestCountForTest("timeout"));
            assertEquals(300L, PrePushValidationCoordinator.DEFAULT_TIMEOUT_SECONDS);
        } finally {
            caller.shutdownNow();
        }
    }

    public void testExactKeyRequestsShareOneValidationAndOutcome() throws Exception {
        PrePushValidationCoordinator coordinator =
            PrePushValidationCoordinator.getInstance(getProject());
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch validationStarted = new CountDownLatch(1);
        CountDownLatch releaseValidation = new CountDownLatch(1);
        AtomicInteger validations = new AtomicInteger();
        try {
            Future<PrePushValidationCoordinator.Outcome> first = callers.submit(() ->
                coordinator.request("same", () -> null, () -> {
                    validations.incrementAndGet();
                    validationStarted.countDown();
                    assertTrue(releaseValidation.await(5, TimeUnit.SECONDS));
                    return List.of("shared error");
                }, null));
            assertTrue(validationStarted.await(5, TimeUnit.SECONDS));

            Future<PrePushValidationCoordinator.Outcome> second = callers.submit(() ->
                coordinator.request("same", () -> null, () -> {
                    fail("joined request must not validate");
                    return List.of();
                }, null));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (coordinator.requestCountForTest("same") < 2
                    && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(2, coordinator.requestCountForTest("same"));
            releaseValidation.countDown();

            assertEquals(List.of("shared error"), first.get(5, TimeUnit.SECONDS).errors());
            assertEquals(List.of("shared error"), second.get(5, TimeUnit.SECONDS).errors());
            assertEquals(1, validations.get());
        } finally {
            callers.shutdownNow();
        }
    }

    public void testDifferentKeysSerializeThenRecheckCoverage() throws Exception {
        PrePushValidationCoordinator coordinator =
            PrePushValidationCoordinator.getInstance(getProject());
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondRequested = new CountDownLatch(1);
        AtomicReference<List<String>> covered = new AtomicReference<>();
        AtomicInteger secondValidations = new AtomicInteger();
        try {
            Future<PrePushValidationCoordinator.Outcome> first = callers.submit(() ->
                coordinator.request("older-narrower", () -> null, () -> {
                    firstStarted.countDown();
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                    List<String> result = List.of();
                    covered.set(result);
                    return result;
                }, null));
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

            Future<PrePushValidationCoordinator.Outcome> second = callers.submit(() -> {
                secondRequested.countDown();
                return coordinator.request("newer-covered", covered::get, () -> {
                    secondValidations.incrementAndGet();
                    return List.of("unexpected");
                }, null);
            });
            assertTrue(secondRequested.await(5, TimeUnit.SECONDS));
            releaseFirst.countDown();

            assertTrue(first.get(5, TimeUnit.SECONDS).errors().isEmpty());
            assertTrue(second.get(5, TimeUnit.SECONDS).errors().isEmpty());
            assertEquals(0, secondValidations.get());
        } finally {
            callers.shutdownNow();
        }
    }

    public void testCanceledWaiterDoesNotCancelOwner() throws Exception {
        PrePushValidationCoordinator coordinator =
            PrePushValidationCoordinator.getInstance(getProject());
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch validationStarted = new CountDownLatch(1);
        CountDownLatch releaseValidation = new CountDownLatch(1);
        try {
            Future<PrePushValidationCoordinator.Outcome> owner = callers.submit(() ->
                coordinator.request("cancel", () -> null, () -> {
                    validationStarted.countDown();
                    assertTrue(releaseValidation.await(5, TimeUnit.SECONDS));
                    return List.of();
                }, null));
            assertTrue(validationStarted.await(5, TimeUnit.SECONDS));

            EmptyProgressIndicator canceled = new EmptyProgressIndicator();
            canceled.cancel();
            Future<PrePushValidationCoordinator.Outcome> waiter = callers.submit(() ->
                coordinator.request("cancel", () -> null, List::of, canceled));

            assertEquals(
                PrePushValidationCoordinator.Status.CANCELED,
                waiter.get(5, TimeUnit.SECONDS).status());
            releaseValidation.countDown();
            assertEquals(
                PrePushValidationCoordinator.Status.COMPLETED,
                owner.get(5, TimeUnit.SECONDS).status());
        } finally {
            callers.shutdownNow();
        }
    }
}
