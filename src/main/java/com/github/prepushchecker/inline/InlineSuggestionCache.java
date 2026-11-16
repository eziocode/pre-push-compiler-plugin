package com.github.prepushchecker.inline;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small access-ordered LRU of recent suggestions, keyed by file path plus the last
 * {@link #KEY_TAIL} characters of the text before the caret. Thread safe.
 */
public final class InlineSuggestionCache {
    public static final int CAPACITY = 20;
    public static final int KEY_TAIL = 200;

    private final Map<String, String> entries = new LinkedHashMap<>(CAPACITY + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CAPACITY;
        }
    };

    public synchronized void put(@NotNull String path, @NotNull String prefix, @NotNull String suggestion) {
        entries.put(key(path, prefix), suggestion);
    }

    public synchronized @Nullable String get(@NotNull String path, @NotNull String prefix) {
        return entries.get(key(path, prefix));
    }

    public synchronized void clear() {
        entries.clear();
    }

    private static String key(String path, String prefix) {
        String tail = prefix.length() > KEY_TAIL ? prefix.substring(prefix.length() - KEY_TAIL) : prefix;
        return path + '\u0000' + tail;
    }
}
