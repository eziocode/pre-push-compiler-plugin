package com.github.prepushchecker;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Shows what the push check did: this project, all projects on this machine, and recent pushes. */
final class PushStatsDialog extends DialogWrapper {
    private final Project project;
    private final String projectPath;
    private final DefaultTableModel counterModel = new DefaultTableModel(
        new Object[] {"", "This project", "All projects"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final DefaultTableModel recentModel = new DefaultTableModel(
        new Object[] {"When", "Project", "Result", "Kinds", "Source", "Branch"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };

    PushStatsDialog(@NotNull Project project) {
        super(project);
        this.project = project;
        this.projectPath = project.getBasePath();
        setTitle("Push Stats");
        setOKButtonText("Close");
        init();
        refresh();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        panel.setPreferredSize(JBUI.size(640, 460));

        JBLabel note = new JBLabel("<html>Counts pushes this plugin checked, on this machine only. "
            + "Manual and background builds, and bypassed pushes, are not counted.</html>");
        note.setForeground(com.intellij.util.ui.UIUtil.getContextHelpForeground());
        panel.add(note, BorderLayout.NORTH);

        JBTable counters = new JBTable(counterModel);
        counters.setShowGrid(false);
        JBTable recent = new JBTable(recentModel);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            new JBScrollPane(counters), new JBScrollPane(recent));
        split.setResizeWeight(0.55);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[] {new CopyAction(), new ResetAction(), getOKAction()};
    }

    private void refresh() {
        PushStats stats = PushStats.getInstance();
        PushStats.Counters mine = stats.forProject(projectPath);
        PushStats.Counters all = stats.totals();

        counterModel.setRowCount(0);
        for (String[] row : rows(mine, all)) counterModel.addRow(row);

        recentModel.setRowCount(0);
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM);
        for (PushStats.Event event : stats.recent()) {
            recentModel.addRow(new Object[] {format.format(new Date(event.at)), event.project,
                event.result, event.kinds, event.source, event.branch});
        }
    }

    static @NotNull String[][] rows(@NotNull PushStats.Counters mine, @NotNull PushStats.Counters all) {
        return new String[][] {
            row("Pushes checked", mine.checks(), all.checks()),
            row("Allowed", mine.allowed, all.allowed),
            row("Blocked", mine.blocked, all.blocked),
            row("  compile errors", mine.blockedCompile, all.blockedCompile),
            row("  layer violations", mine.blockedLayer, all.blockedLayer),
            row("  forbidden jars", mine.blockedJar, all.blockedJar),
            row("  symbolic A/B", mine.blockedSymbolic, all.blockedSymbolic),
            row("Fixed after a block", mine.fixedAfterBlock, all.fixedAfterBlock),
            row("Answered from cache", mine.cacheHits, all.cacheHits),
            row("Checked without the IDE", mine.externalVerdicts, all.externalVerdicts),
            row("Timeouts", mine.timeouts, all.timeouts),
            row("Changed during check", mine.stale, all.stale),
            row("Infrastructure failures", mine.infrastructure, all.infrastructure),
            row("Bypass switched on", mine.bypassActivations, all.bypassActivations),
        };
    }

    private static String[] row(String label, long mine, long all) {
        return new String[] {label, String.valueOf(mine), String.valueOf(all)};
    }

    static @NotNull String asText(@NotNull String projectName, @NotNull PushStats.Counters mine,
                                  @NotNull PushStats.Counters all) {
        StringBuilder text = new StringBuilder("Push Stats\n");
        text.append(String.format("%-28s %-14s %s%n", "", projectName, "All projects"));
        for (String[] row : rows(mine, all)) {
            text.append(String.format("%-28s %-14s %s%n", row[0], row[1], row[2]));
        }
        return text.toString();
    }

    private final class CopyAction extends DialogWrapperAction {
        CopyAction() {
            super("Copy as Text");
        }

        @Override
        protected void doAction(java.awt.event.ActionEvent e) {
            PushStats stats = PushStats.getInstance();
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(
                asText(project.getName(), stats.forProject(projectPath), stats.totals())), null);
        }
    }

    private final class ResetAction extends DialogWrapperAction {
        ResetAction() {
            super("Reset…");
        }

        @Override
        protected void doAction(java.awt.event.ActionEvent e) {
            int choice = Messages.showDialog(project,
                "Clear the counters for this project, or for every project on this machine?",
                "Reset Push Stats", new String[] {"This Project", "All Projects", "Cancel"}, 2,
                Messages.getWarningIcon());
            if (choice == 0 && projectPath != null) PushStats.getInstance().reset(projectPath);
            else if (choice == 1) PushStats.getInstance().reset(null);
            else return;
            refresh();
        }
    }
}
