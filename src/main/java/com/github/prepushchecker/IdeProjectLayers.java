package com.github.prepushchecker;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Reads IntelliJ's module structure and library jars, as repo-relative paths, for the Layers dialog. */
final class IdeProjectLayers {

    /** One IntelliJ module: non-test source roots and the jars it depends on directly. */
    record ModuleInfo(
        @NotNull String name,
        @NotNull List<String> sourceRoots,
        @NotNull List<String> jars
    ) {
    }

    /** {@code modules} are in dependency order (dependencies first). */
    record Snapshot(
        @NotNull List<ModuleInfo> modules,
        @NotNull Set<String> jars,
        int jarsOutsideRepository
    ) {
    }

    private IdeProjectLayers() {
    }

    static @NotNull Snapshot read(@NotNull Project project, @NotNull String repositoryRoot) {
        return ReadActions.compute(() -> readInReadAction(project, repositoryRoot));
    }

    private static Snapshot readInReadAction(Project project, String repositoryRoot) {
        Set<String> allJars = new TreeSet<>();
        Set<String> outside = new HashSet<>();
        List<ModuleInfo> infos = new ArrayList<>();
        for (Module module : dependencyOrder(ModuleManager.getInstance(project).getModules())) {
            List<String> roots = new ArrayList<>();
            for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots(false)) {
                String relative = DiagnosticPathMatcher.relativeToRoot(root.getPath(), repositoryRoot);
                if (relative != null && !roots.contains(relative)) roots.add(relative);
            }
            Set<String> jars = new LinkedHashSet<>();
            for (VirtualFile root : OrderEnumerator.orderEntries(module)
                    .withoutSdk().librariesOnly().classes().getRoots()) {
                String jar = jarPath(root, repositoryRoot, outside);
                if (jar != null) jars.add(jar);
            }
            allJars.addAll(jars);
            infos.add(new ModuleInfo(module.getName(), roots, new ArrayList<>(jars)));
        }
        for (VirtualFile root : OrderEnumerator.orderEntries(project)
                .withoutSdk().librariesOnly().classes().getRoots()) {
            String jar = jarPath(root, repositoryRoot, outside);
            if (jar != null) allJars.add(jar);
        }
        return new Snapshot(infos, allJars, outside.size());
    }

    /** Repo-relative jar path; jars outside the repository are recorded once in {@code outside}. */
    private static String jarPath(VirtualFile root, String repositoryRoot, Set<String> outside) {
        if (!(root.getFileSystem() instanceof JarFileSystem)) return null;
        VirtualFile local = JarFileSystem.getInstance().getVirtualFileForJar(root);
        if (local == null) return null;
        String relative = DiagnosticPathMatcher.relativeToRoot(local.getPath(), repositoryRoot);
        if (relative == null) outside.add(local.getPath());
        return relative;
    }

    /** Dependencies before dependents; ties and cycles fall back to name order. */
    private static List<Module> dependencyOrder(Module[] modules) {
        List<Module> sorted = new ArrayList<>(List.of(modules));
        sorted.sort(Comparator.comparing(Module::getName));
        List<Module> ordered = new ArrayList<>();
        Set<Module> visited = new HashSet<>();
        for (Module module : sorted) visit(module, visited, ordered);
        return ordered;
    }

    private static void visit(Module module, Set<Module> visited, List<Module> ordered) {
        if (!visited.add(module)) return;
        List<Module> dependencies = new ArrayList<>(List.of(ModuleRootManager.getInstance(module).getDependencies()));
        dependencies.sort(Comparator.comparing(Module::getName));
        for (Module dependency : dependencies) visit(dependency, visited, ordered);
        ordered.add(module);
    }

    /** One layer per module with source roots, in dependency order; a jar goes to its first module. */
    static @NotNull List<LayerDraft> draftFromModules(@NotNull Snapshot snapshot) {
        List<LayerDraft> drafts = new ArrayList<>();
        Set<String> assigned = new HashSet<>();
        for (ModuleInfo module : snapshot.modules()) {
            if (module.sourceRoots().isEmpty()) continue;
            LayerDraft draft = new LayerDraft(module.name());
            for (String root : module.sourceRoots()) {
                draft.roots.add(root);
                draft.includes.add(root + "/**");
            }
            for (String jar : module.jars()) {
                if (assigned.add(jar)) draft.libs.add(jar);
            }
            drafts.add(draft);
        }
        return drafts;
    }
}
