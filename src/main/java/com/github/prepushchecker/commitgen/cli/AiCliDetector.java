package com.github.prepushchecker.commitgen.cli;

import com.github.prepushchecker.ProcessExecution;
import com.github.prepushchecker.commitgen.CliPathResolver;
import com.github.prepushchecker.commitgen.CommitMessageSettings;
import com.github.prepushchecker.commitgen.providers.GhCopilotProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Finds which local AI CLIs are installed and whether they are signed in.
 * Every method blocks on subprocesses — never call on the EDT.
 */
public final class AiCliDetector {

    /** Result of probing one CLI. {@code path == null} means not found. */
    public record DetectedCli(@NotNull AiCli cli, @Nullable String path,
                              @NotNull String version, @NotNull String auth) {
        public boolean found() { return path != null; }
    }

    private AiCliDetector() {}

    /** Probes every registered CLI in parallel. Order matches {@link AiCli#values()}. */
    public static @NotNull List<DetectedCli> scanAll(@NotNull CommitMessageSettings.State settings) {
        AiCli[] all = AiCli.values();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(all.length, 6), r -> {
            Thread t = new Thread(r, "CommitGen-CliScan");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<DetectedCli>> futures = new ArrayList<>();
            for (AiCli cli : all) {
                String configured = cli.configuredPath(settings);
                futures.add(pool.submit(() -> detect(cli, configured)));
            }
            List<DetectedCli> out = new ArrayList<>();
            for (int i = 0; i < all.length; i++) {
                try {
                    out.add(futures.get(i).get(60, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    out.add(new DetectedCli(all[i], null, "", "interrupted"));
                } catch (Exception e) {
                    out.add(new DetectedCli(all[i], null, "", "detection failed"));
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Probes a single CLI: path, {@code --version}, and sign-in state. */
    public static @NotNull DetectedCli detect(@NotNull AiCli cli, @Nullable String configuredPath) {
        String path = CliPathResolver.resolveOrNull(configuredPath, cli.binaryName());
        if (path == null) return new DetectedCli(cli, null, "", "not installed");
        return new DetectedCli(cli, path, version(path), authStatus(cli, path));
    }

    /** First line of {@code <path> --version}, or empty. */
    public static @NotNull String version(@NotNull String path) {
        return firstLine(run(path, List.of("--version"), 10));
    }

    /** Human-readable sign-in state, prefixed with ✓ / ✗ / ? . */
    public static @NotNull String authStatus(@NotNull AiCli cli, @NotNull String path) {
        String home = System.getProperty("user.home");
        switch (cli) {
            case CLAUDE: {
                if (Files.isRegularFile(Path.of(home, ".claude.json"))
                        || Files.isRegularFile(Path.of(home, ".claude", ".credentials.json"))) {
                    return "✓ claude config found (run `claude` once if prompts fail)";
                }
                if (CliPathResolver.resolveEnvVar("ANTHROPIC_API_KEY") != null) {
                    return "✓ ANTHROPIC_API_KEY set";
                }
                return "✗ Not signed in — run: claude";
            }
            case CODEX: {
                ProcessExecution.Result r = runResult(path, List.of("login", "status"), 10);
                String out = r == null ? "" : firstLine(r.combinedOutput());
                if (r != null && r.isSuccess()) return "✓ " + (out.isBlank() ? "Signed in" : out);
                return "✗ Not signed in — run: codex login";
            }
            case GH: {
                return GhCopilotProvider.getGhToken(path) != null
                    ? "✓ Signed in to GitHub CLI"
                    : "✗ Not signed in — run: gh auth login";
            }
            default:
                return "? Uses the CLI's own login";
        }
    }

    private static @NotNull String run(@NotNull String path, @NotNull List<String> args, int seconds) {
        ProcessExecution.Result r = runResult(path, args, seconds);
        return r == null ? "" : r.combinedOutput().trim();
    }

    private static @Nullable ProcessExecution.Result runResult(@NotNull String path,
                                                               @NotNull List<String> args, int seconds) {
        List<String> cmd = new ArrayList<>();
        cmd.add(path);
        cmd.addAll(args);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            CliPathResolver.injectAugmentedPath(pb);
            return ProcessExecution.run(pb, Duration.ofSeconds(seconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static @NotNull String firstLine(@NotNull String s) {
        String t = s.trim();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl).trim();
    }
}
