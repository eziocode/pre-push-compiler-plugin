package com.github.prepushchecker;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-project TCP-loopback server that lets external git clients (terminal, Sublime Merge, …)
 * reuse the IDE's incremental JPS compiler instead of spawning a full Gradle/Maven build.
 *
 * <p>Protocol (line-delimited, UTF-8):
 * <pre>
 *   C -> S : CHECK 2\nTOKEN=&lt;secret&gt;\nROOT=&lt;path&gt;\nHEAD=&lt;sha&gt;\nUPDATES=&lt;id&gt;\nPATH=&lt;path&gt;...\n\n
 *   S -> C : OK\n                              (compile succeeded)
 *           | ERRORS &lt;n&gt;\n&lt;line&gt;...\nEND\n    (n errors follow)
 *           | STALE &lt;reason&gt;\n                  (repository changed during check)
 *           | FAIL &lt;reason&gt;\n                   (server could not run the check)
 * </pre>
 *
 * <p>The server binds to {@code 127.0.0.1} on an ephemeral port and writes the port to
 * {@link #PORT_FILE_RELATIVE} (line 1) together with a per-session random token (line 2) that
 * every request must present; the file is owner-readable only where the filesystem supports
 * POSIX permissions. When the IDE is closed the port file is removed and the hook
 * silently falls back to the build-tool path.
 */
public final class PrePushLocalServer implements Disposable {
    static final String PORT_FILE_RELATIVE = ".idea/pre-push-checker/server.port";
    private static final Logger LOG = Logger.getInstance(PrePushLocalServer.class);
    private static final long COMPILE_TIMEOUT_SECONDS = 300L;
    private static final long REVIEW_BUDGET_SECONDS = 200L;
    private static final long MIN_COMPILE_SECONDS = 30L;
    private static final int BACKLOG = 4;
    private static final int MAX_REQUESTED_PATHS = 2_048;
    private static final int CLIENT_SO_TIMEOUT_MS = 305 * 1000;
    private static final int MAX_CLIENT_THREADS = 8;
    private static final int MAX_LINE_CHARS = 8_192;
    private static final int MAX_HEADER_LINES = MAX_REQUESTED_PATHS + 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Project project;
    private final String authToken = newAuthToken();
    private final ExecutorService clientExecutor;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile ServerSocket server;
    private volatile Thread acceptThread;
    private final Set<Path> portFiles = ConcurrentHashMap.newKeySet();

    public PrePushLocalServer(@NotNull Project project) {
        this.project = project;
        // Bounded: surplus connections are rejected instead of spawning unlimited threads.
        this.clientExecutor = new ThreadPoolExecutor(
            0, MAX_CLIENT_THREADS, 30L, TimeUnit.SECONDS,
            new SynchronousQueue<>(), clientThreadFactory(project.getName()));
    }

    private static String newAuthToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean tokenMatches(String presented) {
        return MessageDigest.isEqual(
            authToken.getBytes(StandardCharsets.UTF_8),
            presented.getBytes(StandardCharsets.UTF_8));
    }

    /** Reads one line, failing instead of buffering without bound when a peer never sends a newline. */
    private static String readBoundedLine(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                return sb.toString();
            }
            if (sb.length() >= MAX_LINE_CHARS) throw new IOException("request line too long");
            sb.append((char) ch);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Writes the listening port to {@code portFile} via a temp-file + atomic move so
     * a concurrently-reading hook never observes a zero-byte or partial file.
     * Falls back to a regular replace move when the filesystem does not support
     * atomic moves (e.g. some network mounts).
     */
    private void writePortFileAtomically(Path portFile, int port) throws IOException {
        String content = port + "\n" + authToken + "\n";
        try {
            if (Files.isRegularFile(portFile)
                    && content.equals(Files.readString(portFile, StandardCharsets.UTF_8))) {
                return; // unchanged
            }
        } catch (IOException unreadable) {
            // rewrite below
        }
        Path tmp = portFile.resolveSibling(portFile.getFileName().toString() + ".tmp");
        Files.deleteIfExists(tmp);
        try {
            // Create owner-only up front so the token is never briefly world-readable.
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException nonPosix) {
            Files.createFile(tmp);
        }
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, portFile,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, portFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw ex;
        }
    }

    void start() {
        if (project.isDisposed()) return;
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) return;
        if (!started.compareAndSet(false, true)) return;

        try {
            ServerSocket s = new ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress());
            server = s;
            publishPortFile(basePath);
            for (git4idea.repo.GitRepository repository
                    : git4idea.repo.GitRepositoryManager.getInstance(project).getRepositories()) {
                publishPortFile(repository.getRoot().getPath());
            }

            Thread t = new Thread(this::acceptLoop, "PrePushChecker-Server-" + project.getName());
            t.setDaemon(true);
            t.start();
            acceptThread = t;

            LOG.info("PrePushLocalServer listening on 127.0.0.1:" + s.getLocalPort()
                + " for project '" + project.getName() + "'");
        } catch (IOException ex) {
            ServerSocket s = server;
            server = null;
            if (s != null) {
                try {
                    s.close();
                } catch (IOException closeEx) {
                    ex.addSuppressed(closeEx);
                }
            }
            LOG.warn("Could not start local pre-push server; external hook will fall back to build tool.", ex);
            started.set(false);
        }
    }

    void publishPortFile(@NotNull String repositoryRoot) {
        ServerSocket activeServer = server;
        if (repositoryRoot.isBlank() || activeServer == null || activeServer.isClosed()) return;
        Path descriptor = Path.of(repositoryRoot, PORT_FILE_RELATIVE);
        try {
            GitHookInstaller.writeRepositoryCache(repositoryRoot, descriptor.getParent(), () -> {
                writePortFileAtomically(descriptor, activeServer.getLocalPort());
                portFiles.add(descriptor);
            });
        } catch (IOException failure) {
            LOG.warn("Could not publish IDE compiler port for " + repositoryRoot, failure);
        }
    }

    private void acceptLoop() {
        ServerSocket s = server;
        while (s != null && !s.isClosed() && !Thread.currentThread().isInterrupted()) {
            try {
                Socket socket = s.accept();
                socket.setSoTimeout(CLIENT_SO_TIMEOUT_MS);
                try {
                    clientExecutor.execute(() -> handleClient(socket));
                } catch (RejectedExecutionException rejected) {
                    LOG.warn("Pre-push client rejected because the server is busy or stopping.", rejected);
                    writeServerUnavailable(socket);
                }
            } catch (IOException e) {
                if (s.isClosed()) return;
                LOG.debug("Accept failed", e);
            } catch (Throwable t) {
                LOG.warn("Unexpected error in accept loop", t);
            }
        }
    }

    private static ThreadFactory clientThreadFactory(String projectName) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(
                runnable,
                "PrePushChecker-Client-" + projectName + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    private void writeServerUnavailable(Socket socket) {
        try (Socket c = socket;
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8))) {
            out.write("FAIL infrastructure server-busy-or-stopping\n");
            out.flush();
        } catch (IOException e) {
            LOG.debug("Could not notify pre-push client that the server is stopping", e);
        }
    }

    private void handleClient(Socket socket) {
        try (Socket c = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8))) {

            String line = readBoundedLine(in);
            String command = line == null ? "" : line.trim();
            boolean versionTwo = command.equals("CHECK 2");
            if (!versionTwo && !command.equals("CHECK")) {
                out.write("ERR unknown-request\n");
                out.flush();
                return;
            }
            String repositoryRoot = "";
            String expectedHead = "";
            String updatesFingerprint = "";
            String rangeBase = "";
            String pushedHead = "";
            String presentedToken = "";
            List<String> requestedPaths = new ArrayList<>();
            boolean pathLimitExceeded = false;
            int headerLines = 0;
            while ((line = readBoundedLine(in)) != null) {
                String trimmed = line.strip();
                if (trimmed.isEmpty()) break;
                if (++headerLines > MAX_HEADER_LINES) {
                    out.write("ERR request-too-large\n");
                    out.flush();
                    return;
                }
                if (versionTwo && trimmed.startsWith("TOKEN=")) {
                    presentedToken = trimmed.substring("TOKEN=".length());
                    continue;
                }
                if (versionTwo && trimmed.startsWith("ROOT=")) {
                    repositoryRoot = trimmed.substring("ROOT=".length());
                    continue;
                }
                if (versionTwo && trimmed.startsWith("HEAD=")) {
                    expectedHead = trimmed.substring("HEAD=".length());
                    continue;
                }
                if (versionTwo && trimmed.startsWith("UPDATES=")) {
                    updatesFingerprint = trimmed.substring("UPDATES=".length());
                    continue;
                }
                if (versionTwo && trimmed.startsWith("RANGE=")) {
                    // Remote side of the pushed range: the AI review diffs from here.
                    String base = trimmed.substring("RANGE=".length()).trim();
                    if (rangeBase.isEmpty() && base.matches("[0-9a-fA-F]{7,64}")) rangeBase = base;
                    continue;
                }
                if (versionTwo && trimmed.startsWith("PUSHED=")) {
                    // The pushed commit; differs from HEAD for "git push origin other-branch".
                    String sha = trimmed.substring("PUSHED=".length()).trim();
                    if (pushedHead.isEmpty() && sha.matches("[0-9a-fA-F]{7,64}")) pushedHead = sha;
                    continue;
                }
                if (versionTwo && trimmed.startsWith("PATH=")) {
                    trimmed = trimmed.substring("PATH=".length());
                }
                if (pathLimitExceeded) continue;
                if (requestedPaths.size() >= MAX_REQUESTED_PATHS) {
                    requestedPaths.clear();
                    pathLimitExceeded = true;
                    LOG.info("Pre-push request listed more than " + MAX_REQUESTED_PATHS
                        + " paths; using project-scope compile to keep memory bounded.");
                    continue;
                }
                requestedPaths.add(trimmed);
            }

            if (!versionTwo || !tokenMatches(presentedToken)) {
                out.write("ERR unauthorized\n");
                out.flush();
                return;
            }

            if (project.isDisposed()) {
                out.write("ERR project-disposed\n");
                out.flush();
                return;
            }

            ValidationRequest request = new ValidationRequest(
                normalizeRoot(repositoryRoot),
                expectedHead.trim(),
                updatesFingerprint.trim(),
                pathLimitExceeded ? Collections.emptyList() : requestedPaths,
                rangeBase,
                pushedHead);
            PrePushValidationCoordinator.Outcome outcome = runCompileWithProgress(request);
            if (outcome == USER_CANCELED) {
                out.write("ERRORS 1\n");
                out.write("Pre-push check stopped from IntelliJ. Push again to re-run it.\n");
                out.write("END\n");
                out.flush();
                return;
            }
            if (outcome.status() == PrePushValidationCoordinator.Status.COMPLETED) {
                com.github.prepushchecker.stats.SharedStats.getInstance().maybeAskConsent(project);
            }
            if (outcome.status() == PrePushValidationCoordinator.Status.TIMEOUT) {
                out.write("TIMEOUT validation-timeout\n");
            } else if (outcome.status() == PrePushValidationCoordinator.Status.STALE) {
                out.write("STALE "
                    + outcome.message().replace('\r', ' ').replace('\n', ' ') + "\n");
            } else if (outcome.status() != PrePushValidationCoordinator.Status.COMPLETED) {
                out.write("FAIL infrastructure "
                    + outcome.message().replace('\r', ' ').replace('\n', ' ') + "\n");
            } else if (outcome.errors().isEmpty()) {
                CommitShaClipboardCheckinHandler.copyValidatedPushShaSilently(
                    project, request.expectedHead());
                out.write("OK\n");
            } else {
                out.write("ERRORS " + outcome.errors().size() + "\n");
                for (String e : outcome.errors()) {
                    out.write(e.replace('\r', ' ').replace('\n', ' '));
                    out.write('\n');
                }
                out.write("END\n");
            }
            out.flush();
        } catch (IOException e) {
            LOG.debug("Client handling failed", e);
        }
    }

    /** Marker outcome: the user pressed Stop on the progress shown for this push. */
    private static final PrePushValidationCoordinator.Outcome USER_CANCELED =
        new PrePushValidationCoordinator.Outcome(PrePushValidationCoordinator.Status.CANCELED,
            Collections.emptyList(), "stopped by the user");

    /**
     * Runs the check for a push as a cancellable background task, so a push started outside
     * IntelliJ (terminal, other tools) shows its compile / AI review progress in the status bar
     * like an IDE action, and the user can stop it there. The socket thread waits for the task.
     */
    private PrePushValidationCoordinator.Outcome runCompileWithProgress(ValidationRequest request) {
        if (ApplicationManager.getApplication().isHeadlessEnvironment()) return runCompile(request, null);
        java.util.concurrent.CompletableFuture<PrePushValidationCoordinator.Outcome> result =
            new java.util.concurrent.CompletableFuture<>();
        new com.intellij.openapi.progress.Task.Backgroundable(project, "Pre-push: compilation check", true) {
            @Override
            public void run(@org.jetbrains.annotations.NotNull com.intellij.openapi.progress.ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Preparing compilation check…");
                PrePushValidationCoordinator.Outcome outcome = runCompile(request, indicator);
                result.complete(indicator.isCanceled() ? USER_CANCELED : outcome);
            }

            @Override
            public void onCancel() {
                result.complete(USER_CANCELED);
            }

            @Override
            public void onThrowable(@org.jetbrains.annotations.NotNull Throwable error) {
                result.complete(PrePushValidationCoordinator.Outcome.failure(
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            }
        }.queue();
        try {
            // Review runs in parallel and may wait for the large-push question, so allow for it.
            long reviewWait = REVIEW_BUDGET_SECONDS
                + com.github.prepushchecker.review.ReviewSettings.confirmTimeoutSeconds(project) + 30L;
            return result.get(Math.max(COMPILE_TIMEOUT_SECONDS, reviewWait) + 30L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PrePushValidationCoordinator.Outcome.canceled();
        } catch (java.util.concurrent.TimeoutException e) {
            return PrePushValidationCoordinator.Outcome.timeout();
        } catch (java.util.concurrent.ExecutionException e) {
            return PrePushValidationCoordinator.Outcome.failure(String.valueOf(e.getCause()));
        }
    }

    private PrePushValidationCoordinator.Outcome runCompile(
        ValidationRequest request,
        @org.jetbrains.annotations.Nullable com.intellij.openapi.progress.ProgressIndicator waiter
    ) {
        WriteSafeEdt.saveAllDocuments();

        List<String> normalizedPaths = restrictToProjectRoots(
            normalizeRequestedPaths(request.requestedPaths()));
        boolean projectScope = normalizedPaths.isEmpty()
            || normalizedPaths.stream().anyMatch(PushValidationPaths::isBuildFile);
        List<String> compilePaths = projectScope ? Collections.emptyList() : normalizedPaths;
        ValidationRequest normalizedRequest = new ValidationRequest(
            request.repositoryRoot().isBlank() ? normalizedProjectRoot() : request.repositoryRoot(),
            request.expectedHead(),
            request.updatesFingerprint(),
            normalizedPaths,
            request.rangeBase(),
            request.pushedHead());
        SnapshotToken before = captureSnapshot(normalizedRequest);
        String requestKey = buildRequestKey(normalizedRequest, projectScope, before);

        // The AI review runs next to the compile in its own progress task, not in front of it.
        ReviewRun reviewRun = startReview(normalizedRequest, waiter, requestKey);
        AtomicBoolean cacheHit = new AtomicBoolean();
        PrePushValidationCoordinator.Outcome outcome = PrePushValidationCoordinator.getInstance(project).request(
            requestKey,
            () -> {
                // Same content already compiled cleanly (smart build, user build, earlier push).
                if (!request.expectedHead().isBlank()
                        && !request.expectedHead().equals(before.actualHead())) {
                    return null;
                }
                // A clean IDE build says nothing about Ant layer order, so this runs first.
                if (PrePushCheckerSettings.isLayerCheckEnabled(project)) {
                    PrePushValidationCoordinator.ownerIndicator().setText("Checking layer rules…");
                }
                List<String> layerErrors = runLayerCheck(normalizedRequest);
                if (!layerErrors.isEmpty()) {
                    if (!snapshotMatches(normalizedRequest, before)) {
                        CompilationErrorService.getInstance(project).clearErrors();
                        throw new PrePushValidationCoordinator.StaleSnapshotException(
                            "repository changed during validation; retry push");
                    }
                    return layerErrors;
                }
                List<String> cached = SmartBuildService.getInstance(project).cachedCleanVerdict();
                if (cached != null) cacheHit.set(true);
                return cached;
            },
            () -> {
                if (!snapshotMatches(normalizedRequest, before)) {
                    throw new PrePushValidationCoordinator.StaleSnapshotException(
                        "repository changed before validation started; retry push");
                }
                PrePushValidationCoordinator.ownerIndicator().setText(compilePaths.isEmpty()
                    ? "Compiling the whole project…"
                    : "Compiling " + compilePaths.size() + " changed file(s)…");
                List<String> result = runCompileSingleFlight(compilePaths);
                if (!snapshotMatches(normalizedRequest, before)) {
                    CompilationErrorService.getInstance(project).clearErrors();
                    throw new PrePushValidationCoordinator.StaleSnapshotException(
                        "repository changed during validation; retry push");
                }
                return result;
            },
            waiter,
            COMPILE_TIMEOUT_SECONDS,
            TimeUnit.SECONDS);
        outcome = joinReview(reviewRun, outcome, normalizedRequest, before, waiter);
        if (outcome == USER_CANCELED) return outcome;
        recordStats(normalizedRequest, requestKey, outcome, cacheHit.get());
        return outcome;
    }

    /** The AI review of one push, running in parallel with the compile. */
    private static final class ReviewRun {
        final java.util.concurrent.CompletableFuture<List<String>> future = new java.util.concurrent.CompletableFuture<>();
        volatile ProgressIndicator indicator;
        volatile boolean canceled;
        /** Pushes waiting on this review; identical pushes share one run. */
        final AtomicInteger waiters = new AtomicInteger(1);

        /** One waiter leaves; the review stops only when the last one has gone. */
        void release() {
            if (waiters.decrementAndGet() <= 0 && !future.isDone()) cancel();
        }

        void cancel() {
            ProgressIndicator i = indicator;
            if (i != null && i.isRunning()) i.cancel();
            canceled = true;
            future.complete(Collections.emptyList());
        }
    }

    /** Running reviews by request key, so identical pushes share one review (and one dialog, one AI call). */
    private final Map<String, ReviewRun> activeReviews = new ConcurrentHashMap<>();

    private ReviewRun startReview(
        ValidationRequest request,
        @org.jetbrains.annotations.Nullable ProgressIndicator compileWaiter,
        String requestKey
    ) {
        if (request.repositoryRoot().isBlank() || request.requestedPaths().isEmpty()
                || !com.github.prepushchecker.review.ReviewSettings.isEnabled(project)
                || com.github.prepushchecker.review.ReviewService.getInstance(project)
                    .rules(Path.of(request.repositoryRoot())) == null) {
            ReviewRun none = new ReviewRun();
            none.future.complete(Collections.emptyList()); // review off or no rule file: no progress row
            return none;
        }
        ReviewRun run;
        synchronized (activeReviews) {
            ReviewRun existing = activeReviews.get(requestKey);
            if (existing != null && !existing.future.isDone()) {
                existing.waiters.incrementAndGet();
                return existing;
            }
            run = new ReviewRun();
            activeReviews.put(requestKey, run);
        }
        ReviewRun owned = run;
        owned.future.whenComplete((lines, error) -> activeReviews.remove(requestKey, owned));
        // Stopping the review from IntelliJ stops the whole push, compile included.
        if (compileWaiter != null) {
            run.future.thenRun(() -> {
                if (owned.canceled && compileWaiter.isRunning()) compileWaiter.cancel();
            });
        }
        if (ApplicationManager.getApplication().isHeadlessEnvironment()) {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                com.intellij.openapi.progress.util.ProgressIndicatorBase indicator =
                    new com.intellij.openapi.progress.util.ProgressIndicatorBase();
                run.indicator = indicator;
                try {
                    run.future.complete(runReviewGate(request, indicator));
                } catch (Throwable t) {
                    run.future.complete(Collections.emptyList());
                }
            });
            return run;
        }
        new com.intellij.openapi.progress.Task.Backgroundable(project, "Pre-push: AI review", true) {
            @Override
            public void run(@org.jetbrains.annotations.NotNull ProgressIndicator indicator) {
                run.indicator = indicator;
                indicator.setIndeterminate(true);
                indicator.setText("Checking which files need an AI review…");
                try {
                    List<String> lines = runReviewGate(request, indicator);
                    if (indicator.isCanceled()) run.canceled = true;
                    run.future.complete(lines);
                } catch (ProcessCanceledException e) {
                    run.canceled = true;
                    run.future.complete(Collections.emptyList());
                    throw e;
                }
            }

            @Override
            public void onCancel() {
                run.canceled = true;
                run.future.complete(Collections.emptyList());
            }

            @Override
            public void onThrowable(@org.jetbrains.annotations.NotNull Throwable error) {
                LOG.warn("AI review task failed; continuing with the compile result", error);
                run.future.complete(Collections.emptyList());
            }
        }.queue();
        return run;
    }

    /**
     * Waits for the review and merges its blocking lines into the compile outcome. Polls the compile
     * waiter so Stop on the compile row ends the wait at once, and re-checks the snapshot even when
     * the review found nothing (the checkout may have changed while both ran).
     */
    private PrePushValidationCoordinator.Outcome joinReview(
        ReviewRun run,
        PrePushValidationCoordinator.Outcome outcome,
        ValidationRequest request,
        SnapshotToken before,
        @org.jetbrains.annotations.Nullable ProgressIndicator waiter
    ) {
        if (outcome.status() != PrePushValidationCoordinator.Status.COMPLETED) {
            run.release(); // push is already decided
            return outcome;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REVIEW_BUDGET_SECONDS
            + com.github.prepushchecker.review.ReviewSettings.confirmTimeoutSeconds(project) + 30L);
        List<String> reviewLines = Collections.emptyList();
        try {
            while (true) {
                if (waiter != null && waiter.isCanceled()) {
                    run.release();
                    return USER_CANCELED;
                }
                if (run.canceled) return USER_CANCELED;
                try {
                    reviewLines = run.future.get(100L, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException waiting) {
                    if (System.nanoTime() >= deadline) {
                        run.release();
                        return PrePushValidationCoordinator.Outcome.timeout();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.release();
            return PrePushValidationCoordinator.Outcome.canceled();
        } catch (java.util.concurrent.ExecutionException e) {
            LOG.warn("AI review gate failed; continuing with the compile result", e.getCause());
        }
        run.release();
        if (run.canceled) return USER_CANCELED;
        if (!snapshotMatches(request, before)) {
            CompilationErrorService.getInstance(project).clearErrors();
            return PrePushValidationCoordinator.Outcome.stale("repository changed during validation; retry push");
        }
        if (reviewLines.isEmpty()) return outcome;
        List<String> combined = new ArrayList<>(reviewLines);
        combined.addAll(outcome.errors());
        publishErrors(combined);
        return PrePushValidationCoordinator.Outcome.completed(combined);
    }

    /** Counts this verdict in {@link PushStats}. A failure here must never affect the push. */
    private void recordStats(
        ValidationRequest request,
        String requestKey,
        PrePushValidationCoordinator.Outcome outcome,
        boolean cacheHit
    ) {
        try {
            PushStats.Result result = switch (outcome.status()) {
                case TIMEOUT -> PushStats.Result.TIMEOUT;
                case STALE -> PushStats.Result.STALE;
                case COMPLETED -> outcome.errors().isEmpty() ? PushStats.Result.ALLOWED : PushStats.Result.BLOCKED;
                default -> PushStats.Result.INFRASTRUCTURE;
            };
            PushStats.getInstance().record(
                valueOrEmpty(project.getBasePath()), project.getName(), result, outcome.errors(),
                requestKey, cacheHit, currentBranch(request.repositoryRoot()), PushStats.Source.IDE);
        } catch (RuntimeException e) {
            LOG.debug("Could not record push stats", e);
        }
    }

    private String currentBranch(String repositoryRoot) {
        for (git4idea.repo.GitRepository repository
                : git4idea.repo.GitRepositoryManager.getInstance(project).getRepositories()) {
            if (normalizeRoot(repository.getRoot().getPath()).equals(repositoryRoot)) {
                return valueOrEmpty(repository.getCurrentBranchName());
            }
        }
        return "";
    }

    /**
     * AI review gate for the pushed files: blocking lines, or empty to go on. An on-push review
     * may run here; it gets {@link #REVIEW_BUDGET_SECONDS} so the compile still has time.
     */
    private List<String> runReviewGate(ValidationRequest request, ProgressIndicator indicator) {
        if (request.repositoryRoot().isBlank() || request.requestedPaths().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<Path> paths = new ArrayList<>(request.requestedPaths().size());
            for (String p : request.requestedPaths()) paths.add(Path.of(p));
            List<String> lines = com.github.prepushchecker.review.ReviewService.getInstance(project).checkPush(
                Path.of(request.repositoryRoot()), paths,
                request.rangeBase().isBlank() ? null : request.rangeBase(),
                request.pushedHead().isBlank() ? null : request.pushedHead(),
                // The "Pre-push: AI review" task's own indicator: Stop cancels it, its text is the progress.
                indicator,
                java.time.Duration.ofSeconds(REVIEW_BUDGET_SECONDS));
            return lines;
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("AI review gate failed; continuing with the compile check", e);
            return Collections.emptyList();
        }
    }

    /** Cross-layer import violations for the pushed snapshot; empty when disabled or clean. */
    private List<String> runLayerCheck(ValidationRequest request) {
        if (!PrePushCheckerSettings.isLayerCheckEnabled(project)
                || !CrossLayerImportCheck.hasRulesFile(request.repositoryRoot())) {
            return Collections.emptyList();
        }
        List<String> raw = CrossLayerImportCheck.detect(
            project, request.repositoryRoot(), request.requestedPaths());
        List<String> blocking = DiagnosticPathMatcher.filterIgnored(project, raw);
        if (blocking.isEmpty()) return Collections.emptyList();
        publishErrors(raw);
        return blocking;
    }

    /** Drops requested paths that are not inside the project or one of its git roots (defense in depth). */
    private List<String> restrictToProjectRoots(List<String> paths) {
        List<String> roots = new ArrayList<>();
        roots.add(normalizedProjectRoot());
        for (git4idea.repo.GitRepository repository
                : git4idea.repo.GitRepositoryManager.getInstance(project).getRepositories()) {
            roots.add(normalizeRoot(repository.getRoot().getPath()));
        }
        List<String> kept = new ArrayList<>(paths.size());
        for (String path : paths) {
            for (String root : roots) {
                if (!root.isEmpty() && (path.equals(root) || path.startsWith(root + "/"))) {
                    kept.add(path);
                    break;
                }
            }
        }
        if (kept.size() != paths.size()) {
            LOG.warn("Ignored " + (paths.size() - kept.size())
                + " pre-push path(s) outside the project roots.");
        }
        return kept;
    }

    private String buildRequestKey(
        ValidationRequest request,
        boolean projectScope,
        SnapshotToken snapshot
    ) {
        StringBuilder key = new StringBuilder("hook-v2\n");
        key.append("scope=").append(projectScope ? "project" : "files").append('\n');
        key.append("root=").append(request.repositoryRoot()).append('\n');
        key.append("head=").append(request.expectedHead()).append('\n');
        key.append("updates=").append(request.updatesFingerprint()).append('\n');
        key.append("ignored=")
            .append(new TreeSet<>(IgnoredCompilationFiles.getInstance(project).enabledPaths()))
            .append('\n');
        key.append("layers=").append(layerRulesStamp(request.repositoryRoot())).append('\n');
        key.append("review=").append(reviewStamp(request.repositoryRoot())).append('\n');
        key.append(snapshot.fingerprint());
        return key.toString();
    }

    private String reviewStamp(String repositoryRoot) {
        if (repositoryRoot.isBlank()) return "none";
        try {
            return com.github.prepushchecker.review.ReviewService.getInstance(project)
                .gateStamp(Path.of(repositoryRoot));
        } catch (RuntimeException e) {
            return "unreadable";
        }
    }

    private String layerRulesStamp(String repositoryRoot) {
        if (!PrePushCheckerSettings.isLayerCheckEnabled(project)) return "off";
        if (repositoryRoot.isBlank()) return "none";
        try {
            Path rules = Path.of(repositoryRoot, LayerRules.FILE_NAME);
            if (!Files.isRegularFile(rules)) return "none";
            return Files.getLastModifiedTime(rules).toMillis() + ":" + Files.size(rules);
        } catch (IOException | RuntimeException ignored) {
            return "unreadable";
        }
    }

    private String normalizedProjectRoot() {
        return normalizeRoot(project.getBasePath());
    }

    private static String normalizeRoot(String root) {
        if (root == null || root.isBlank()) return "";
        try {
            return Path.of(root).toAbsolutePath().normalize().toString().replace('\\', '/');
        } catch (RuntimeException ignored) {
            return root.replace('\\', '/');
        }
    }

    private static SnapshotToken captureSnapshot(ValidationRequest request) {
        String actualHead = request.repositoryRoot().isBlank()
            ? ""
            : valueOrEmpty(GitOperations.headSha(request.repositoryRoot()));
        StringBuilder fingerprint = new StringBuilder()
            .append("actualHead=").append(actualHead).append('\n');
        for (String path : request.requestedPaths()) {
            fingerprint.append("path=").append(path);
            try {
                Path file = Path.of(path);
                fingerprint.append('@')
                    .append(Files.getLastModifiedTime(file).toMillis())
                    .append(':')
                    .append(Files.size(file));
            } catch (IOException | RuntimeException ignored) {
                fingerprint.append("@missing");
            }
            fingerprint.append('\n');
        }
        return new SnapshotToken(actualHead, fingerprint.toString());
    }

    private static boolean snapshotMatches(
        ValidationRequest request,
        SnapshotToken before
    ) {
        SnapshotToken now = captureSnapshot(request);
        if (!request.expectedHead().isBlank()
                && !request.expectedHead().equals(now.actualHead())) {
            return false;
        }
        return before.equals(now);
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static List<String> normalizeRequestedPaths(List<String> requestedPaths) {
        TreeSet<String> normalized = new TreeSet<>();
        for (String path : requestedPaths) {
            if (path == null || path.isBlank()) continue;
            try {
                normalized.add(Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/'));
            } catch (RuntimeException ignored) {
                normalized.add(path.replace('\\', '/'));
            }
        }
        return List.copyOf(normalized);
    }

    private List<String> runCompileSingleFlight(List<String> requestedPaths) throws TimeoutException {
        long startNanos = System.nanoTime();
        ProgressIndicator owner = PrePushValidationCoordinator.ownerIndicator();
        if (PrePushCheckerSettings.isStrictSnapshotGuardEnabled(project)) {
            owner.setText("Checking dependencies against a clean snapshot of HEAD…");
        }
        PrePushSnapshotGuard.SnapshotValidationResult strictSnapshot =
            PrePushSnapshotGuard.validateHeadSnapshotIfNeeded(project, requestedPaths, owner);
        if (strictSnapshot.wasChecked()) {
            List<String> rawSnapshotErrors = strictSnapshot.errors();
            List<String> snapshotErrors = DiagnosticPathMatcher.filterIgnored(project, rawSnapshotErrors);
            publishErrors(rawSnapshotErrors);
            return snapshotErrors;
        }

        // One budget for the whole request: whatever the strict guard used is gone.
        long remainingSeconds = COMPILE_TIMEOUT_SECONDS
            - TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos);
        if (remainingSeconds < MIN_COMPILE_SECONDS) {
            throw new TimeoutException("Not enough time left to compile after the snapshot check.");
        }

        // Only the save needs the EDT; an open dialog must not stall a push (see WriteSafeEdt).
        if (!project.isDisposed()) WriteSafeEdt.saveAllDocuments();
        if (project.isDisposed()) {
            throw new IllegalStateException("project disposed");
        }
        owner.checkCanceled();

        // VFS refresh and scope building run here, on the validation thread, not the EDT.
        List<VirtualFile> files = resolveFiles(requestedPaths);
        if (!files.isEmpty()) {
            LocalFileSystem.getInstance().refreshFiles(files);
        }
        CompilerManager compiler = CompilerManager.getInstance(project);
        CompileScope scope = files.isEmpty()
            ? ReadActions.compute(() -> compiler.createProjectCompileScope(project))
            : CompilationSupport.buildPushScope(project, files, compiler);
        return IdeCompilationRunner.runWithRecovery(
            project,
            owner,
            compiler,
            notification -> compiler.make(scope, notification),
            remainingSeconds,
            TimeUnit.SECONDS);
    }

    private void publishErrors(List<String> errors) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                CompilationErrorService.getInstance(project).setErrors(errors);
            }
        }, ModalityState.any());
    }

    private static List<VirtualFile> resolveFiles(List<String> paths) {
        if (paths.isEmpty()) return Collections.emptyList();
        LocalFileSystem lfs = LocalFileSystem.getInstance();
        LinkedHashSet<String> uniquePaths = new LinkedHashSet<>(paths.size());
        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            uniquePaths.add(p.replace('\\', '/'));
        }
        List<VirtualFile> out = new ArrayList<>(uniquePaths.size());
        for (String normalized : uniquePaths) {
            VirtualFile vf = lfs.findFileByPath(normalized);
            if (vf == null) vf = lfs.refreshAndFindFileByPath(normalized);
            if (vf != null && !vf.isDirectory()) {
                String name = vf.getName();
                // Only compilable sources - resources/build files would fail compile().
                if (name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".kts")
                        || name.endsWith(".groovy") || name.endsWith(".scala")) {
                    out.add(vf);
                }
            }
        }
        return out;
    }

    private record ValidationRequest(
        @NotNull String repositoryRoot,
        @NotNull String expectedHead,
        @NotNull String updatesFingerprint,
        @NotNull List<String> requestedPaths,
        @NotNull String rangeBase,
        @NotNull String pushedHead
    ) {
        private ValidationRequest {
            requestedPaths = List.copyOf(requestedPaths);
        }
    }

    private record SnapshotToken(
        @NotNull String actualHead,
        @NotNull String fingerprint
    ) {
    }

    @Override
    public void dispose() {
        started.set(false);
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
        clientExecutor.shutdownNow();
        Thread t = acceptThread;
        if (t != null) t.interrupt();
        for (Path descriptor : portFiles) {
            try { Files.deleteIfExists(descriptor); } catch (IOException ignored) {}
        }
        portFiles.clear();
    }

    /** Starts the per-project server on project open. Multiple startup entrypoints can safely call this. */
    public static void runStartup(@NotNull Project project) {
        PrePushCheckerSettings.syncSettingsFile(project);
        PrePushLocalServer srv = project.getService(PrePushLocalServer.class);
        if (srv == null) {
            LOG.warn("PrePushLocalServer project service is unavailable; external hook will fall back to build tool.");
            return;
        }
        srv.start();
    }
}
