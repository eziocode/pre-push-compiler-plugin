package com.github.prepushchecker.commitgen;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.prepushchecker.ProcessExecution;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import java.util.regex.Pattern;

/**
 * Resolves CLI tool paths and environment variables as the user's shell sees
 * them — by sourcing rc files so that Homebrew, npm-global, asdf shims, etc.
 * are found even when IntelliJ was launched as a GUI app with a stripped PATH.
 */
public final class CliPathResolver {

    /**
     * Allowlist for bare executable names passed to {@code which} inside a
     * {@code sh -c} script. Only alphanumerics, dots, hyphens and underscores
     * are permitted — this prevents shell injection via user-controlled
     * settings values (e.g. a compromised {@code commitMessageGenerator.xml}).
     */
    private static final Pattern SAFE_BARE_NAME = Pattern.compile("[A-Za-z0-9._+-]+");

    private static final Object SHELL_PATH_LOCK = new Object();
    /** {@code null} = not computed yet, {@code ""} = computed, unavailable. */
    private static volatile String shellPathCache;

    private static final List<String> RC_FILES = List.of(
        ".zshenv", ".zprofile", ".zshrc",
        ".bash_profile", ".bashrc", ".profile"
    );

    private static final List<String> EXTRA_PATH_DIRS = buildExtraPathDirs();

    private static List<String> buildExtraPathDirs() {
        String home = System.getProperty("user.home");
        List<String> dirs = new ArrayList<>(List.of(
            "/opt/homebrew/bin",
            "/usr/local/bin",
            "/opt/local/bin",
            "/usr/bin",
            "/bin",
            home + "/.local/bin",
            home + "/.npm-global/bin",
            home + "/.claude/local",
            home + "/.bun/bin",
            home + "/.volta/bin",
            home + "/.yarn/bin",
            home + "/Library/pnpm",
            home + "/.local/share/pnpm",
            home + "/.cargo/bin",
            home + "/.asdf/shims",
            home + "/go/bin",
            "/usr/local/npm/bin",
            "/usr/local/share/npm/bin"
        ));
        // nvm installs every Node version under its own bin dir; npm-installed
        // CLIs (claude, codex, gemini, …) land there. Newest version first.
        Path nvm = Path.of(home, ".nvm", "versions", "node");
        if (Files.isDirectory(nvm)) {
            try (var stream = Files.list(nvm)) {
                stream.map(v -> v.resolve("bin"))
                    .filter(Files::isDirectory)
                    .map(Path::toString)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(dirs::add);
            } catch (Exception ignored) {}
        }
        return List.copyOf(dirs);
    }

    private CliPathResolver() {}

    /**
     * Returns {@code true} when {@code name} consists only of characters that
     * are safe to embed in a {@code sh -c} script without quoting.
     * Rejects empty strings and anything containing shell meta-characters.
     */
    static boolean isSafeBareNameForShell(@NotNull String name) {
        return !name.isBlank() && SAFE_BARE_NAME.matcher(name).matches();
    }

    /** Resolves the absolute path to {@code cliName}, or returns the bare name. */
    @NotNull
    public static String resolve(@Nullable String configured, @NotNull String cliName) {
        String found = resolveOrNull(configured, cliName);
        return found != null ? found : cliName;
    }

    /**
     * Resolves the absolute path to {@code cliName}, or {@code null} when it
     * cannot be found. Order: configured absolute path → configured bare name
     * via the user's shell → {@code cliName} via the user's shell → well-known
     * install directories.
     */
    @Nullable
    public static String resolveOrNull(@Nullable String configured, @NotNull String cliName) {
        String key = (configured == null ? "" : configured.trim()) + "\u0000" + cliName;
        Resolved hit = RESOLVE_CACHE.get(key);
        long now = System.currentTimeMillis();
        if (hit != null) {
            if (hit.path != null && isExecutable(hit.path)) return hit.path;
            if (hit.path == null && now - hit.at < MISS_TTL_MS) return null;
            RESOLVE_CACHE.remove(key, hit);
        }
        String found = resolveUncached(configured, cliName);
        RESOLVE_CACHE.put(key, new Resolved(found, now));
        return found;
    }

    /** A resolved path ({@code null} = not found) and when it was looked up. */
    private record Resolved(@Nullable String path, long at) {}

