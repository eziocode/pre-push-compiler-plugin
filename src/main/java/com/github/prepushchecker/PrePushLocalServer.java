package com.github.prepushchecker;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.compiler.CompileStatusNotification;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-project TCP-loopback server that lets external git clients (terminal, Sublime Merge, …)
 * reuse the IDE's incremental JPS compiler instead of spawning a full Gradle/Maven build.
 *
 * <p>Protocol (line-delimited, UTF-8):
 * <pre>
 *   C -> S : CHECK\n
 *   S -> C : OK\n                              (compile succeeded)
 *           | ERRORS &lt;n&gt;\n&lt;line&gt;...\nEND\n    (n errors follow)
 *           | ERR &lt;reason&gt;\n                   (server could not run the check)
 * </pre>
 *
 * <p>The server binds to {@code 127.0.0.1} on an ephemeral port and writes the port to
 * {@link #PORT_FILE_RELATIVE}. When the IDE is closed the port file is removed and the hook
 * silently falls back to the build-tool path.
 */
public final class PrePushLocalServer implements Disposable {
    static final String PORT_FILE_RELATIVE = ".idea/pre-push-checker/server.port";
    private static final Logger LOG = Logger.getInstance(PrePushLocalServer.class);
    private static final long COMPILE_TIMEOUT_SECONDS = 300L;
    private static final int BACKLOG = 4;
    private static final int MAX_CLIENT_THREADS = 4;
    private static final int MAX_REQUESTED_PATHS = 2_048;
    private static final int CLIENT_SO_TIMEOUT_MS = 5 * 60 * 1000;

    private final Project project;
    private final ExecutorService clientExecutor;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile ServerSocket server;
    private volatile Thread acceptThread;
    private volatile Path portFile;

    public PrePushLocalServer(@NotNull Project project) {
        this.project = project;
        this.clientExecutor = new ThreadPoolExecutor(
            0,
            MAX_CLIENT_THREADS,
            30L,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            clientThreadFactory(project.getName()),
            new ThreadPoolExecutor.AbortPolicy());
    }

    void start() {
        if (project.isDisposed() || !started.compareAndSet(false, true)) return;
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) return;

        try {
            ServerSocket s = new ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress());
            server = s;
            portFile = Path.of(basePath, PORT_FILE_RELATIVE);
            Files.createDirectories(portFile.getParent());
            Files.writeString(portFile, s.getLocalPort() + "\n", StandardCharsets.UTF_8);

            Thread t = new Thread(this::acceptLoop, "PrePushChecker-Server-" + project.getName());
            t.setDaemon(true);
            t.start();
            acceptThread = t;

            LOG.info("PrePushLocalServer listening on 127.0.0.1:" + s.getLocalPort()
                + " for project '" + project.getName() + "'");
        } catch (IOException ex) {
            LOG.warn("Could not start local pre-push server; external hook will fall back to build tool.", ex);
            started.set(false);
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
                    LOG.warn("Pre-push client rejected because all workers are busy.", rejected);
                    writeServerBusy(socket);
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

    private void writeServerBusy(Socket socket) {
        try (Socket c = socket;
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8))) {
            out.write("ERR server-busy\n");
            out.flush();
        } catch (IOException e) {
            LOG.debug("Could not notify pre-push client that the server is busy", e);
        }
    }

    private void handleClient(Socket socket) {
        try (Socket c = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8))) {

            String line = in.readLine();
            if (line == null || !line.startsWith("CHECK")) {
                out.write("ERR unknown-request\n");
                out.flush();
                return;
            }
            // Read optional list of absolute file paths (one per line) until a blank line or EOF.
            List<String> requestedPaths = new ArrayList<>();
            boolean pathLimitExceeded = false;
            while ((line = in.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) break;
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

            if (project.isDisposed()) {
                out.write("ERR project-disposed\n");
                out.flush();
                return;
            }

            List<String> errors = runCompile(pathLimitExceeded ? Collections.emptyList() : requestedPaths);
            if (errors == null) {
                out.write("ERR compile-timeout\n");
            } else if (errors.isEmpty()) {
                out.write("OK\n");
            } else {
                out.write("ERRORS " + errors.size() + "\n");
                for (String e : errors) {
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

    private List<String> runCompile(List<String> requestedPaths) {
        PrePushSnapshotGuard.SnapshotValidationResult strictSnapshot =
            PrePushSnapshotGuard.validateHeadSnapshotIfNeeded(project, requestedPaths, null);
        if (strictSnapshot.wasChecked()) {
            List<String> snapshotErrors = strictSnapshot.errors();
            ApplicationManager.getApplication().invokeAndWait(() -> {
                if (!project.isDisposed()) {
                    CompilationErrorService.getInstance(project).setErrors(snapshotErrors);
                }
            }, ModalityState.defaultModalityState());
            return snapshotErrors;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<String>> errorsRef = new AtomicReference<>(Collections.emptyList());
        AtomicBoolean fatal = new AtomicBoolean(false);

        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                fatal.set(true);
                latch.countDown();
                return;
            }
            try {
                // Narrow, targeted refresh: only the files the hook is actually asking about.
                // Avoids the (potentially slow) project-wide VirtualFileManager.syncRefresh().
                FileDocumentManager.getInstance().saveAllDocuments();

                CompilerManager cm = CompilerManager.getInstance(project);
                List<VirtualFile> files = resolveFiles(requestedPaths);
                if (!files.isEmpty()) {
                    LocalFileSystem.getInstance().refreshFiles(files);
                }

                // Reuse a recent successful compile verdict when nothing has moved on disk. Lets
                // external pushes piggyback on a just-completed manual check or an
                // earlier push check without re-running javac. Cached failures are deliberately
                // rechecked: IntelliJ's problem cache and narrow warmup compiles can hold stale
                // generated-symbol errors (Lombok getters/setters/builders) until a real compile
                // clears them.
                CompilationErrorService svc = CompilationErrorService.getInstance(project);
                if (!files.isEmpty()) {
                    List<String> cached = svc.tryReuse(files);
                    if (cached != null && cached.isEmpty()) {
                        errorsRef.set(cached);
                        latch.countDown();
                        return;
                    }
                }

                final List<VirtualFile> recordedFiles = files;
                class RetryingCompileCallback implements CompileStatusNotification {
                    private boolean projectScope;

                    private RetryingCompileCallback(boolean projectScope) {
                        this.projectScope = projectScope;
                    }

                    @Override
                    public void finished(boolean aborted, int errorCount, int warningCount, CompileContext ctx) {
                        boolean retryingAsProject = false;
                        try {
                            if (shouldRetryProjectScopeAfterScopedFailure(projectScope, aborted, errorCount)) {
                                retryingAsProject = true;
                                projectScope = true;
                                LOG.info("External pre-push scoped compile reported errors; retrying project compile before reporting them.");
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    try {
                                        if (project.isDisposed()) {
                                            fatal.set(true);
                                            latch.countDown();
                                            return;
                                        }
                                        cm.make(cm.createProjectCompileScope(project), this);
                                    } catch (Throwable t) {
                                        LOG.warn("CompilerManager project retry failed", t);
                                        fatal.set(true);
                                        latch.countDown();
                                    }
                                }, ModalityState.defaultModalityState());
                                return;
                            }

                            List<String> result;
                            if (aborted) {
                                result = Collections.singletonList("Compilation was aborted.");
                            } else if (errorCount > 0) {
                                result = PrePushCompilationHandler.formatCompilerMessages(
                                    project, ctx.getMessages(CompilerMessageCategory.ERROR));
                            } else {
                                result = Collections.emptyList();
                            }
                            errorsRef.set(result);
                            if (!aborted) {
                                svc.recordCompletion(
                                    projectScope,
                                    projectScope
                                        ? Collections.emptyMap()
                                        : CompilationErrorService.snapshotStamps(recordedFiles),
                                    result);
                            }
                        } finally {
                            if (!retryingAsProject) {
                                latch.countDown();
                            }
                        }
                    }
                }

                CompileStatusNotification callback = new RetryingCompileCallback(files.isEmpty());

                if (!files.isEmpty()) {
                    // Same adaptive scope as the in-IDE push path: include dependent modules so
                    // JPS cannot miss A-depends-on-B breakage, but keep it incremental.
                    CompileScope scope = PrePushCompilationHandler.buildPushScopeForExternal(project, files, cm);
                    cm.make(scope, callback);
                } else {
                    cm.make(cm.createProjectCompileScope(project), callback);
                }
            } catch (Throwable t) {
                LOG.warn("CompilerManager compile/make failed", t);
                fatal.set(true);
                latch.countDown();
            }
        }, ModalityState.defaultModalityState());

        try {
            if (!latch.await(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        return fatal.get() ? null : errorsRef.get();
    }

    static boolean shouldRetryProjectScopeAfterScopedFailure(boolean projectScope, boolean aborted, int errorCount) {
        return !projectScope && !aborted && errorCount > 0;
    }

    private static List<VirtualFile> resolveFiles(List<String> paths) {
        if (paths.isEmpty()) return Collections.emptyList();
        LocalFileSystem lfs = LocalFileSystem.getInstance();
        List<VirtualFile> out = new ArrayList<>(paths.size());
        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            String normalized = p.replace('\\', '/');
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
        Path p = portFile;
        if (p != null) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        }
    }

    /** Starts the per-project server on project open. Invoked by the Kotlin {@code ProjectActivity} bridge. */
    public static void runStartup(@NotNull Project project) {
        PrePushCheckerSettings.syncSettingsFile(project);
        PrePushLocalServer srv = new PrePushLocalServer(project);
        srv.start();
        com.intellij.openapi.util.Disposer.register(project, srv);
    }
}
