package com.github.prepushchecker.commitgen;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw AI output into a plain commit message.
 *
 * <p>Local agent CLIs print far more than the answer on stdout: ANSI colours,
 * spinner frames, thinking text, tool-call blocks, signatures, JSON event
 * streams, transcript markers and token/cost footers. This class removes all
 * of that and keeps only the message. It is pure (no IDE state) so every noise
 * kind can be unit tested.</p>
 */
public final class AiOutputSanitizer {

    /** Max characters of raw provider output quoted in an error message. */
    public static final int MAX_ERROR_DETAIL = 500;

    /** Message of the exception thrown when nothing usable is left. */
    public static final String EMPTY_RESULT_MESSAGE =
        "AI returned no commit message after removing tool output, thinking text and other noise.";

    private static final Pattern ANSI = Pattern.compile(
        "\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)"   // OSC … BEL / ST
            + "|\u001B\\[[0-?]*[ -/]*[@-~]"               // CSI
            + "|\u001B[@-Z\\\\-_]");                      // other 2-byte escapes
    /**
     * {@code String.trim()} (used by {@link com.github.prepushchecker.ProcessExecution}) strips a
     * leading ESC, leaving e.g. {@code [2m} at the very start. Only SGR / erase / cursor-home finals
     * are matched so "[JIRA-1]" or "[2fa]" survive.
     */
    private static final Pattern LEADING_ORPHAN_CSI = Pattern.compile("\\A\\[[0-9][0-9;]*[mKJHG]");
    private static final Pattern CONTROL = Pattern.compile("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]");

    private static final String BLOCK_TAGS = "(?:antml:)?(?:think|thinking|reasoning|reflection|tool_call|tool_calls"
        + "|tool_use|function_calls|function_call|invoke|parameter|tool_result|tool_results|function_results"
        + "|signature)";
    private static final Pattern SELF_CLOSING_TAG = Pattern.compile("(?i)<" + BLOCK_TAGS + "\\b[^>]*/>");
    private static final Pattern TAG_BLOCK = Pattern.compile(
        "(?is)<(" + BLOCK_TAGS + ")\\b[^>]*>.*?(?:</\\1\\s*>|\\z)");
    /** Some reasoning models emit only the closing tag after their thinking. */
    private static final Pattern ORPHAN_THINK_CLOSE = Pattern.compile(
        "(?is)\\A.*</(?:antml:)?(?:think|thinking|reasoning)\\s*>");
    private static final Pattern STRAY_TAG = Pattern.compile("(?i)</?" + BLOCK_TAGS + "\\b[^>]*>");

    private static final Pattern FENCE = Pattern.compile(
        "(?m)^[ \\t]*```[ \\t]*([A-Za-z0-9_+.-]*)[^\\n]*\\n(.*?)^[ \\t]*```[ \\t]*$", Pattern.DOTALL);
    private static final Set<String> TEXT_FENCE_LANGS = Set.of(
        "", "text", "txt", "plain", "plaintext", "git", "commit", "gitcommit", "git-commit", "markdown", "md");

    private static final List<Pattern> NOISE_LINES = List.of(
        // Agent transcript markers (Claude Code, Codex, Gemini, Copilot …).
        Pattern.compile("^\\s*[⏺●⎿✓✔✗✘⚙\uD83D\uDD27](?:\\s|$).*"),
        Pattern.compile("(?i)^\\s*>\\s*(?:running|executing|calling)\\b.*"),
        Pattern.compile("(?i)^\\s*\\[(?:tool|tools|tool_call|tool_use|tool_result|function|thinking|reasoning"
            + "|model|debug|info|status|session|usage|exec|agent)\\b[^\\]]*\\].*"),
        Pattern.compile("(?i)^\\s*(?:tool call|tool calls|tool result|tool use|calling tool|called tool|using tool"
            + "|running tool|function call|signature|thinking|reasoning)\\s*(?:[:(…]|\\.\\.\\.).*"),
        Pattern.compile("(?i)^\\s*(?:thinking|reasoning|working|loading)\\s*(?:\\.{3}|…)?\\s*$"),
        // Token / cost / timing footers.
        Pattern.compile("(?i)^\\s*(?:tokens?|tokens used|total tokens|input tokens|output tokens|cached tokens"
            + "|total cost|cost|duration|api duration|wall time|total duration)\\s*[:=].*"),
        Pattern.compile("(?i)^\\s*[\\d,.]+\\s*[kKmM]?\\s+(?:input|output|total)?\\s*tokens\\b.*"),
        // Box-drawing frames.
        Pattern.compile("^\\s*[╭╮╰╯│┃┌┐└┘├┤─━═║╔╗╚╝┏┓┗┛].*"),
        // Bare signature / base64 blobs.
        Pattern.compile("^\\s*[A-Za-z0-9+/=_-]{80,}\\s*$"));

