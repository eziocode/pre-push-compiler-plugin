package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.commitgen.CliPathResolver;
import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.CommitMessageSettings;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Generates commit messages by running the local <b>Codex CLI</b> inside a
 * Python-allocated pseudo-terminal (PTY), which satisfies the CLI's
 * {@code "stdin is not a terminal"} requirement without needing a real
 * interactive session.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>A small Python 3 script is executed. Python's {@code pty} module
 *       opens a master/slave PTY pair and launches {@code codex} with the
 *       slave as its stdin/stdout — the CLI sees a real terminal.</li>
 *   <li>The full prompt is written to the Python script's stdin (not as a
 *       CLI argument, so no argument-parser rejection).</li>
 *   <li>Python feeds the prompt into the PTY master and waits for the CLI to
 *       finish, then strips ANSI escape sequences and prints clean text.</li>
 *   <li>The commit message is delimited with {@code <<<COMMIT_START>>>} /
 *       {@code <<<COMMIT_END>>>} markers in the prompt, so the response is
 *       reliably extracted from any surrounding TUI output.</li>
 * </ol>
 *
 * <h3>Authentication</h3>
 * Run {@code codex auth} once in a terminal — no API key required.
 */
public final class CodexCliProvider implements CommitMessageProvider {

    public static final String CLI_NAME = "codex";

    /** Python 3 PTY wrapper script. Reads codex-path from argv[1], prompt from stdin. */
    private static final String PTY_SCRIPT = String.join("\n",
        "import sys, os, pty, subprocess, time, select, re",
        "codex = sys.argv[1]",
        "prompt = sys.stdin.read()",
        "master, slave = pty.openpty()",
        "try:",
        "    import fcntl, termios, struct",
        "    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 50, 220, 0, 0))",
        "except Exception:",
        "    pass",
        "proc = subprocess.Popen([codex], stdin=slave, stdout=slave, stderr=slave,",
        "                        close_fds=True, start_new_session=True)",
        "os.close(slave)",
        "time.sleep(1.5)",          // wait for TUI to initialise
        "# feed prompt in small chunks to avoid EAGAIN",
        "data = prompt.encode('utf-8')",
        "for i in range(0, len(data), 64):",
        "    os.write(master, data[i:i+64])",
        "    time.sleep(0.02)",
        "os.write(master, b'\\n')",
        "# read until process exits (max 90 s)",
        "out = bytearray()",
        "deadline = time.time() + 90",
        "last = time.time()",
        "while time.time() < deadline:",
        "    r = select.select([master], [], [], 0.3)[0]",
        "    if r:",
        "        try:",
        "            chunk = os.read(master, 4096)",
        "            if chunk:",
        "                out.extend(chunk); last = time.time()",
        "        except OSError:",
        "            break",
        "    elif proc.poll() is not None and time.time()-last > 1.0:",
        "        break",
        "try:",
        "    proc.wait(timeout=5)",
        "except Exception:",
        "    proc.kill()",
        "text = out.decode('utf-8', errors='replace')",
        "text = re.sub(r'\\x1b\\[[0-9;?]*[A-Za-z]', '', text)",  // CSI
        "text = re.sub(r'\\x1b[()][A-Z0-9]', '', text)",
        "text = re.sub(r'\\x1b[=>]', '', text)",
        "text = re.sub(r'\\r\\n|\\r', '\\n', text)",
        "print(text)"
    );

    @Override
    public @NotNull String generate(@NotNull String systemPrompt, @NotNull String userPrompt)
            throws Exception {
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        String codexPath = CliPathResolver.resolve(s.codexCliPath, CLI_NAME);

        // Ask codex to wrap the commit message in delimiters so we can parse it
        // out of any surrounding TUI / agent output reliably.
        String markedPrompt = systemPrompt
            + "\n\n" + userPrompt
            + "\n\nIMPORTANT: wrap your final commit message exactly like this:\n"
            + "<<<COMMIT_START>>>\n"
            + "<the commit message here>\n"
            + "<<<COMMIT_END>>>\n"
            + "Output nothing outside those markers after you decide on the message.";

        // Run Python PTY wrapper; pass codex path as arg, prompt via stdin
        String python3 = CliPathResolver.resolve(null, "python3");
        ProcessBuilder pb = new ProcessBuilder(python3, "-c", PTY_SCRIPT, codexPath);
        pb.redirectErrorStream(false);
        CliPathResolver.injectAugmentedPath(pb);

        Process proc;
        try {
            proc = pb.start();
        } catch (java.io.IOException e) {
            throw new RuntimeException(
                "Cannot start Python 3 (required to allocate a PTY for Codex CLI).\n"
                    + "Ensure python3 is installed and on PATH.", e);
        }

        // Write prompt to Python script's stdin
        try (Writer w = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8)) {
            w.write(markedPrompt);
        }

        String stdout;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            stdout = r.lines().collect(Collectors.joining("\n"));
        }
        String stderr;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
            stderr = r.lines().collect(Collectors.joining("\n"));
        }

        boolean finished = proc.waitFor(95, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new RuntimeException("Codex CLI timed out after 90 seconds.");
        }

        return parseDelimitedMessage(stdout, stderr);
    }

    @Override
    public @NotNull Id id() { return Id.CODEX_CLI; }

    private static @NotNull String parseDelimitedMessage(
            @NotNull String stdout, @NotNull String stderr) {
        // Prefer delimited section
        int start = stdout.indexOf("<<<COMMIT_START>>>");
        int end   = stdout.indexOf("<<<COMMIT_END>>>");
        if (start >= 0 && end > start) {
            String msg = stdout.substring(start + "<<<COMMIT_START>>>".length(), end).trim();
            if (!msg.isBlank()) return msg;
        }
        // Fallback: last non-blank line that looks like a commit message
        String[] lines = stdout.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isBlank() && line.length() < 300
                    && !line.startsWith("<<<") && !line.startsWith("[")) {
                return line;
            }
        }
        String detail = stderr.isBlank() ? stdout.trim() : stderr.trim();
        throw new RuntimeException(
            "Codex CLI produced no recognisable commit message.\n"
                + (detail.isBlank() ? "Run 'codex auth' to authenticate." : detail));
    }
}
