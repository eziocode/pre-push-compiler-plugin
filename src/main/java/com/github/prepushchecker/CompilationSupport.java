package com.github.prepushchecker;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessage;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiler-related helpers shared by the loopback server and tool window.
 *
 * <p>This class intentionally has no dependency on IntelliJ's {@code PrePushHandler}.
 * Git hooks are the single push-validation entry point.
 */
final class CompilationSupport {
    private CompilationSupport() {
    }

    static @NotNull CompileScope buildPushScope(
        @NotNull Project project,
        @NotNull Collection<VirtualFile> files,
        @NotNull CompilerManager compilerManager
    ) {
        return ApplicationManager.getApplication().runReadAction(
            (Computable<CompileScope>) () -> {
                ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
                ModuleManager moduleManager = ModuleManager.getInstance(project);
                Set<Module> modules = new LinkedHashSet<>();
                for (VirtualFile file : files) {
                    Module module = fileIndex.getModuleForFile(file, false);
                    if (module == null) continue;
                    modules.add(module);
                    modules.addAll(moduleManager.getModuleDependentModules(module));
                }

                VirtualFile[] fileArray = files.toArray(VirtualFile.EMPTY_ARRAY);
                if (modules.isEmpty()) {
                    return compilerManager.createFilesCompileScope(fileArray);
                }
                int cap = Registry.intValue("prepushchecker.scope.modules.cap", 50);
                if (modules.size() > cap) {
                    return compilerManager.createFilesCompileScope(fileArray);
                }
                return compilerManager.createModulesCompileScope(
                    modules.toArray(Module.EMPTY_ARRAY), false);
            });
    }

    static @NotNull List<String> formatCompilerMessages(
        @NotNull Project project,
        CompilerMessage[] messages
    ) {
        if (messages == null || messages.length == 0) {
            return List.of("Compilation failed with an unknown compiler error.");
        }

        List<String> formatted = new ArrayList<>(
            Math.min(messages.length, CompilationErrorService.MAX_RETAINED_ERRORS) + 1);
        int omitted = 0;
        for (CompilerMessage message : messages) {
            if (message == null) continue;
            if (formatted.size() >= CompilationErrorService.MAX_RETAINED_ERRORS) {
                omitted++;
                continue;
            }

            VirtualFile file = message.getVirtualFile();
            StringBuilder entry = new StringBuilder("[")
                .append(file == null ? "unknown" : toDisplayPath(project, file));
            String prefix = message.getRenderTextPrefix();
            if (prefix != null && !prefix.isBlank()) {
                entry.append(' ').append(prefix.trim());
            }
            entry.append("] ").append(message.getMessage() == null ? "" : message.getMessage());
            formatted.add(CompilationErrorService.compactError(entry.toString()));
        }
        if (omitted > 0) {
            formatted.add(CompilationErrorService.omittedErrorsMessage(omitted));
        }
        return formatted.isEmpty()
            ? List.of("Compilation failed with an unknown compiler error.")
            : List.copyOf(formatted);
    }

    static @NotNull String toDisplayPath(
        @NotNull Project project,
        @NotNull VirtualFile file
    ) {
        String basePath = project.getBasePath();
        if (basePath == null) return file.getPath();
        String relative = FileUtil.getRelativePath(basePath, file.getPath(), '/');
        return relative == null ? file.getPath() : relative;
    }
}
