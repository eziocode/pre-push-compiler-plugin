package com.github.prepushchecker.inline;

import com.github.prepushchecker.commitgen.AiProviderConfigurable;
import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.LayeredIcon;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Inline suggestion state in the status bar: a spinner while a suggestion is generated, the idle
 * icon otherwise, greyed out when switched off, with an error badge when paused or failing.
 * Clicking opens the related actions.
 * <p>
 * Java, not Kotlin: a Kotlin class gets a bridge for the deprecated default
 * {@code getPresentation(PlatformType)}, which the Plugin Verifier reports.
 */
public final class InlineStatusBarWidget implements StatusBarWidget, StatusBarWidget.IconPresentation {
    public static final String ID = "PrePushInlineSuggest";

    private static final Icon IDLE = IconLoader.getIcon("/icons/inlineSuggest.svg", InlineStatusBarWidget.class);
    private static final Icon DISABLED = IconLoader.getDisabledIcon(IDLE);
    private static final Icon FAILING = LayeredIcon.layeredIcon(new Icon[] {IDLE, AllIcons.Nodes.ErrorMark});

    private final Project project;
    private @Nullable StatusBar statusBar;

    public InlineStatusBarWidget(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @NotNull String ID() {
        return ID;
    }

    @Override
    public @NotNull WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
        ApplicationManager.getApplication().getMessageBus().connect(this)
            .subscribe(InlineStatusListener.TOPIC, () -> {
                StatusBar bar = this.statusBar;
                if (bar != null) bar.updateWidget(ID);
            });
    }

    @Override
    public void dispose() {
        statusBar = null;
    }

    private static InlineSuggestSettings.State settings() {
        return InlineSuggestSettings.getInstance().settings();
    }

    private static @Nullable InlineSuggestService serviceIfCreated() {
        return ApplicationManager.getApplication().getServiceIfCreated(InlineSuggestService.class);
    }

    private static InlineSuggestService.Status status() {
        if (!settings().enabled) return InlineSuggestService.Status.DISABLED;
        InlineSuggestService service = serviceIfCreated();
        return service == null ? InlineSuggestService.Status.IDLE : service.status();
    }

    @Override
    public @NotNull Icon getIcon() {
        return switch (status()) {
            case LOADING -> AnimatedIcon.Default.INSTANCE;
            case DISABLED -> DISABLED;
            case PAUSED, ERROR -> FAILING;
            case IDLE -> IDLE;
        };
    }

    @Override
    public @NotNull String getTooltipText() {
        String state = switch (status()) {
            case LOADING -> "generating a suggestion…";
            case DISABLED -> "off";
            case PAUSED -> "paused after repeated failures";
            case ERROR -> "last request failed";
            case IDLE -> "ready";
        };
        InlineSuggestService service = serviceIfCreated();
        String error = service == null ? null : service.lastError();
        String firstLine = error == null ? "" : error.lines().findFirst().orElse("");
        return "Inline suggestions: " + state + " · " + providerText() + (firstLine.isBlank() ? "" : "\n" + firstLine);
    }

    @Override
    public @NotNull Consumer<MouseEvent> getClickConsumer() {
        return e -> showPopup(e.getComponent());
    }

    // ── popup ────────────────────────────────────────────────────────────────

    private void showPopup(@NotNull Component component) {
        ListPopup popup = JBPopupFactory.getInstance().createActionGroupPopup(
            "Inline Suggestions", buildGroup(), DataManager.getInstance().getDataContext(component),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true);
        Dimension size = popup.getContent().getPreferredSize();
        popup.show(new RelativePoint(component, new Point(component.getWidth() - size.width, -size.height)));
    }

    private DefaultActionGroup buildGroup() {
        InlineSuggestSettings.State s = settings();
        InlineSuggestService service = serviceIfCreated();
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(info(providerText()));
        group.add(Separator.getInstance());
        group.add(action(s.enabled ? "Disable Inline Suggestions" : "Enable Inline Suggestions", () -> {
            s.enabled = !s.enabled;
            InlineSuggestService.getInstance().settingsChanged();
            if (!s.enabled) {
                for (Editor editor : EditorFactory.getInstance().getAllEditors()) InlineGhostText.dismiss(editor);
            }
        }));
        if (s.enabled && service != null && (service.status() == InlineSuggestService.Status.PAUSED
            || service.status() == InlineSuggestService.Status.ERROR)) {
            group.add(action("Resume Suggestions", service::settingsChanged));
        }
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (s.enabled && editor != null) {
            group.add(action("Suggest Now (" + InlineShortcuts.suggest() + ")", () -> InlineSuggestService.getInstance().requestNow(editor)));
        }
        VirtualFile file = editor == null ? null : editor.getVirtualFile();
        String ext = file == null ? null : file.getExtension();
        if (ext != null && !ext.isBlank()) {
            boolean off = InlineSuggestSettings.isExtensionDisabled(s, ext);
            group.add(action((off ? "Enable" : "Disable") + " for *." + ext + " Files", () -> {
                s.disabledExtensions = toggleExtension(s.disabledExtensions, ext, !off);
                InlineSuggestService.getInstance().settingsChanged();
            }));
        }
        String error = service == null ? null : service.lastError();
        if (error != null && !error.isBlank()) {
            group.add(action("Show Last Error…", () -> Messages.showErrorDialog(project, error, "Inline Suggestion Error")));
        }
        group.add(Separator.getInstance());
        group.add(action("Inline Suggestions Settings…", () ->
            ShowSettingsUtil.getInstance().showSettingsDialog(project, InlineSuggestConfigurable.class)));
        group.add(action("AI Provider Settings…", () ->
            ShowSettingsUtil.getInstance().showSettingsDialog(project, AiProviderConfigurable.class)));
        return group;
    }

    private static String providerText() {
        InlineSuggestSettings.State s = settings();
        CommitMessageProvider.Id id = InlineSuggestSettings.providerOf(s);
        String model = s.model == null || s.model.isBlank() ? "default model" : s.model.trim();
        return id.displayName() + " · " + model;
    }

    private static AnAction action(String text, Runnable run) {
        return new DumbAwareAction(text) {
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
            @Override public void actionPerformed(@NotNull AnActionEvent e) { run.run(); }
        };
    }

    private static AnAction info(String text) {
        return new DumbAwareAction(text) {
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
            @Override public void update(@NotNull AnActionEvent e) { e.getPresentation().setEnabled(false); }
            @Override public void actionPerformed(@NotNull AnActionEvent e) { }
        };
    }

    /** Adds or removes {@code ext} in a comma-separated extension list. */
    static @NotNull String toggleExtension(@Nullable String list, @NotNull String ext, boolean disable) {
        String want = normalize(ext);
        Set<String> parts = new LinkedHashSet<>();
        if (list != null) {
            for (String p : list.split(",")) {
                String n = normalize(p);
                if (!n.isEmpty()) parts.add(n);
            }
        }
        if (disable) parts.add(want); else parts.remove(want);
        return String.join(", ", new ArrayList<>(parts));
    }

    private static String normalize(String e) {
        String t = e.trim().toLowerCase(Locale.ROOT);
        return t.startsWith(".") ? t.substring(1) : t;
    }
}
