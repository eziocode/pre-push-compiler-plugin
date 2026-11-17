package com.github.prepushchecker.inline;

import com.intellij.util.messages.Topic;

/** Inline suggestion state changed (request started/finished, paused, failed, switched on/off). EDT. */
public interface InlineStatusListener {
    @Topic.AppLevel
    Topic<InlineStatusListener> TOPIC = new Topic<>(InlineStatusListener.class, Topic.BroadcastDirection.NONE);

    void statusChanged();
}
