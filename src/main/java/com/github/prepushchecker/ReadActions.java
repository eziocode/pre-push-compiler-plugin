package com.github.prepushchecker;

import com.intellij.openapi.application.ReadAction;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Callable;

/** Read-action helper that avoids {@code ReadAction.compute}, deprecated in recent platforms. */
final class ReadActions {
    private ReadActions() {}

    /** Runs {@code computation} in a read action, retrying if a write action interrupts it. Background threads only. */
    static <T> T compute(@NotNull Callable<T> computation) {
        return ReadAction.nonBlocking(computation).executeSynchronously();
    }
}
