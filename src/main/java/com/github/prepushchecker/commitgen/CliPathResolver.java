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

    private static final List<String> RC_FILES = List.of(
        ".zshenv", ".zprofile", ".zshrc",
        ".bash_profile", ".bashrc", ".profile"
    );

    private static final List<String> EXTRA_PATH_DIRS = List.of(
        "/opt/homebrew/bin",
        "/usr/local/bin",
        "/opt/local/bin",
        "/usr/bin",
        "/bin",
        System.getProperty("user.home") + "/.local/bin",
        System.getProperty("user.home") + "/.npm-global/bin",
        System.getProperty("user.home") + "/.cargo/bin",
        System.getProperty("user.home") + "/.asdf/shims",
        System.getProperty("user.home") + "/go/bin",
        "/usr/local/npm/bin",
        "/usr/local/share/npm/bin"
    );

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
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.trim());
            // Absolute path: use as-is if executable (no shell involved)
            if (p.isAbsolute() && Files.isExecutable(p)) return p.toString();
            // Bare name override: only pass to shell if it contains no shell-special chars
            String trimmed = configured.trim();
            if (isSafeBareNameForShell(trimmed)) {
                String shellResult = whichViaShell(trimmed);
                if (shellResult != null) return shellResult;
            }
        }
        String shellResult = whichViaShell(cliName);
        if (shellResult != null) return shellResult;
        for (String dir : EXTRA_PATH_DIRS) {
            Path candidate = Path.of(dir, cliName);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return cliName;
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
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", script.toString());
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            String out;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.lines().collect(Collectors.joining()).trim();
            }
            proc.waitFor(5, TimeUnit.SECONDS);
            return out.isBlank() ? null : out;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Returns the full PATH string from the user's shell, or {@code null}. */
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
