package com.github.prepushchecker.commitgen;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Resolves the absolute path of a CLI tool the same way the pre-push hook script
 * does: by running {@code which} inside a login/interactive sub-shell so that
 * the user's full PATH (Homebrew, npm globals, asdf shims, SDKMAN, pyenv, etc.)
 * is available even when IntelliJ was launched as a GUI app with a stripped PATH.
 *
 * <h3>Resolution order</h3>
 * <ol>
 *   <li>If the configured path is an absolute file that is executable — use it.</li>
 *   <li>Run {@code which <name>} via a login/interactive shell.</li>
 *   <li>Probe a curated list of well-known install locations.</li>
 *   <li>Fall back to the bare name and let the OS resolve it (may fail if PATH is
 *       minimal).</li>
 * </ol>
 */
public final class CliPathResolver {

    /** Shell rc files sourced when building the augmented PATH. */
    private static final List<String> RC_FILES = List.of(
        ".zshenv", ".zprofile", ".zshrc",
        ".bash_profile", ".bashrc", ".profile"
    );

    /**
     * Well-known directories where tools like {@code codex}, {@code llm},
     * {@code gh}, and similar end up after a typical install.
     */
    private static final List<String> EXTRA_PATH_DIRS = List.of(
        "/opt/homebrew/bin",          // macOS Apple-silicon Homebrew
        "/usr/local/bin",             // macOS Intel Homebrew / manual installs
        "/opt/local/bin",             // MacPorts
        "/usr/bin",
        "/bin",
        System.getProperty("user.home") + "/.local/bin",      // pip --user
        System.getProperty("user.home") + "/.npm-global/bin", // npm --global prefix
        System.getProperty("user.home") + "/.cargo/bin",      // Cargo (Rust)
        System.getProperty("user.home") + "/.asdf/shims",     // asdf
        System.getProperty("user.home") + "/go/bin",          // Go
        "/usr/local/npm/bin",
        "/usr/local/share/npm/bin"
    );

    private CliPathResolver() {}

    /**
     * Returns the full absolute path to {@code cliName}, or {@code null} if it
     * cannot be found anywhere on the search path.
     *
     * @param configured the value from Settings (may be blank or a bare name)
     * @param cliName    the bare executable name (e.g. {@code "codex"})
     */
    @Nullable
    public static String resolve(@Nullable String configured, @NotNull String cliName) {
        // 1. Explicitly configured absolute path
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.trim());
            if (p.isAbsolute() && Files.isExecutable(p)) return p.toString();
            // Might be a bare name override — fall through to shell resolution
            String nameOverride = configured.trim();
            String shellResult = whichViaShell(nameOverride);
            if (shellResult != null) return shellResult;
        }

        // 2. Shell which (sources rc files — picks up Homebrew, nvm, asdf, etc.)
        String shellResult = whichViaShell(cliName);
        if (shellResult != null) return shellResult;

        // 3. Probe well-known install directories
        for (String dir : EXTRA_PATH_DIRS) {
            Path candidate = Path.of(dir, cliName);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }

        // 4. Bare name — let the process builder resolve it
        return cliName;
    }

    /**
     * Returns {@code true} when the CLI can be found and is executable.
     */
    public static boolean isAvailable(@Nullable String configured, @NotNull String cliName) {
        String resolved = resolve(configured, cliName);
        if (resolved == null || resolved.equals(cliName)) {
            // bare name — test by running --version
            return runQuick(cliName, "--version") != null;
        }
        return Files.isExecutable(Path.of(resolved));
    }

    /**
     * Returns the detected version string for {@code cliName}, or {@code null}.
     */
    @Nullable
    public static String detectVersion(@Nullable String configured, @NotNull String cliName) {
        String resolved = resolve(configured, cliName);
        if (resolved == null) return null;
        return runQuick(resolved, "--version");
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Resolves the full PATH string as the user's shell sees it (sources rc
     * files + adds well-known directories) and injects it into
     * {@code pb.environment()}.
     *
     * <p>Call this on every {@link ProcessBuilder} that runs a CLI tool so
     * that scripts with shebangs like {@code #!/usr/bin/env node} or
     * {@code #!/usr/bin/env python3} can find their runtime even when
     * IntelliJ was launched as a GUI app with a stripped PATH.
     *
     * @param pb the ProcessBuilder whose environment should be augmented
     */
    public static void injectAugmentedPath(@NotNull ProcessBuilder pb) {
        String resolved = resolveShellPath();
        if (resolved != null && !resolved.isBlank()) {
            pb.environment().put("PATH", resolved);
        }
    }

    /**
     * Returns the full PATH string from the user's shell (sources rc files and
     * injects well-known directories), or {@code null} if the sub-shell fails.
     */
    @Nullable
    public static String resolveShellPath() {
        String home = System.getProperty("user.home");

        StringBuilder script = new StringBuilder();
        for (String rc : RC_FILES) {
            Path rcFile = Path.of(home, rc);
            if (Files.isRegularFile(rcFile)) {
                script.append(". \"").append(rcFile).append("\" 2>/dev/null || true\n");
            }
        }
        // Prepend well-known dirs so they are always available
        script.append("for _d in");
        for (String dir : EXTRA_PATH_DIRS) {
            script.append(" \"").append(dir).append("\"");
        }
        script.append("; do\n");
        script.append("  case \":$PATH:\" in *\":$_d:\"*) :;; *) PATH=\"$_d:$PATH\";; esac\n");
        script.append("done\n");
        script.append("printf '%s' \"$PATH\"");

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", script.toString());
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            String out;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.lines().collect(Collectors.joining()).trim();
            }
            proc.waitFor(8, TimeUnit.SECONDS);
            return out.isBlank() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Runs {@code which <name>} inside a subshell that sources the user's rc
     * files, returning the trimmed path or {@code null}.
     */
    @Nullable
    public static String whichViaShell(@NotNull String name) {
        String home = System.getProperty("user.home");

        // Build a script that sources rc files then runs `which`
        StringBuilder script = new StringBuilder();
        for (String rc : RC_FILES) {
            Path rcFile = Path.of(home, rc);
            if (Files.isRegularFile(rcFile)) {
                script.append(". \"").append(rcFile).append("\" 2>/dev/null || true\n");
            }
        }
        // Add well-known dirs to PATH
        script.append("for _d in");
        for (String dir : EXTRA_PATH_DIRS) {
            script.append(" \"").append(dir).append("\"");
        }
        script.append("; do\n");
        script.append("  case \":$PATH:\" in *\":$_d:\"*) :;; *) PATH=\"$_d:$PATH\";; esac\n");
        script.append("done\n");
        script.append("which ").append(name).append(" 2>/dev/null");

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", script.toString());
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            String out;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.lines().collect(Collectors.joining("\n")).trim();
            }
            proc.waitFor(5, TimeUnit.SECONDS);
            if (!out.isBlank()) {
                Path p = Path.of(out);
                if (Files.isExecutable(p)) return out;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Runs {@code cmd args} and returns trimmed stdout, or {@code null} on error. */
    @Nullable
    private static String runQuick(@NotNull String cmd, @NotNull String... args) {
        List<String> command = new ArrayList<>();
        command.add(cmd);
        for (String a : args) command.add(a);
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String out;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.lines().collect(Collectors.joining("\n")).trim();
            }
            boolean done = proc.waitFor(5, TimeUnit.SECONDS);
            if (!done) { proc.destroyForcibly(); return null; }
            return out.isBlank() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }
}
