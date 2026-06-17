package com.github.prepushchecker.commitgen;

import com.github.prepushchecker.ProcessExecution;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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
    private static final Pattern BARE_EXECUTABLE_NAME = Pattern.compile("[A-Za-z0-9._+-]+");

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
            String trimmed = configured.trim();
            Path p = Path.of(trimmed);
            if (p.isAbsolute()) {
                return Files.isExecutable(p) ? p.toString() : null;
            }
            if (isBareExecutableName(trimmed)) {
                String shellResult = whichViaShell(trimmed);
                if (shellResult != null) return shellResult;
            }
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
        if (resolved == null) return false;
        if (resolved.equals(cliName)) {
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
            ProcessExecution.Result result = ProcessExecution.run(pb, Duration.ofSeconds(8));
            if (!result.isSuccess()) return null;
            String out = result.stdout().trim();
            return out.isBlank() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolves {@code name} against the PATH produced by the user's shell rc
     * files, returning the executable path or {@code null}.
     */
    @Nullable
    public static String whichViaShell(@NotNull String name) {
        if (!isBareExecutableName(name)) return null;
        String shellPath = resolveShellPath();
        String found = findOnPath(name, shellPath);
        if (found != null) return found;
        found = findOnPath(name, System.getenv("PATH"));
        if (found != null) return found;
        for (String dir : EXTRA_PATH_DIRS) {
            Path candidate = Path.of(dir, name);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
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
            injectAugmentedPath(pb);
            ProcessExecution.Result result = ProcessExecution.run(pb, Duration.ofSeconds(5));
            String out = result.combinedOutput().trim();
            return result.isSuccess() && !out.isBlank() ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBareExecutableName(@NotNull String name) {
        return BARE_EXECUTABLE_NAME.matcher(name).matches();
    }

    @Nullable
    private static String findOnPath(@NotNull String name, @Nullable String pathValue) {
        if (pathValue == null || pathValue.isBlank()) return null;
        for (String entry : pathValue.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) continue;
            Path candidate = Path.of(entry, name);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return null;
    }
}