    private static final List<Pattern> PREAMBLE = List.of(
        Pattern.compile("(?i)^(?:sure|okay|ok|certainly|of course|absolutely|great|alright|done)\\b[!.,]?\\s*$"),
        Pattern.compile("(?i)^(?:(?:sure|okay|ok|certainly)[,!.]?\\s*)?(?:here(?:'s| is| are)|below is|the following"
            + "|i(?:'ve| have)?\\s*(?:generated|written|created|drafted|prepared)|based on (?:the|your)).*:\\s*$"),
        Pattern.compile("(?i)^(?:suggested |proposed |generated )?commit message\\s*:?\\s*$"));
    private static final Pattern TRAILER = Pattern.compile(
        "(?i)^(?:let me know|feel free|hope this|i hope|i can also|would you like|if you(?:'d| would)? like"
            + "|this (?:commit )?message (?:follows|uses|describes|summari[sz]es|captures)|note:|explanation:).*");

    private static final Set<String> NON_ANSWER_TYPES = Set.of(
        "user", "system", "tool_use", "tool_call", "tool_result", "function_call", "function_call_output",
        "thinking", "redacted_thinking", "reasoning", "signature", "error", "init", "status", "ping", "usage",
        "session", "stats", "message_start", "message_stop", "message_delta", "content_block_start",
        "content_block_stop", "command_execution", "file_change", "mcp_tool_call", "turn.started",
        "thread.started", "turn.completed");
    private static final List<String> ANSWER_KEYS = List.of(
        "result", "response", "output", "text", "content", "message", "delta", "item", "choices", "candidates",
        "parts");

    private AiOutputSanitizer() {}

    /** Cleans {@code raw}; returns an empty string when nothing usable is left. */
    public static @NotNull String clean(@Nullable String raw) {
        if (raw == null) return "";
        String text = stripTerminalNoise(raw);
        text = unwrapJson(text);
        text = SELF_CLOSING_TAG.matcher(text).replaceAll("");
        text = TAG_BLOCK.matcher(text).replaceAll("");
        text = ORPHAN_THINK_CLOSE.matcher(text).replaceFirst("");
        text = STRAY_TAG.matcher(text).replaceAll("");

        String fenced = preferredFence(text);
        if (fenced != null) return tidy(stripAttribution(fenced));

        text = dropNoiseLines(text);
        text = dropPreambleAndTrailer(text);
        return unquote(tidy(stripAttribution(text)));
    }

    /** Git trailers and "generated by" footers that AI CLIs add on their own; never wanted in a message. */
    private static final Pattern ATTRIBUTION = Pattern.compile(
        "(?i)^\\s*(?:co-authored-by|signed-off-by|reviewed-by|generated-by|assisted-by)\\s*:.*"
            + "|^\\s*(?:generated|created|written|made|authored|assisted)\\s+(?:with|by)\\b.*"
            + "|.*\\uD83E\\uDD16.*"
            + "|.*claude\\.com/claude-code.*"
            + "|.*noreply@(?:anthropic|openai)\\.com.*");

