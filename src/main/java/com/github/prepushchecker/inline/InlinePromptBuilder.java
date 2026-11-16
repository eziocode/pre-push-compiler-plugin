package com.github.prepushchecker.inline;

/** Builds the system and user prompts of an inline completion request. Pure. */
public final class InlinePromptBuilder {

    public static final String CURSOR = "<CURSOR>";

    public record Prompt(String system, String user) {}

    private InlinePromptBuilder() {}

    public static Prompt build(InlineContext ctx, InlineSuggestSettings.State s) {
        StringBuilder sys = new StringBuilder();
        sys.append("You are a code completion engine. Reply with only the text to insert at ")
            .append(CURSOR).append(". ")
            .append("No explanation, no code fences, and do not repeat any text before or after the cursor. ");
        if (s.multiLine) {
            sys.append("Write at most ").append(s.maxLines).append(" lines.");
        } else {
            sys.append("Write one line only.");
        }
        if (s.extraInstructions != null && !s.extraInstructions.isBlank()) {
            sys.append("\n").append(s.extraInstructions.strip());
        }

        StringBuilder user = new StringBuilder();
        user.append("File: ").append(ctx.filePath()).append(" (").append(ctx.language()).append(")\n");
        if (ctx.related() != null) {
            for (InlineContext.Snippet sn : ctx.related()) {
                user.append("\n--- ").append(sn.title()).append(" ---\n").append(sn.text()).append('\n');
            }
        }
        user.append("\n--- current file ---\n").append(ctx.prefix()).append(CURSOR).append(ctx.suffix());
        return new Prompt(sys.toString(), user.toString());
    }
}