    /** Not-found results are retried after this long; found paths stay until invalidated. */
    static final long MISS_TTL_MS = 60_000;
    private static final java.util.concurrent.ConcurrentHashMap<String, Resolved> RESOLVE_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Forgets resolved CLI paths. Called when AI settings are applied and by "Detect Local CLIs",
     * so a newly installed or moved CLI is found again.
     */
    public static void clearResolveCache() {
        RESOLVE_CACHE.clear();
    }

    /** Number of cached lookups; for tests. */
    static int resolveCacheSize() {
        return RESOLVE_CACHE.size();
    }

    private static boolean isExecutable(@NotNull String path) {
        try {
            return Files.isExecutable(Path.of(path));
        } catch (Exception e) {
            return false;
        }
    }

    @Nullable
    private static String resolveUncached(@Nullable String configured, @NotNull String cliName) {
        if (com.intellij.openapi.util.SystemInfo.isWindows) return resolveOnWindows(configured, cliName);
        if (configured != null && !configured.isBlank()) {
            String trimmed = configured.trim();
            Path p = Path.of(trimmed);
            // Absolute path: use as-is if executable (no shell involved)
            if (p.isAbsolute() && Files.isExecutable(p)) return p.toString();
            // Bare name override: only pass to shell if it contains no shell-special chars
            if (isSafeBareNameForShell(trimmed)) {
                String shellResult = whichViaShell(trimmed);
                if (shellResult != null) return shellResult;
            }
        }
        String shellResult = whichViaShell(cliName);
        if (shellResult != null) return shellResult;
        return findInExtraDirs(cliName);
    }