    static @NotNull String stripAttribution(@NotNull String text) {
        StringBuilder out = new StringBuilder();
        boolean seenText = false;
        for (String line : text.split("\\R", -1)) {
            // The subject line is never a trailer, even when it starts with "Generated by …".
            if (seenText && ATTRIBUTION.matcher(line).matches()) continue;
            if (!line.isBlank()) seenText = true;
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString();
    }

    private static final Pattern COMPLETION_LEADING_BLOCK = Pattern.compile(
        "(?is)\\A\\s*<((?:antml:)?(?:think|thinking|reasoning|tool_call))\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern FENCE_OPEN = Pattern.compile("^[ \\t]*```[ \\t]*[A-Za-z0-9_+.#-]*[ \\t]*$");
    private static final Pattern FENCE_CLOSE = Pattern.compile("^[ \\t]*```[ \\t]*$");

    /**
     * Cleans the answer of a code-completion request. Terminal escapes and thinking/tool blocks
     * are removed and one outer code fence is unwrapped; the code itself is left untouched, so
     * lines that look like log noise, quotes and the first line's indentation survive. Leading and
     * trailing blank lines and trailing whitespace are dropped.
     *
     * @return the code, or an empty string when nothing usable is left
     */
    public static @NotNull String cleanCompletion(@Nullable String raw) {
        if (raw == null) return "";
        String text = stripTerminalNoise(raw);
        // Only complete reasoning/tool blocks at the very start are removed: code may legitimately
        // contain tags such as <parameter> or a later </think>.
        Matcher m = COMPLETION_LEADING_BLOCK.matcher(text);
        while (m.find()) {
            text = text.substring(m.end());
            m = COMPLETION_LEADING_BLOCK.matcher(text);
        }

        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        trimBlankEnds(lines);
        if (lines.size() >= 2 && FENCE_OPEN.matcher(lines.get(0)).matches()
            && FENCE_CLOSE.matcher(lines.get(lines.size() - 1)).matches()) {
            lines.remove(lines.size() - 1);
            lines.remove(0);
            trimBlankEnds(lines);
        } else {
            keepOnlyFencedBody(lines);
        }
        return stripTrailingWhitespace(String.join("\n", lines));
    }

    /**
     * Exactly one fenced block with prose around it: keep only the block body. With no fences,
     * or two or more, the lines are left as they are so real code is never removed.
     */
    private static void keepOnlyFencedBody(@NotNull List<String> lines) {
        int open = -1;
        int close = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.trim().startsWith("```")) continue;
            if (open < 0 && FENCE_OPEN.matcher(line).matches()) open = i;
            else if (open >= 0 && close < 0 && FENCE_CLOSE.matcher(line).matches()) close = i;
            else return; // a further or malformed fence marker: ambiguous, leave untouched
        }
        if (open < 0 || close < 0) return;
        List<String> body = new ArrayList<>(lines.subList(open + 1, close));
        lines.clear();
        lines.addAll(body);
        trimBlankEnds(lines);
    }

    private static void trimBlankEnds(@NotNull List<String> lines) {
        while (!lines.isEmpty() && lines.get(0).isBlank()) lines.remove(0);
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
    }

    private static @NotNull String stripTrailingWhitespace(@NotNull String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    /** Like {@link #clean} but throws when nothing usable is left. */
    public static @NotNull String cleanOrThrow(@Nullable String raw) {
        String cleaned = clean(raw);
        if (cleaned.isBlank()) throw new IllegalStateException(EMPTY_RESULT_MESSAGE);
        return cleaned;
    }

    /**
     * Extracts the JSON object answer of a structured request (review, fix) from raw provider
     * output: terminal escapes, CLI JSON envelopes, thinking and tool-call blocks are removed,
     * then the last JSON object that has {@code requiredKey} is returned (fenced or bare).
     * Unlike {@link #clean}, no text is trimmed from inside the answer.
     *
     * @return the parsed object, or {@code null} when the output holds no such object
     */
    public static @Nullable Map<?, ?> cleanStructured(@Nullable String raw, @NotNull String requiredKey) {
        if (raw == null || raw.isBlank()) return null;
        String text = stripTerminalNoise(raw);
        Map<?, ?> direct = objectWithKey(text.trim(), requiredKey);
        if (direct != null) return direct;

        String unwrapped = unwrapJson(text);
        unwrapped = SELF_CLOSING_TAG.matcher(unwrapped).replaceAll("");
        unwrapped = TAG_BLOCK.matcher(unwrapped).replaceAll("");
        unwrapped = ORPHAN_THINK_CLOSE.matcher(unwrapped).replaceFirst("");
        for (String candidate : List.of(unwrapped, text)) {
            Map<?, ?> found = lastObjectWithKey(candidate, requiredKey);
            if (found != null) return found;
        }
        return null;
    }

    private static @Nullable Map<?, ?> objectWithKey(@NotNull String json, @NotNull String key) {
        if (!json.startsWith("{") || !json.endsWith("}")) return null;
        Object parsed = JsonUtil.parse(json);
        return parsed instanceof Map<?, ?> map && map.containsKey(key) ? map : null;
    }

    /** Scans for balanced top-level {@code {…}} spans (string-aware) and keeps the last match. */
    private static @Nullable Map<?, ?> lastObjectWithKey(@NotNull String text, @NotNull String key) {
        Map<?, ?> last = null;
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf('{', i);
            if (start < 0) break;
            int end = matchingBrace(text, start);
            if (end < 0) {
                i = start + 1;
                continue;
            }
            Map<?, ?> found = objectWithKey(text.substring(start, end + 1), key);
            if (found != null) {
                last = found;
                i = end + 1;
            } else {
                i = start + 1;
            }
        }
        return last;
    }

