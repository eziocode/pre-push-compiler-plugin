package com.github.prepushchecker.commitgen.cli;

import com.github.prepushchecker.commitgen.AiRequest;
import com.github.prepushchecker.commitgen.CommitMessageProvider;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single gate for every AI provider call (CLI and HTTP), with priority lanes:
 * <ul>
 *   <li>{@link Lane#INTERACTIVE}: the user is waiting (commit message, quick fix, inline).</li>
 *   <li>{@link Lane#PUSH}: pre-push review.</li>
 *   <li>{@link Lane#BACKGROUND}: work done ahead of time (speculative commit message, prefetch).</li>
 * </ul>
 * At most {@value #TOTAL_SLOTS} calls run at once and at most one of them is background work. A
 * waiting call goes before any waiting call of a lower lane, and an interactive call that finds
 * every slot taken cancels the running background call (its thread is interrupted, which kills the
 * CLI process; the background caller gets a {@link CancellationException}).
 */
public final class AiCallScheduler {

    public enum Lane { INTERACTIVE, PUSH, BACKGROUND }

    static final int TOTAL_SLOTS = 3;
    static final int BACKGROUND_SLOTS = 1;

    private static final AiCallScheduler INSTANCE = new AiCallScheduler(TOTAL_SLOTS, BACKGROUND_SLOTS);

    /** Lane forced for the calls made by the current thread (see {@link #inBackground}). */
    private static final ThreadLocal<Lane> FORCED = new ThreadLocal<>();
    /** Set while this thread holds a slot: nested provider calls (Other CLI → claude) do not wait again. */
    private static final ThreadLocal<Boolean> HOLDING = new ThreadLocal<>();

    private final int total;
    private final int backgroundMax;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final int[] waiting = new int[Lane.values().length];
    private final Set<Thread> backgroundThreads = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Thread> preempted = Collections.newSetFromMap(new IdentityHashMap<>());
    private int running;
    private int runningBackground;

    AiCallScheduler(int total, int backgroundMax) {
        this.total = total;
        this.backgroundMax = backgroundMax;
    }

    public static @NotNull AiCallScheduler getInstance() {
        return INSTANCE;
    }

    /** The lane for {@code request} on this thread: a forced lane, else review = push, rest = interactive. */
    public static @NotNull Lane laneFor(@NotNull AiRequest request) {
        Lane forced = FORCED.get();
        if (forced != null) return forced;
        return request.kind() == AiRequest.Kind.REVIEW ? Lane.PUSH : Lane.INTERACTIVE;
    }

    /** Runs {@code body} with every AI call it makes on this thread in the background lane. */
    public static <T> T inBackground(@NotNull Callable<T> body) throws Exception {
        Lane previous = FORCED.get();
        FORCED.set(Lane.BACKGROUND);
        try {
            return body.call();
        } finally {
            if (previous == null) FORCED.remove(); else FORCED.set(previous);
        }
    }

    /** {@code true} inside {@link #inBackground}: for handing the lane on to worker threads. */
    public static boolean isBackgroundThread() {
        return FORCED.get() == Lane.BACKGROUND;
    }

    /** Wraps {@code provider} so each request waits for a slot in its lane. */
    public static @NotNull CommitMessageProvider wrap(@NotNull CommitMessageProvider provider) {
        if (provider instanceof Scheduled) return provider;
        return new Scheduled(provider, INSTANCE);
    }

    /** Runs {@code body} once a slot in {@code lane} is free. */
    public <T> T run(@NotNull Lane lane, @NotNull Callable<T> body) throws Exception {
        if (Boolean.TRUE.equals(HOLDING.get())) return body.call();
        acquire(lane);
        HOLDING.set(true);
        boolean wasPreempted = false;
        try {
            return body.call();
        } catch (InterruptedException e) {
            wasPreempted = lane == Lane.BACKGROUND && clearPreempted();
            if (wasPreempted) throw cancelled(e);
            throw e;
        } catch (RuntimeException e) {
            wasPreempted = lane == Lane.BACKGROUND && clearPreempted();
            if (wasPreempted) throw cancelled(e);
            throw e;
        } finally {
            HOLDING.remove();
            release(lane);
            // Our interrupt was meant for the CLI process, not for the caller's next blocking call.
            if (wasPreempted || (lane == Lane.BACKGROUND && clearPreempted())) Thread.interrupted();
        }
    }

    private static CancellationException cancelled(Exception cause) {
        CancellationException c = new CancellationException("Background AI call yielded to an interactive one");
        c.initCause(cause);
        return c;
    }

    private void acquire(@NotNull Lane lane) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            waiting[lane.ordinal()]++;
            try {
                while (!canRun(lane)) {
                    if (lane == Lane.INTERACTIVE && running >= total) preemptBackground();
                    changed.await();
                }
            } finally {
                waiting[lane.ordinal()]--;
            }
            running++;
            if (lane == Lane.BACKGROUND) {
                runningBackground++;
                backgroundThreads.add(Thread.currentThread());
            }
        } finally {
            lock.unlock();
        }
    }

    private void release(@NotNull Lane lane) {
        lock.lock();
        try {
            running--;
            if (lane == Lane.BACKGROUND) {
                runningBackground--;
                backgroundThreads.remove(Thread.currentThread());
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private boolean canRun(@NotNull Lane lane) {
        if (running >= total) return false;
        for (int higher = 0; higher < lane.ordinal(); higher++) {
            if (waiting[higher] > 0) return false;
        }
        return lane != Lane.BACKGROUND || runningBackground < backgroundMax;
    }

    private void preemptBackground() {
        for (Thread t : backgroundThreads) {
            if (preempted.add(t)) t.interrupt();
        }
    }

    private boolean clearPreempted() {
        lock.lock();
        try {
            return preempted.remove(Thread.currentThread());
        } finally {
            lock.unlock();
        }
    }

    /** Calls running now; for tests. */
    int running() {
        lock.lock();
        try {
            return running;
        } finally {
            lock.unlock();
        }
    }

    /** A provider whose requests go through the scheduler. */
    private static final class Scheduled implements CommitMessageProvider {
        private final CommitMessageProvider delegate;
        private final AiCallScheduler scheduler;

        Scheduled(CommitMessageProvider delegate, AiCallScheduler scheduler) {
            this.delegate = delegate;
            this.scheduler = scheduler;
        }

        @Override
        public @NotNull String generate(@NotNull AiRequest request) throws Exception {
            return scheduler.run(laneFor(request), () -> delegate.generate(request));
        }

        @Override
        public @NotNull Id id() {
            return delegate.id();
        }
    }
}