    /**
     * Windows has no {@code sh}/{@code which}: a configured path, then the IDE's PATH (with the
     * {@code .exe}/{@code .cmd}/{@code .bat} forms npm and pip install).
     */
    @Nullable
    private static String resolveOnWindows(@Nullable String configured, @NotNull String cliName) {
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.trim());
            if (p.isAbsolute() && Files.isRegularFile(p)) return p.toString();
            if (isSafeBareNameForShell(configured.trim())) {
                String found = findOnWindowsPath(configured.trim());
                if (found != null) return found;
            }
        }
        return isSafeBareNameForShell(cliName) ? findOnWindowsPath(cliName) : null;
    }

    @Nullable
    private static String findOnWindowsPath(@NotNull String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String candidate : List.of(name + ".exe", name + ".cmd", name + ".bat", name)) {
                try {
                    Path p = Path.of(dir.trim(), candidate);
                    if (Files.isRegularFile(p)) return p.toString();
                } catch (Exception ignored) {
                    // malformed PATH entry
                }
            }
        }
        return null;
    }

    /** Looks for {@code cliName} in the well-known install directories only. */
    @Nullable
    static String findInExtraDirs(@NotNull String cliName) {
        if (!isSafeBareNameForShell(cliName)) return null;
        for (String dir : EXTRA_PATH_DIRS) {
            Path candidate = Path.of(dir, cliName);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return null;
    }

    /** Returns {@code true} when the CLI can be found and is executable. */
    public static boolean isAvailable(@Nullable String configured, @NotNull String cliName) {
        String resolved = resolve(configured, cliName);
        if (resolved.equals(cliName)) return runQuick(cliName, "--version") != null;
        return Files.isExecutable(Path.of(resolved));
    }

    /**
     * Injects the shell-resolved PATH into {@code pb.environment()} so that
     * scripts with shebangs like {@code #!/usr/bin/env node} work even from
     * a GUI-launched IntelliJ with a stripped PATH.
     */
    public static void injectAugmentedPath(@NotNull ProcessBuilder pb) {
        String resolved = resolveShellPath();
        if (resolved != null && !resolved.isBlank()) {
            pb.environment().put("PATH", resolved);
        }
    }

    /**
     * Reads an environment variable as the user's shell sees it — after
     * sourcing rc files. Picks up {@code OPENAI_API_KEY}, {@code ANTHROPIC_API_KEY},
     * etc. that are exported in {@code .zshrc} / {@code .bashrc}.
     *
     * @param varName the env var name (e.g. {@code "OPENAI_API_KEY"})
     * @return trimmed value, or {@code null} if unset or empty
     */
    @Nullable
    public static String resolveEnvVar(@NotNull String varName) {
        String home = System.getProperty("user.home");
        StringBuilder script = new StringBuilder();
        for (String rc : RC_FILES) {
            Path rcFile = Path.of(home, rc);
            if (Files.isRegularFile(rcFile)) {
                script.append(". \"").append(rcFile).append("\" 2>/dev/null || true\n");
            }
        }
        script.append("printf '%s' \"$").append(varName).append("\"");
        return runForOutput(List.of("sh", "-c", script.toString()), 5, false);
    }

    /**
     * Returns the full PATH string from the user's shell, or {@code null}.
     * Computed once per IDE session: sourcing rc files can take seconds and
     * every CLI / git call needs it.
     */
    @Nullable
    public static String resolveShellPath() {
        String cached = shellPathCache;
        if (cached != null) return cached.isEmpty() ? null : cached;
        synchronized (SHELL_PATH_LOCK) {
            if (shellPathCache == null) {
                String computed = computeShellPath();
                shellPathCache = computed == null ? "" : computed;
            }
            return shellPathCache.isEmpty() ? null : shellPathCache;
        }
    }

    /** Forgets the cached shell PATH (e.g. after the user edits their rc files). */
    public static void clearShellPathCache() {
        synchronized (SHELL_PATH_LOCK) {
            shellPathCache = null;
        }
    }

    @Nullable
    private static String computeShellPath() {
        String home = System.getProperty("user.home");
        StringBuilder script = new StringBuilder();
        for (String rc : RC_FILES) {
            Path rcFile = Path.of(home, rc);
            if (Files.isRegularFile(rcFile)) {
                script.append(". \"").append(rcFile).append("\" 2>/dev/null || true\n");
            }
        }
        script.append("for _d in");
        for (String dir : EXTRA_PATH_DIRS) {
            script.append(" \"").append(dir).append("\"");
        }
        script.append("; do\n");
        script.append("  case \":$PATH:\" in *\":$_d:\"*) :;; *) PATH=\"$_d:$PATH\";; esac\n");
        script.append("done\n");
        script.append("printf '%s' \"$PATH\"");
        return runForOutput(List.of("sh", "-c", script.toString()), 8, false);
    }

    /** Runs {@code which <name>} in a sub-shell sourcing rc files. */
    @Nullable
    public static String whichViaShell(@NotNull String name) {
        // SECURITY: reject anything that is not a safe bare executable name
        // before constructing the sh -c script to prevent shell injection
        // via user-controlled Settings values.
        if (!isSafeBareNameForShell(name)) return null;
        String home = System.getProperty("user.home");
        StringBuilder script = new StringBuilder();
        for (String rc : RC_FILES) {
            Path rcFile = Path.of(home, rc);
            if (Files.isRegularFile(rcFile)) {
                script.append(". \"").append(rcFile).append("\" 2>/dev/null || true\n");
            }
        }
        script.append("for _d in");
        for (String dir : EXTRA_PATH_DIRS) {
            script.append(" \"").append(dir).append("\"");
        }
        script.append("; do\n");
        script.append("  case \":$PATH:\" in *\":$_d:\"*) :;; *) PATH=\"$_d:$PATH\";; esac\n");
        script.append("done\n");
        script.append("which ").append(name).append(" 2>/dev/null");
        String out = runForOutput(List.of("sh", "-c", script.toString()), 5, false);
        if (out == null) return null;
        try {
            if (Files.isExecutable(Path.of(out))) return out;
        } catch (Exception ignored) {}
        return null;
    }

    @Nullable
    private static String runQuick(@NotNull String cmd, @NotNull String... args) {
        List<String> command = new ArrayList<>();
        command.add(cmd);
        for (String a : args) command.add(a);
        return runForOutput(command, 5, true);
    }

    /**
     * Runs {@code command} and returns its trimmed stdout, or {@code null} when it
     * fails to start, times out or prints nothing. Output is drained on separate
     * threads, so the timeout holds even when a slow rc file blocks the shell.
     */
    @Nullable
    private static String runForOutput(@NotNull List<String> command, int timeoutSeconds, boolean mergeStderr) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(mergeStderr);
            ProcessExecution.Result result = ProcessExecution.run(pb, Duration.ofSeconds(timeoutSeconds));
            if (result.timedOut()) return null;
            String out = result.stdout().trim();
            return out.isBlank() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }
}