    private static int matchingBrace(@NotNull String text, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * Short, readable form of a raw HTTP body or CLI stderr for an error message:
     * escapes removed, a JSON error unwrapped to its message, capped at
     * {@link #MAX_ERROR_DETAIL} characters.
     */
    public static @NotNull String errorDetail(@Nullable String raw) {
        if (raw == null) return "";
        String text = stripTerminalNoise(raw).trim();
        if (text.startsWith("{") || text.startsWith("[")) {
            Object json = JsonUtil.parse(text);
            if (json != null) {
                String message = firstString(json, "message", "error_description", "detail", "error");
                if (message != null && !message.isBlank()) text = message.trim();
            }
        }
        return truncate(text, MAX_ERROR_DETAIL);
    }

    /** Terminal escapes and control characters removed, for error messages. */
    public static @NotNull String stripNoiseForErrors(@Nullable String raw) {
        return raw == null ? "" : stripTerminalNoise(raw);
    }

    /** Caps {@code s} at {@code max} characters, adding an ellipsis when cut. */
    public static @NotNull String truncate(@NotNull String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ── Steps ────────────────────────────────────────────────────────────────

    private static String stripTerminalNoise(String raw) {
        String text = ANSI.matcher(raw).replaceAll("");
        text = LEADING_ORPHAN_CSI.matcher(text).replaceFirst("");
        text = text.replace("\r\n", "\n");
        // A bare \r means "overwrite this line" (spinners, progress): keep what was drawn last.
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            int cr = line.lastIndexOf('\r');
            if (out.length() > 0) out.append('\n');
            out.append(cr >= 0 ? line.substring(cr + 1) : line);
        }
        return CONTROL.matcher(out).replaceAll("");
    }

    /** Whole-output JSON or JSON event lines (stream-json / JSONL) → the answer text. */
    private static String unwrapJson(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return trimmed;
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            Object json = JsonUtil.parse(trimmed);
            if (json != null) {
                String answer = answerFromJson(json);
                return answer == null ? "" : stripTerminalNoise(answer);
            }
        }

        String finalResult = null;
        StringBuilder deltas = new StringBuilder();
        String lastEventText = null;
        boolean sawEvent = false;
        StringBuilder rest = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            Object json = t.startsWith("{") && t.endsWith("}") ? JsonUtil.parse(t) : null;
            if (!(json instanceof Map<?, ?> map) || map.isEmpty()) {
                rest.append(line).append('\n');
                continue;
            }
            sawEvent = true;
            Object type = map.get("type");
            if ("result".equals(type) && map.get("result") instanceof String result) {
                finalResult = result;
                continue;
            }
            String answer = answerFromJson(map);
            if (answer == null) continue;
            if (map.get("delta") != null || String.valueOf(type).endsWith("delta")) deltas.append(answer);
            else lastEventText = answer;
        }
        if (!sawEvent) return text;
        if (finalResult != null) return stripTerminalNoise(finalResult);
        String remaining = rest.toString();
        if (!remaining.isBlank()) return remaining;
        if (deltas.length() > 0) return deltas.toString();
        return lastEventText == null ? "" : lastEventText;
    }

