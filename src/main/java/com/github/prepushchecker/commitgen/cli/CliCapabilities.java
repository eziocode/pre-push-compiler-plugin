package com.github.prepushchecker.commitgen.cli;

import com.github.prepushchecker.ProcessExecution;
import com.github.prepushchecker.commitgen.CliPathResolver;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The long options an installed CLI advertises in its {@code --help}, probed once per binary
 * (path + modification time + size, so an upgrade is probed again) and cached for the IDE session.
 *
 * <p>The speed flags in {@link AiCliCommand} are only added when the installed version lists
 * them: an older CLI that does not know {@code --strict-mcp-config} keeps working instead of
 * failing on an unknown option. {@link #UNKNOWN} (the probe failed) means "add nothing new".</p>
 */
public final class CliCapabilities {

    private static final Logger LOG = Logger.getInstance(CliCapabilities.class);
    private static final Pattern LONG_FLAG = Pattern.compile("(?<![A-Za-z0-9-])(--[a-z0-9][a-z0-9-]*)");
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    /** Probe failed or not run: callers fall back to the flags every version accepts. */
    public static final CliCapabilities UNKNOWN = new CliCapabilities(Collections.emptySet(), false);

    private record Key(String path, long modified, long size) {}

    private static final ConcurrentHashMap<Key, CliCapabilities> CACHE = new ConcurrentHashMap<>();

    private final Set<String> flags;
    private final boolean known;

    private CliCapabilities(@NotNull Set<String> flags, boolean known) {
        this.flags = flags;
        this.known = known;
    }

    /** Capabilities with exactly {@code flags}; for tests and callers that already know them. */
    public static @NotNull CliCapabilities of(@NotNull String... flags) {
        return new CliCapabilities(Set.of(flags), true);
    }

    /** {@code true} when the probe ran and listed options. */
    public boolean known() { return known; }

    /** {@code true} when {@code flag} (e.g. {@code --tools}) appears in the CLI's help. */
    public boolean supports(@NotNull String flag) {
        return flags.contains(flag);
    }

    public @NotNull Set<String> flags() { return Collections.unmodifiableSet(flags); }

    /**
     * Probes {@code path} (blocking, at most once per binary version). Codex lists its exec
     * options under {@code codex exec --help}; every other CLI under {@code --help}.
     */
    public static @NotNull CliCapabilities probe(@Nullable String path) {
        if (path == null || path.isBlank()) return UNKNOWN;
        Key key = keyOf(path);
        if (key == null) return UNKNOWN;
        CliCapabilities cached = CACHE.get(key);
        if (cached != null) return cached;
        long started = System.nanoTime();
        CliCapabilities probed = runProbe(path);
        LOG.info("AI CLI capabilities for " + path + ": " + probed.flags.size() + " options in "
            + (System.nanoTime() - started) / 1_000_000 + " ms");
        // A cancelled probe says nothing about the CLI: probe again next time.
        if (!Thread.currentThread().isInterrupted()) CACHE.put(key, probed);
        return probed;
    }

    /** Forgets every probe; "Detect Local CLIs" calls this after an install or upgrade. */
    public static void clearCache() {
        CACHE.clear();
    }

    /** The long options mentioned in {@code helpText}. */
    static @NotNull Set<String> parse(@Nullable String helpText) {
        Set<String> out = new LinkedHashSet<>();
        if (helpText == null) return out;
        Matcher m = LONG_FLAG.matcher(helpText);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static @Nullable Key keyOf(@NotNull String path) {
        try {
            Path p = Path.of(path).toRealPath();
            return new Key(p.toString(), Files.getLastModifiedTime(p).toMillis(), Files.size(p));
        } catch (Exception e) {
            return null;
        }
    }

    private static @NotNull CliCapabilities runProbe(@NotNull String path) {
        String name = Path.of(path).getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        List<String> cmd = new ArrayList<>();
        cmd.add(path);
        if (name.equals("codex") || name.startsWith("codex.")) cmd.add("exec");
        cmd.add("--help");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            CliPathResolver.injectAugmentedPath(pb);
            pb.environment().put("NO_COLOR", "1");
            ProcessExecution.Result r = ProcessExecution.run(pb, PROBE_TIMEOUT);
            if (r.timedOut()) return UNKNOWN;
            Set<String> flags = parse(r.combinedOutput());
            return flags.isEmpty() ? UNKNOWN : new CliCapabilities(flags, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UNKNOWN;
        } catch (Exception e) {
            return UNKNOWN;
        }
    }
}
