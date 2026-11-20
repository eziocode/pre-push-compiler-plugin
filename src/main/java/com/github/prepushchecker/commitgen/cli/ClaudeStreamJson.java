package com.github.prepushchecker.commitgen.cli;

import com.github.prepushchecker.commitgen.JsonUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Reads {@code claude -p --output-format stream-json --include-partial-messages} output: one JSON
 * event per line. Text arrives as {@code content_block_delta} / {@code text_delta} events; the
 * last {@code result} event carries the whole answer.
 */
public final class ClaudeStreamJson {

    private ClaudeStreamJson() {}

    /** The text a {@code text_delta} line adds, or {@code null} for any other line. */
    public static @Nullable String delta(@Nullable String line) {
        if (line == null || !line.contains("\"text_delta\"")) return null;
        Object parsed = JsonUtil.parse(line.strip());
        if (!(parsed instanceof Map<?, ?> root) || !"stream_event".equals(root.get("type"))) return null;
        if (!(root.get("event") instanceof Map<?, ?> event)
            || !"content_block_delta".equals(event.get("type"))) return null;
        if (!(event.get("delta") instanceof Map<?, ?> delta) || !"text_delta".equals(delta.get("type"))) return null;
        return delta.get("text") instanceof String text ? text : null;
    }

    /**
     * The final answer: the {@code result} event's text, else the joined deltas. {@code null}
     * when {@code stdout} holds no stream events (e.g. an older CLI printed plain text).
     */
    public static @Nullable String finalText(@Nullable String stdout) {
        if (stdout == null || stdout.isBlank()) return null;
        String result = null;
        StringBuilder deltas = new StringBuilder();
        boolean sawEvent = false;
        for (String line : stdout.split("\n")) {
            String t = line.strip();
            if (!t.startsWith("{")) continue;
            String d = delta(t);
            if (d != null) {
                deltas.append(d);
                sawEvent = true;
                continue;
            }
            if (!t.contains("\"result\"")) continue;
            Object parsed = JsonUtil.parse(t);
            if (parsed instanceof Map<?, ?> root && "result".equals(root.get("type"))) {
                sawEvent = true;
                if (Boolean.TRUE.equals(root.get("is_error"))) {
                    throw new RuntimeException(String.valueOf(root.get("result")));
                }
                if (root.get("result") instanceof String text) result = text;
            }
        }
        if (result != null && !result.isBlank()) return result;
        return sawEvent ? deltas.toString() : null;
    }
}
