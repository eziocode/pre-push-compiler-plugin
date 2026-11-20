package com.github.prepushchecker.commitgen;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generated commit messages keyed by a hash of the prompt (diff + rules) and the provider settings,
 * plus generations still running. Clicking Generate again for the same diff, or after a
 * speculative pre-generation, returns at once (or joins the running call) instead of asking the AI
 * again.
 */
public final class CommitMessageCache {

    static final int MAX_ENTRIES = 16;
    static final long TTL_MS = 30 * 60 * 1000L;

    private record Entry(@NotNull String message, long at) {}

    private static final Map<String, Entry> DONE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    private static final ConcurrentHashMap<String, CompletableFuture<String>> RUNNING = new ConcurrentHashMap<>();

    private CommitMessageCache() {}

    /** Key for one prompt sent with the current provider settings. */
    public static @NotNull String key(@NotNull String system, @NotNull String user,
                                      @NotNull CommitMessageSettings.State s) {
        String settings = String.join("\u0000", nz(s.selectedProvider), nz(s.openAiModel), nz(s.anthropicModel),
            nz(s.geminiModel), nz(s.ollamaModel), nz(s.ollamaBaseUrl), nz(s.codexModel), nz(s.codexEffort),
            nz(s.codexExtraArgs), nz(s.claudeModel), nz(s.claudeEffort), nz(s.claudeExtraArgs), nz(s.ghModel),
            nz(s.llmModel), nz(s.customCliBinary), nz(s.customCliModel), nz(s.customCliArgs));
        return sha256(system + "\u0000" + user + "\u0000" + settings);
    }

    /** The cached message for {@code key}, or {@code null}. */
    public static @Nullable String get(@NotNull String key) {
        synchronized (DONE) {
            Entry e = DONE.get(key);
            if (e == null) return null;
            if (System.currentTimeMillis() - e.at > TTL_MS) {
                DONE.remove(key);
                return null;
            }
            return e.message;
        }
    }

    public static void put(@NotNull String key, @NotNull String message) {
        synchronized (DONE) {
            DONE.put(key, new Entry(message, System.currentTimeMillis()));
        }
    }

    /** A generation for {@code key} that is still running, or {@code null}. */
    public static @Nullable CompletableFuture<String> running(@NotNull String key) {
        return RUNNING.get(key);
    }

    /**
     * Registers a running generation; {@code false} when one for {@code key} already runs. The
     * entry is removed when {@code future} completes, and a successful result is cached.
     */
    public static boolean startRunning(@NotNull String key, @NotNull CompletableFuture<String> future) {
        if (RUNNING.putIfAbsent(key, future) != null) return false;
        future.whenComplete((message, error) -> {
            RUNNING.remove(key, future);
            if (error == null && message != null && !message.isBlank()) put(key, message);
        });
        return true;
    }

    /** Forgets everything; for tests. */
    static void clear() {
        synchronized (DONE) {
            DONE.clear();
        }
        RUNNING.clear();
    }

    private static @NotNull String nz(@Nullable String v) {
        return v == null ? "" : v;
    }

    private static @NotNull String sha256(@NotNull String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode()) + ":" + s.length();
        }
    }
}
