package com.github.prepushchecker;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.gotoByName.ChooseByNamePopup;
import com.intellij.ide.util.gotoByName.GotoFileModel;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.ui.CheckBoxList;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Ignored-files list for the settings page: files whose compile errors do not block pushes.
 * Changes are saved immediately, like the tool window's "Ignore File" action.
 */
public final class IgnoredFilesEditor {

    private final Project project;
    private final CheckBoxList<String> list = new IgnoredFilesList();
    private final JComponent component;

    public IgnoredFilesEditor(@NotNull Project project) {
        this.project = project;
        list.getEmptyText().setText("No ignored files");
        list.getEmptyText().appendLine("Add a file, or right-click an error → Ignore File");
        list.setCheckBoxListListener((index, value) -> {
            String path = list.getItemAt(index);
            if (path != null) IgnoredCompilationFiles.getInstance(project).setEnabled(path, value);
        });
        refresh();

        AnAction search = new AnAction("Search Project Files…",
                "Find a project file by name to ignore", AllIcons.Actions.Search) {
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                openSearchPopup();
            }
        };

        component = ToolbarDecorator.createDecorator(list)
            .setAddAction(button -> FileChooser.chooseFiles(
                projectScopedDescriptor(), project, projectRootForDialog(),
                files -> {
                    for (var file : files) add(file.getPath());
                }))
            .setAddActionName("Add Files…")
            .setRemoveAction(button -> {
                for (int index : list.getSelectedIndices()) {
                    String path = list.getItemAt(index);
                    if (path != null) IgnoredCompilationFiles.getInstance(project).remove(path);
                }
                refresh();
            })
            .addExtraAction(search)
            .disableUpDownActions()
            .setPreferredSize(new Dimension(JBUI.scale(420), JBUI.scale(120)))
            .createPanel();
    }

    public @NotNull JComponent getComponent() {
        return component;
    }

    public void refresh() {
        list.clear();
        for (IgnoredCompilationFiles.Entry entry : IgnoredCompilationFiles.getInstance(project).list()) {
            list.addItem(entry.path(), fileName(entry.path()), entry.enabled());
        }
    }

    /** Adds {@code path}, or explains why it cannot be ignored. */
    static void addOrWarn(@NotNull Project project, @NotNull String path) {
        if (!IgnoredCompilationFiles.getInstance(project).add(path)) {
            Messages.showWarningDialog(project,
                "\"" + path + "\" cannot be ignored — it must be a .java/.kt/.kts/.groovy/.scala "
                    + "file inside the project.",
                "Cannot Ignore File");
        }
    }

    private void add(String path) {
        addOrWarn(project, path);
        refresh();
    }

    private FileChooserDescriptor projectScopedDescriptor() {
        List<VirtualFile> roots = new ArrayList<>();
        LocalFileSystem lfs = LocalFileSystem.getInstance();
        for (String root : DiagnosticPathMatcher.roots(project)) {
            VirtualFile vf = lfs.findFileByPath(root);
            if (vf != null) roots.add(vf);
        }
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory
            .createMultipleFilesNoJarsDescriptor()
            .withTreeRootVisible(false)
            .withTitle("Select Project Files to Ignore")
            .withDescription("Compiler errors attributed to these files will not block pushes")
            .withFileFilter(vf -> vf.isDirectory() || PushValidationPaths.isCompilableSource(vf.getPath()));
        return roots.isEmpty() ? descriptor : descriptor.withRoots(roots);
    }

    private @Nullable VirtualFile projectRootForDialog() {
        String basePath = project.getBasePath();
        return basePath == null ? null : LocalFileSystem.getInstance().findFileByPath(basePath);
    }

    private void openSearchPopup() {
        ChooseByNamePopup.createPopup(
            project, new GotoFileModel(project), (com.intellij.psi.PsiElement) null).invoke(
            new ChooseByNamePopup.Callback() {
                @Override
                public void onClose() {}

                @Override
                public void elementChosen(Object element) {
                    VirtualFile file = element instanceof PsiFile psiFile
                        ? psiFile.getVirtualFile()
                        : element instanceof VirtualFile vf ? vf : null;
                    if (file != null) add(file.getPath());
                }
            },
            ModalityState.current(), false);
    }

    private static String fileName(String path) {
        int slash = path.replace('\\', '/').lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static @Nullable String parentDir(String path) {
        int slash = path.replace('\\', '/').lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : null;
    }

    /** File name as the label, parent folder dimmed, full path on hover. */
    private static final class IgnoredFilesList extends CheckBoxList<String> {
        @Override
        protected @Nullable String getSecondaryText(int index) {
            String path = getItemAt(index);
            return path == null ? null : parentDir(path);
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            int index = locationToIndex(event.getPoint());
            String path = index >= 0 ? getItemAt(index) : null;
            return path == null ? null
                : path + " — checked means compiler errors from this file are ignored";
        }
    }
}