    private static @Nullable String answerFromJson(@Nullable Object value) {
        if (value instanceof String s) return s;
        if (value instanceof Map<?, ?> map) {
            Object type = map.get("type");
            if (type instanceof String t && NON_ANSWER_TYPES.contains(t.toLowerCase(Locale.ROOT))) return null;
            for (String key : ANSWER_KEYS) {
                Object child = map.get(key);
                if (child == null) continue;
                String found = answerFromJson(child);
                if (found != null && !found.isBlank()) return found;
            }
            return null;
        }
        if (value instanceof List<?> list) {
            // Content blocks ([{type:text,…},{type:tool_use,…}]) are joined; other arrays keep the last answer.
            StringBuilder joined = new StringBuilder();
            String last = null;
            for (Object child : list) {
                String found = answerFromJson(child);
                if (found == null || found.isBlank()) continue;
                if (child instanceof Map<?, ?> m && "text".equals(m.get("type"))) joined.append(found);
                last = found;
            }
            return joined.length() > 0 ? joined.toString() : last;
        }
        return null;
    }

    private static @Nullable String firstString(Object json, String... keys) {
        if (json instanceof Map<?, ?> map) {
            for (String key : keys) {
                Object v = map.get(key);
                if (v instanceof String s) return s;
                if (v instanceof Map<?, ?> nested) {
                    String found = firstString(nested, keys);
                    if (found != null) return found;
                }
            }
        } else if (json instanceof List<?> list && !list.isEmpty()) {
            return firstString(list.get(0), keys);
        }
        return null;
    }

    /** Contents of the last plain-text fenced block, or {@code null} when there is none. */
    private static @Nullable String preferredFence(String text) {
        Matcher m = FENCE.matcher(text);
        String chosen = null;
        while (m.find()) {
            String lang = m.group(1).toLowerCase(Locale.ROOT);
            String body = m.group(2);
            if (TEXT_FENCE_LANGS.contains(lang) && !body.isBlank()) chosen = body;
        }
        if (chosen != null) return chosen;
        // Unterminated opening fence wrapping the whole answer.
        String trimmed = text.trim();
        if (trimmed.startsWith("```") && trimmed.indexOf("```", 3) < 0) {
            int nl = trimmed.indexOf('\n');
            if (nl > 0) {
                String lang = trimmed.substring(3, nl).trim().toLowerCase(Locale.ROOT);
                if (TEXT_FENCE_LANGS.contains(lang)) return trimmed.substring(nl + 1);
            }
        }
        return null;
    }

    private static String dropNoiseLines(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            if (isNoise(line)) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static boolean isNoise(String line) {
        for (Pattern p : NOISE_LINES) {
            if (p.matcher(line).matches()) return true;
        }
        return false;
    }

    private static String dropPreambleAndTrailer(String text) {
        List<String> lines = new ArrayList<>(List.of(text.strip().split("\n", -1)));
        while (!lines.isEmpty()) {
            String first = lines.get(0).trim();
            boolean preamble = first.isEmpty()
                || (matchesAny(PREAMBLE, first) && lines.stream().skip(1).anyMatch(l -> !l.isBlank()));
            if (preamble) lines.remove(0);
            else break;
        }
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i - 1).isBlank() && TRAILER.matcher(lines.get(i).trim()).matches()) {
                lines = lines.subList(0, i);
                break;
            }
        }
        return String.join("\n", lines);
    }

    private static boolean matchesAny(List<Pattern> patterns, String s) {
        for (Pattern p : patterns) {
            if (p.matcher(s).matches()) return true;
        }
        return false;
    }

    private static String tidy(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int blanks = 0;
        for (String line : text.split("\n", -1)) {
            String stripped = line.stripTrailing();
            if (stripped.isEmpty()) {
                if (++blanks > 1) continue;
            } else {
                blanks = 0;
            }
            out.append(stripped).append('\n');
        }
        return out.toString().strip();
    }

    /** A single line wrapped in matching quotes or backticks is unwrapped. */
    private static String unquote(String text) {
        if (text.length() < 2 || text.indexOf('\n') >= 0) return text;
        char first = text.charAt(0);
        char last = text.charAt(text.length() - 1);
        if ((first == '"' || first == '\'' || first == '`') && first == last) {
            return text.substring(1, text.length() - 1).trim();
        }
        return text;
    }
}
