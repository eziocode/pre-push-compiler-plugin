package com.github.prepushchecker.commitgen.cli;

import com.github.prepushchecker.commitgen.AiRequest;
import junit.framework.TestCase;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class AiCallSchedulerTest extends TestCase {

    private ExecutorService pool;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        pool = Executors.newCachedThreadPool();
    }

    @Override
    protected void tearDown() throws Exception {
        pool.shutdownNow();
        super.tearDown();
    }

    public void testLanes() throws Exception {
        assertEquals(AiCallScheduler.Lane.PUSH, AiCallScheduler.laneFor(AiRequest.review("s", "u")));
        assertEquals(AiCallScheduler.Lane.INTERACTIVE, AiCallScheduler.laneFor(AiRequest.commitMessage("s", "u")));
        assertEquals(AiCallScheduler.Lane.INTERACTIVE, AiCallScheduler.laneFor(AiRequest.fix("s", "u")));
        assertEquals(AiCallScheduler.Lane.BACKGROUND, AiCallScheduler.inBackground(
            () -> AiCallScheduler.laneFor(AiRequest.commitMessage("s", "u"))));
    }

    public void testInteractiveCancelsBackgroundWhenFull() throws Exception {
        AiCallScheduler scheduler = new AiCallScheduler(1, 1);
        CountDownLatch bgStarted = new CountDownLatch(1);
        Future<String> background = pool.submit(() -> scheduler.run(AiCallScheduler.Lane.BACKGROUND, () -> {
            bgStarted.countDown();
            Thread.sleep(30_000); // a slow CLI; interrupted when it has to yield
            return "late";
        }));
        assertTrue(bgStarted.await(5, TimeUnit.SECONDS));
        long started = System.nanoTime();
        String result = scheduler.run(AiCallScheduler.Lane.INTERACTIVE, () -> "now");
        assertEquals("now", result);
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 5);
        try {
            background.get(5, TimeUnit.SECONDS);
            fail("background call should have been cancelled");
        } catch (java.util.concurrent.ExecutionException e) {
            assertTrue(e.getCause() instanceof CancellationException);
        }
        assertEquals(0, scheduler.running());
    }

    public void testHigherLaneGoesFirstAndBackgroundIsLimited() throws Exception {
        AiCallScheduler scheduler = new AiCallScheduler(1, 1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch holding = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();
        Future<?> holder = pool.submit(() -> scheduler.run(AiCallScheduler.Lane.PUSH, () -> {
            holding.countDown();
            release.await();
            return null;
        }));
        assertTrue(holding.await(5, TimeUnit.SECONDS));
        Future<?> push = pool.submit(() -> scheduler.run(AiCallScheduler.Lane.PUSH, () -> order.add("push")));
        Thread.sleep(100);
        Future<?> interactive = pool.submit(() -> scheduler.run(AiCallScheduler.Lane.INTERACTIVE, () -> order.add("interactive")));
        Thread.sleep(100);
        release.countDown();
        holder.get(5, TimeUnit.SECONDS);
        push.get(5, TimeUnit.SECONDS);
        interactive.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("interactive", "push"), order);
    }

    public void testNestedCallsDoNotWaitAgain() throws Exception {
        AiCallScheduler scheduler = new AiCallScheduler(1, 1);
        String out = scheduler.run(AiCallScheduler.Lane.INTERACTIVE,
            () -> scheduler.run(AiCallScheduler.Lane.INTERACTIVE, () -> "inner"));
        assertEquals("inner", out);
        assertEquals(0, scheduler.running());
    }
}
