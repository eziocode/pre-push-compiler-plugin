package com.github.prepushchecker;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowContentUiType;
import com.intellij.ui.content.Content;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import java.awt.FontMetrics;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * Shows the tool window's contents as tabs while they fit in the header, and as a dropdown
 * ("Compilation Checker: Code Review ▾") once the window is too narrow. The platform otherwise
 * silently hides the tabs that do not fit, so users never find the other tab.
 */
final class AdaptiveContentTabs {

    /** Extra width before switching back to tabs, so dragging near the edge does not flicker. */
    static final int HYSTERESIS = 24;

    private AdaptiveContentTabs() {
    }

    static void install(@NotNull ToolWindow toolWindow) {
        JComponent component = toolWindow.getComponent();
        ComponentAdapter listener = new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                update(toolWindow);
            }

            @Override
            public void componentShown(ComponentEvent e) {
                update(toolWindow);
            }
        };
        component.addComponentListener(listener);
        Disposable disposable = toolWindow.getDisposable();
        Disposer.register(disposable, () -> component.removeComponentListener(listener));
        ApplicationManager.getApplication().invokeLater(() -> update(toolWindow), ModalityState.any(),
                o -> toolWindow.isDisposed());
    }

    private static void update(@NotNull ToolWindow toolWindow) {
        if (toolWindow.isDisposed()) {
            return;
        }
        JComponent component = toolWindow.getComponent();
        int width = component.getWidth();
        if (width <= 0) {
            return;
        }
        Content[] contents = toolWindow.getContentManager().getContents();
        FontMetrics metrics = component.getFontMetrics(JBUI.Fonts.label());
        int labelsWidth = metrics.stringWidth(toolWindow.getStripeTitle());
        for (Content content : contents) {
            labelsWidth += metrics.stringWidth(content.getDisplayName() == null ? "" : content.getDisplayName());
        }
        int required = requiredWidth(labelsWidth, contents.length);
        ToolWindowContentUiType current = toolWindow.getContentUiType();
        ToolWindowContentUiType wanted = choose(current, width, required, JBUI.scale(HYSTERESIS));
        if (wanted != current) {
            toolWindow.setContentUiType(wanted, null);
        }
    }

    /** Header width needed to show title and every tab label, plus padding and title actions. */
    static int requiredWidth(int labelsWidth, int tabCount) {
        // Each tab has ~12px padding per side; the header keeps ~110px for its own actions
        // (gear, minimize, the plugin's overflow menu) and title insets.
        return labelsWidth + tabCount * JBUI.scale(24) + JBUI.scale(110);
    }

    static @NotNull ToolWindowContentUiType choose(@NotNull ToolWindowContentUiType current,
                                                   int width, int required, int hysteresis) {
        if (current == ToolWindowContentUiType.TABBED) {
            return width < required ? ToolWindowContentUiType.COMBO : ToolWindowContentUiType.TABBED;
        }
        return width >= required + hysteresis ? ToolWindowContentUiType.TABBED : ToolWindowContentUiType.COMBO;
    }
}
