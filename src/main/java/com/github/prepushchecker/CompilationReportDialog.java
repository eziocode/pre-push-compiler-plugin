package com.github.prepushchecker;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Function;

final class CompilationReportDialog extends DialogWrapper {

    private static final int DIALOG_WIDTH = 640;
    private static final int DIALOG_HEIGHT = 340;

    private final Project project;
    private final String header;
    private final Function<ProgressIndicator, List<String>> refreshAction;
    @Nullable private final Runnable abortCommitAction;
    private final DefaultListModel<String> listModel = new DefaultListModel<>();

    CompilationReportDialog(
        @NotNull Project project,
        @NotNull String title,
        @NotNull String header,
        @NotNull List<String> initialItems,
        @NotNull Function<ProgressIndicator, List<String>> refreshAction
    ) {
        this(project, title, header, initialItems, refreshAction, null);
    }

    CompilationReportDialog(
        @NotNull Project project,
        @NotNull String title,
        @NotNull String header,
        @NotNull List<String> initialItems,
        @NotNull Function<ProgressIndicator, List<String>> refreshAction,
        @Nullable Runnable abortCommitAction
    ) {
        super(project, true);
        this.project = project;
        this.header = header;
        this.refreshAction = refreshAction;
        this.abortCommitAction = abortCommitAction;
        setTitle(title);
        initialItems.forEach(listModel::addElement);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setPreferredSize(new Dimension(DIALOG_WIDTH, DIALOG_HEIGHT));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

        JBLabel headerLabel = new JBLabel(
            "<html>" + header.replace("\n", "<br>") + "</html>",
            AllIcons.General.Error,
            SwingConstants.LEFT
        );
        headerLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        panel.add(headerLabel, BorderLayout.NORTH);

        JBList<String> list = new JBList<>(listModel);
        list.setCellRenderer(new CompilationEntryRenderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    CompilationEntryRenderer.navigateTo(project, list.getSelectedValue());
                }
            }
        });
        panel.add(new JBScrollPane(list), BorderLayout.CENTER);

        return panel;
    }

    @Override
    protected void createDefaultActions() {
        super.createDefaultActions();
        getCancelAction().putValue(Action.NAME, "Abort Push");
    }

    @Override
    protected Action @NotNull [] createActions() {
        AbstractAction refresh = new AbstractAction("Refresh", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(ActionEvent e) {
                ProgressManager.getInstance().run(new Task.Modal(project, "Rechecking...", false) {
                    private List<String> updated;

                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        updated = refreshAction.apply(indicator);
                    }

                    @Override
                    public void onSuccess() {
                        listModel.clear();
                        if (updated == null || updated.isEmpty()) {
                            close(OK_EXIT_CODE);
                        } else {
                            updated.forEach(listModel::addElement);
                        }
                    }
                });
            }
        };

        if (abortCommitAction == null) {
            return new Action[]{refresh, getCancelAction()};
        }

        AbstractAction abortCommit = new AbstractAction("Abort Commit") {
            @Override
            public void actionPerformed(ActionEvent e) {
                abortCommitAction.run();
                close(CANCEL_EXIT_CODE);
            }
        };
        abortCommit.putValue(Action.SHORT_DESCRIPTION,
            "Soft-reset the commits you were trying to push; changes stay in your working tree.");
        return new Action[]{refresh, abortCommit, getCancelAction()};
    }
}
