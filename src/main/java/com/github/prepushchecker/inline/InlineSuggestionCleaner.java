package com.github.prepushchecker.inline;

import com.github.prepushchecker.commitgen.AiOutputSanitizer;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/** Turns raw model output into the text to show as an inline suggestion. Pure. */
public final class InlineSuggestionCleaner {

    private InlineSuggestionCleaner() {}

    /** @return the text to insert; {@code ""} means show nothing */
    public static String clean(@Nullable String raw, String prefix, String suffix, boolean multiLine, int maxLines) {
        String text = AiOutputSanitizer.cleanCompletion(raw);
        if (text.isEmpty()) return "";

        String echoed = lastNonBlankLine(prefix);
        if (!echoed.isEmpty() && text.startsWith(echoed) && text.length() > echoed.length()) {
            text = text.substring(echoed.length());
            if (!prefix.isEmpty() && Character.isWhitespace(prefix.charAt(prefix.length() - 1))) {
                text = stripLeadingHorizontal(text);
            }
        }

        if (!multiLine) {
            int nl = text.indexOf('\n');
            if (nl >= 0) text = text.substring(0, nl);
        } else {
            String[] lines = text.split("\n", -1);
            if (lines.length > maxLines) {
                text = String.join("\n", List.of(lines).subList(0, Math.max(maxLines, 0)));
            }
        }

        String next = firstNonBlankLine(suffix);
        if (!next.isEmpty()) {
            String stripped = stripTrailing(text);
            if (stripped.endsWith(next)) text = stripped.substring(0, stripped.length() - next.length());
        }

        text = stripTrailing(text);
        return text.isBlank() ? "" : text;
    }

    private static String lastNonBlankLine(String s) {
        String[] lines = s.split("\n", -1);
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) return lines[i].trim();
        }
        return "";
    }

    private static String firstNonBlankLine(String s) {
        for (String line : s.split("\n", -1)) {
            if (!line.isBlank()) return line.trim();
        }
        return "";
    }

    private static String stripLeadingHorizontal(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return s.substring(i);
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }
}
