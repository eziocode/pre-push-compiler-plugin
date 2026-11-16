package com.github.prepushchecker.inline;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders one inline AI suggestion per editor as grey italic "ghost text": the first line as an
 * inline inlay at the suggestion offset, any further lines as one block inlay below that line.
 * All methods are EDT only; state lives in the editor's user data.
 */
public final class InlineGhostText {

    static final String ACCEPT_COMMAND_NAME = "Accept Inline Suggestion";

    private static final Key<State> KEY = Key.create("prepushchecker.inline.ghostText");

    private InlineGhostText() {
    }

    private static final class State {
        final int offset;
        final String text;
        final List<Inlay<?>> inlays;

        State(int offset, String text, List<Inlay<?>> inlays) {
            this.offset = offset;
            this.text = text;
            this.inlays = inlays;
        }
    }

    public static void show(@NotNull Editor editor, int offset, @Nullable String text) {
        dismiss(editor);
        if (editor.isDisposed() || text == null || text.isBlank()) {
            return;
        }
        if (offset < 0) {
            return;
        }
        // The logical offset is kept as given; the inlays are clamped so a caller that updates
        // state before the document catches up cannot make the inlay model throw.
        int inlayOffset = Math.min(offset, editor.getDocument().getTextLength());
        int newline = text.indexOf('\n');
        String firstLine = newline < 0 ? text : text.substring(0, newline);
        String rest = newline < 0 ? "" : text.substring(newline + 1);

        List<Inlay<?>> inlays = new ArrayList<>(2);
        if (!firstLine.isEmpty()) {
            Inlay<?> inline = editor.getInlayModel()
                .addInlineElement(inlayOffset, true, new GhostRenderer(List.of(firstLine)));
            if (inline != null) {
                inlays.add(inline);
            }
        }
        if (newline >= 0) {
            Inlay<?> block = editor.getInlayModel()
                .addBlockElement(inlayOffset, true, false, 0, new GhostRenderer(List.of(rest.split("\n", -1))));
            if (block != null) {
                inlays.add(block);
            }
        }
        editor.putUserData(KEY, new State(offset, text, inlays));
    }

    public static boolean isShowing(@NotNull Editor editor) {
        return state(editor) != null;
    }

    public static @Nullable String current(@NotNull Editor editor) {
        State state = state(editor);
        return state == null ? null : state.text;
    }

    public static int offset(@NotNull Editor editor) {
        State state = state(editor);
        return state == null ? -1 : state.offset;
    }

    public static void dismiss(@NotNull Editor editor) {
        State state = editor.getUserData(KEY);
        if (state == null) {
            return;
        }
        editor.putUserData(KEY, null);
        for (Inlay<?> inlay : state.inlays) {
            if (inlay.isValid()) {
                Disposer.dispose(inlay);
            }
        }
    }

    public static boolean typeThrough(@NotNull Editor editor, int insertOffset, @NotNull CharSequence inserted) {
        State state = state(editor);
        if (state == null || insertOffset != state.offset || inserted.length() == 0) {
            return false;
        }
        String typed = inserted.toString();
        if (state.text.equals(typed)) {
            dismiss(editor);
            return true;
        }
        if (state.text.length() > typed.length() && state.text.startsWith(typed)) {
            show(editor, insertOffset + typed.length(), state.text.substring(typed.length()));
            return true;
        }
        return false;
    }

    public static boolean accept(@NotNull Editor editor) {
        State state = state(editor);
        if (state == null) {
            return false;
        }
        dismiss(editor);
        int end = state.offset + state.text.length();
        WriteCommandAction.writeCommandAction(editor.getProject())
            .withName(ACCEPT_COMMAND_NAME)
            .run(() -> {
                editor.getDocument().insertString(state.offset, state.text);
                editor.getCaretModel().moveToOffset(end);
            });
        return true;
    }

    private static @Nullable State state(@NotNull Editor editor) {
        if (editor.isDisposed()) {
            return null;
        }
        return editor.getUserData(KEY);
    }

    /** Paints one or more lines of ghost text in the editor font, italic, inlay-text colour. */
    private static final class GhostRenderer implements EditorCustomElementRenderer {
        private final List<String> lines;

        GhostRenderer(List<String> lines) {
            this.lines = lines;
        }

        @Override
        public int calcWidthInPixels(@NotNull Inlay inlay) {
            Editor editor = inlay.getEditor();
            FontMetrics metrics = editor.getContentComponent().getFontMetrics(font(editor));
            int width = 0;
            for (String line : lines) {
                width = Math.max(width, metrics.stringWidth(expandTabs(editor, line)));
            }
            return Math.max(1, width);
        }

        @Override
        public int calcHeightInPixels(@NotNull Inlay inlay) {
            return inlay.getEditor().getLineHeight() * Math.max(1, lines.size());
        }

        @Override
        public void paint(@NotNull Inlay inlay, @NotNull Graphics g, @NotNull Rectangle targetRegion,
                          @NotNull TextAttributes textAttributes) {
            Editor editor = inlay.getEditor();
            g.setFont(font(editor));
            g.setColor(foreground(editor.getColorsScheme()));
            int lineHeight = editor.getLineHeight();
            int ascent = editor.getAscent();
            for (int i = 0; i < lines.size(); i++) {
                g.drawString(expandTabs(editor, lines.get(i)), targetRegion.x, targetRegion.y + i * lineHeight + ascent);
            }
        }

        private static Font font(Editor editor) {
            return editor.getColorsScheme().getFont(EditorFontType.ITALIC);
        }

        private static Color foreground(EditorColorsScheme scheme) {
            TextAttributes attributes = scheme.getAttributes(DefaultLanguageHighlighterColors.INLAY_TEXT_WITHOUT_BACKGROUND);
            Color color = attributes == null ? null : attributes.getForegroundColor();
            return color != null ? color : JBColor.GRAY;
        }

        private static String expandTabs(Editor editor, String line) {
            if (line.indexOf('\t') < 0) {
                return line;
            }
            int tabSize = Math.max(1, editor.getSettings().getTabSize(editor.getProject()));
            return line.replace("\t", " ".repeat(tabSize));
        }
    }
}
