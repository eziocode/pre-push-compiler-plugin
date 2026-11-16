package com.github.prepushchecker.inline;

import com.github.prepushchecker.ReadActions;
import com.github.prepushchecker.commitgen.SensitivePaths;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Gathers the text around the caret plus related project knowledge for one inline suggestion. */
public final class InlineContextCollector {
    static final int PREFIX_CHARS = 4000;
    static final int SUFFIX_CHARS = 1000;
    static final int OPEN_FILE_LINES = 60;

    private InlineContextCollector() {}

    /** Background thread only. Returns {@code null} for sensitive files or an out-of-range offset. */
    public static @Nullable InlineContext collect(@NotNull Project project, @NotNull VirtualFile file,
                                                  @NotNull Document document, int offset,
                                                  @NotNull InlineSuggestSettings.State s) {
        return ReadActions.compute(() -> collectInReadAction(project, file, document, offset, s));
    }

    private static @Nullable InlineContext collectInReadAction(Project project, VirtualFile file, Document document,
                                                               int offset, InlineSuggestSettings.State s) {
        int length = document.getTextLength();
        if (offset < 0 || offset > length) return null;
        if (SensitivePaths.isExcluded(file.getPath())) return null;

        String text = document.getText();
        String prefix = text.substring(Math.max(0, offset - PREFIX_CHARS), offset);
        String suffix = text.substring(offset, Math.min(length, offset + SUFFIX_CHARS));

        PsiFile psi = PsiManager.getInstance(project).findFile(file);
        String language = psi != null ? psi.getLanguage().getID() : file.getFileType().getName();

        List<InlineContext.Snippet> related = new ArrayList<>();
        if (s.useProjectContext) {
            int[] remaining = {s.contextChars};
            if (psi instanceof PsiJavaFile && !DumbService.isDumb(project)) {
                addJavaSignatures(project, (PsiJavaFile) psi, offset, related, remaining);
            }
            addOpenFiles(project, file, related, remaining);
        }
        return new InlineContext(relativePath(project, file), language, prefix, suffix, related);
    }

    private static String relativePath(Project project, VirtualFile file) {
        String base = project.getBasePath();
        String path = file.getPath();
        if (base != null && path.startsWith(base + "/")) return path.substring(base.length() + 1);
        return file.getName();
    }

    private static boolean tryAdd(List<InlineContext.Snippet> out, int[] remaining, String title, String text) {
        if (text.length() > remaining[0]) return false;
        remaining[0] -= text.length();
        out.add(new InlineContext.Snippet(title, text));
        return true;
    }

    private static void addJavaSignatures(Project project, PsiJavaFile psi, int offset,
                                          List<InlineContext.Snippet> out, int[] remaining) {
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        Map<PsiClass, Integer> distance = new HashMap<>();
        try {
            for (PsiJavaCodeReferenceElement ref : PsiTreeUtil.collectElementsOfType(psi, PsiJavaCodeReferenceElement.class)) {
                ProgressManager.checkCanceled();
                PsiElement target = ref.resolve();
                if (!(target instanceof PsiClass) || target instanceof PsiTypeParameter) continue;
                PsiClass cls = (PsiClass) target;
                if (cls.getQualifiedName() == null) continue;
                PsiFile cf = cls.getContainingFile();
                if (cf == null || cf.equals(psi)) continue;
                VirtualFile vf = cf.getVirtualFile();
                if (vf == null || !index.isInSourceContent(vf)) continue;
                if (SensitivePaths.isExcluded(vf.getPath())) continue;
                int d = Math.abs(ref.getTextRange().getStartOffset() - offset);
                distance.merge(cls, d, Math::min);
            }
        } catch (IndexNotReadyException e) {
            // Switched to dumb mode mid-collection: keep whatever resolved so far.
        }
        List<Map.Entry<PsiClass, Integer>> ordered = new ArrayList<>(distance.entrySet());
        ordered.sort(Map.Entry.comparingByValue());
        for (Map.Entry<PsiClass, Integer> e : ordered) {
            ProgressManager.checkCanceled();
            if (remaining[0] <= 0) return;
            try {
                tryAdd(out, remaining, e.getKey().getQualifiedName(), signatures(e.getKey()));
            } catch (IndexNotReadyException ex) {
                return;
            }
        }
    }

    private static String signatures(PsiClass cls) {
        StringBuilder sb = new StringBuilder(header(cls)).append(" {\n");
        for (PsiField f : cls.getFields()) {
            if (!f.isPhysical()) continue; // implicit (e.g. record component) fields duplicate the header
            String mods = f.getModifierList() == null ? "" : collapse(f.getModifierList().getText());
            sb.append("  ").append(mods.isEmpty() ? "" : mods + " ")
                .append(f.getType().getPresentableText()).append(' ').append(f.getName()).append(";\n");
        }
        for (PsiMethod m : cls.getMethods()) {
            if (!m.isPhysical() || m.hasModifierProperty(PsiModifier.PRIVATE)) continue;
            StringBuilder line = new StringBuilder("  ");
            if (m.getModifierList().getText().length() > 0) line.append(collapse(m.getModifierList().getText())).append(' ');
            if (m.getReturnType() != null) line.append(m.getReturnType().getPresentableText()).append(' ');
            line.append(m.getName()).append(collapse(m.getParameterList().getText()));
            if (m.getThrowsList().getText().length() > 0) line.append(' ').append(collapse(m.getThrowsList().getText()));
            sb.append(line).append(";\n");
        }
        return sb.append("}").toString();
    }

    private static String header(PsiClass cls) {
        StringBuilder sb = new StringBuilder();
        if (cls.getModifierList() != null && !cls.getModifierList().getText().isEmpty()) {
            sb.append(collapse(cls.getModifierList().getText())).append(' ');
        }
        sb.append(cls.isInterface() ? (cls.isAnnotationType() ? "@interface " : "interface ")
            : cls.isEnum() ? "enum " : cls.isRecord() ? "record " : "class ");
        sb.append(cls.getName());
        if (cls.getTypeParameterList() != null) sb.append(collapse(cls.getTypeParameterList().getText()));
        if (cls.getRecordHeader() != null) sb.append(collapse(cls.getRecordHeader().getText()));
        if (cls.getExtendsList() != null && cls.getExtendsList().getTextLength() > 0) {
            sb.append(' ').append(collapse(cls.getExtendsList().getText()));
        }
        if (cls.getImplementsList() != null && cls.getImplementsList().getTextLength() > 0) {
            sb.append(' ').append(collapse(cls.getImplementsList().getText()));
        }
        return sb.toString();
    }

    private static String collapse(@Nullable String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    private static void addOpenFiles(Project project, VirtualFile current, List<InlineContext.Snippet> out,
                                     int[] remaining) {
        for (VirtualFile open : FileEditorManager.getInstance(project).getOpenFiles()) {
            ProgressManager.checkCanceled();
            if (remaining[0] <= 0) return;
            if (open.equals(current) || !open.isValid() || open.isDirectory()) continue;
            if (!open.getFileType().equals(current.getFileType())) continue;
            if (SensitivePaths.isExcluded(open.getPath())) continue;
            Document doc = FileDocumentManager.getInstance().getDocument(open);
            if (doc == null) continue;
            int lines = Math.min(OPEN_FILE_LINES, doc.getLineCount());
            if (lines == 0) continue;
            String text = doc.getText().substring(0, doc.getLineEndOffset(lines - 1));
            tryAdd(out, remaining, open.getName(), text);
        }
    }
}
