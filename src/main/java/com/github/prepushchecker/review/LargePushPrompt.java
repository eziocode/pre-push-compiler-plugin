package com.github.prepushchecker.review;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.AppIcon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Asks whether an on-push AI review of a large or merge-containing push should run. The push
 * may come from a terminal, so the question has a timeout with a configurable default answer.
 */
public final class LargePushPrompt {

    public enum Answer { REVIEW, SKIP, ABORT, TIMEOUT }

    /** Seam for tests. */
    public interface Asker {
        @NotNull Answer ask(@NotNull Project project, @NotNull List<String> files, boolean mergeUnsure, int timeoutSeconds);
    }

    private LargePushPrompt() {}

    public static @NotNull Asker dialog() {
        return LargePushPrompt::askWithDialog;
    }

    static @NotNull Answer askWithDialog(@NotNull Project project, @NotNull List<String> files, boolean mergeUnsure,
                                         int timeoutSeconds) {
        CompletableFuture<Answer> result = new CompletableFuture<>();
        DialogWrapper[] holder = new DialogWrapper[1];
        // Pure UI: no model change here, so ModalityState.any() is fine (see jetbrains-api-compat rules).
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                result.complete(Answer.ABORT);
                return;
            }
            Dialog d = new Dialog(project, files, mergeUnsure, timeoutSeconds, result);
            holder[0] = d;
            AppIcon.getInstance().requestAttention(project, true);
            d.show();
            result.complete(d.answer == null ? Answer.ABORT : d.answer);
        }, ModalityState.any());
        try {
            return result.get(timeoutSeconds + 5L, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            DialogWrapper d = holder[0];
            if (d != null) ApplicationManager.getApplication().invokeLater(() -> d.close(DialogWrapper.CANCEL_EXIT_CODE), ModalityState.any());
            return Answer.TIMEOUT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Answer.ABORT;
        } catch (java.util.concurrent.ExecutionException e) {
            return Answer.ABORT;
        }
    }

    private static final class Dialog extends DialogWrapper {
        private final List<String> files;
        private final boolean mergeUnsure;
        private final int timeoutSeconds;
        private final CompletableFuture<Answer> result;
        volatile @Nullable Answer answer;
        private Timer timer;
        private int left;
        private final JLabel countdown = new JLabel();

        Dialog(Project project, List<String> files, boolean mergeUnsure, int timeoutSeconds,
               CompletableFuture<Answer> result) {
            super(project, false);
            this.files = files;
            this.mergeUnsure = mergeUnsure;
            this.timeoutSeconds = timeoutSeconds;
            this.result = result;
            this.left = timeoutSeconds;
            setTitle("Pre-Push AI Review");
            setOKButtonText("Review Now");
            init();
            timer = new Timer(1000, e -> {
                left--;
                countdown.setText("Waiting for your answer: " + Math.max(left, 0) + "s");
                if (left <= 0) {
                    timer.stop();
                    close(DialogWrapper.CANCEL_EXIT_CODE);
                }
            });
            countdown.setText("Waiting for your answer: " + left + "s");
            timer.start();
        }

        @Override
        protected @NotNull JComponent createCenterPanel() {
            JPanel p = new JPanel(new BorderLayout(0, 8));
            StringBuilder head = new StringBuilder("<html>");
            head.append("This push has <b>").append(files.size()).append(" file(s)</b> that need an AI review.");
            if (mergeUnsure) {
                head.append("<br>It contains a merge, and merged-in code could not be told apart from your own, "
                    + "so it may include files you did not write.");
            }
            head.append("<br>Reviewing many files can take several minutes.</html>");
            p.add(new JLabel(head.toString()), BorderLayout.NORTH);
            JBTextArea list = new JBTextArea(String.join("\n", files.stream().limit(15).toList())
                + (files.size() > 15 ? "\n… and " + (files.size() - 15) + " more" : ""));
            list.setEditable(false);
            JBScrollPane sp = new JBScrollPane(list);
            sp.setPreferredSize(new Dimension(520, 200));
            p.add(sp, BorderLayout.CENTER);
            p.add(countdown, BorderLayout.SOUTH);
            return p;
        }

        @Override
        protected Action @NotNull [] createActions() {
            Action skip = new AbstractAction("Skip AI Review") {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    answer = Answer.SKIP;
                    close(NEXT_USER_EXIT_CODE);
                }
            };
            Action abort = new AbstractAction("Abort Push") {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    answer = Answer.ABORT;
                    close(CANCEL_EXIT_CODE);
                }
            };
            return new Action[]{getOKAction(), skip, abort};
        }

        @Override
        protected void doOKAction() {
            answer = Answer.REVIEW;
            super.doOKAction();
        }

        @Override
        public void doCancelAction() {
            // Closed by the countdown (answer still null) or the window X: both are "no answer".
            if (answer == null) answer = left <= 0 ? Answer.TIMEOUT : Answer.ABORT;
            super.doCancelAction();
        }

        @Override
        protected void dispose() {
            if (timer != null) timer.stop();
            if (answer == null) answer = Answer.TIMEOUT;
            result.complete(answer);
            super.dispose();
        }
    }
}
