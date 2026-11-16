package com.github.prepushchecker.inline;

import java.util.List;

/** What the model sees for one inline suggestion request. */
public record InlineContext(String filePath, String language, String prefix, String suffix, List<Snippet> related) {

    /** A titled excerpt of a related file. */
    public record Snippet(String title, String text) {}
}
