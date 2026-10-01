package com.github.prepushchecker;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
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
import java.util.concurrent.atomic.AtomicReference;

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
        Path tmp = portFile.resolveSibling(portFile.getFileName().toString() + ".tmp");
        Files.deleteIfExists(tmp);
        try {
            // Create owner-only up front so the token is never briefly world-readable.
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException nonPosix) {
            Files.createFile(tmp);
        }
        Files.writeString(tmp, port + "\n" + authToken + "\n", StandardCharsets.UTF_8);
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
            Files.createDirectories(descriptor.getParent());
            writePortFileAtomically(descriptor, activeServer.getLocalPort());
            portFiles.add(descriptor);
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
                pathLimitExceeded ? Collections.emptyList() : requestedPaths);
            PrePushValidationCoordinator.Outcome outcome = runCompile(request);
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

    private PrePushValidationCoordinator.Outcome runCompile(ValidationRequest request) {
        ApplicationManager.getApplication().invokeAndWait(
            () -> FileDocumentManager.getInstance().saveAllDocuments(),
            ModalityState.defaultModalityState());

        List<String> normalizedPaths = restrictToProjectRoots(
            normalizeRequestedPaths(request.requestedPaths()));
        boolean projectScope = normalizedPaths.isEmpty()
            || normalizedPaths.stream().anyMatch(PushValidationPaths::isBuildFile);
        List<String> compilePaths = projectScope ? Collections.emptyList() : normalizedPaths;
        ValidationRequest normalizedRequest = new ValidationRequest(
            request.repositoryRoot().isBlank() ? normalizedProjectRoot() : request.repositoryRoot(),
            request.expectedHead(),
            request.updatesFingerprint(),
            normalizedPaths);
        SnapshotToken before = captureSnapshot(normalizedRequest);
        String requestKey = buildRequestKey(normalizedRequest, projectScope, before);

        return PrePushValidationCoordinator.getInstance(project).request(
            requestKey,
            () -> null,
            () -> {
                if (!snapshotMatches(normalizedRequest, before)) {
                    throw new PrePushValidationCoordinator.StaleSnapshotException(
                        "repository changed before validation started; retry push");
                }
                List<String> result = runCompileSingleFlight(compilePaths);
                if (!snapshotMatches(normalizedRequest, before)) {
                    CompilationErrorService.getInstance(project).clearErrors();
                    throw new PrePushValidationCoordinator.StaleSnapshotException(
                        "repository changed during validation; retry push");
                }
                return result;
            },
            null,
            COMPILE_TIMEOUT_SECONDS,
            TimeUnit.SECONDS);
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
        key.append(snapshot.fingerprint());
        return key.toString();
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
        PrePushSnapshotGuard.SnapshotValidationResult strictSnapshot =
            PrePushSnapshotGuard.validateHeadSnapshotIfNeeded(project, requestedPaths, null);
        if (strictSnapshot.wasChecked()) {
            List<String> rawSnapshotErrors = strictSnapshot.errors();
            List<String> snapshotErrors = DiagnosticPathMatcher.filterIgnored(project, rawSnapshotErrors);
            ApplicationManager.getApplication().invokeAndWait(() -> {
                if (!project.isDisposed()) {
                    CompilationErrorService.getInstance(project).setErrors(rawSnapshotErrors);
                }
            }, ModalityState.defaultModalityState());
            return snapshotErrors;
        }

        AtomicReference<CompileScope> scopeRef = new AtomicReference<>();
        AtomicReference<CompilerManager> compilerRef = new AtomicReference<>();
        ApplicationManager.getApplication().invokeAndWait(() -> {
            if (project.isDisposed()) {
                throw new IllegalStateException("project disposed");
            }
            FileDocumentManager.getInstance().saveAllDocuments();
            CompilerManager compiler = CompilerManager.getInstance(project);
            List<VirtualFile> files = resolveFiles(requestedPaths);
            if (!files.isEmpty()) {
                LocalFileSystem.getInstance().refreshFiles(files);
            }
            CompileScope scope = files.isEmpty()
                ? compiler.createProjectCompileScope(project)
                : CompilationSupport.buildPushScope(project, files, compiler);
            compilerRef.set(compiler);
            scopeRef.set(scope);
        }, ModalityState.defaultModalityState());

        CompilerManager compiler = compilerRef.get();
        CompileScope scope = scopeRef.get();
        return IdeCompilationRunner.runWithRecovery(
            project,
            new EmptyProgressIndicator(),
            compiler,
            notification -> compiler.make(scope, notification),
            COMPILE_TIMEOUT_SECONDS,
            TimeUnit.SECONDS);
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
        @NotNull List<String> requestedPaths
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
