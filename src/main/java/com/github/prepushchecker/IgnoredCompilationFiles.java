package com.github.prepushchecker;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Project-local exact-file exclusions. Stored in workspace properties, never in VCS. */
final class IgnoredCompilationFiles {
    private static final String KEY = "prepushchecker.ignoredCompilationFiles.v1";

    record Entry(@NotNull String path, boolean enabled) {}

    private final Project project;
    private volatile List<Entry> entries;
    private volatile Set<String> enabledPaths;

    private IgnoredCompilationFiles(@NotNull Project project) {
        this.project = project;
        reload();
    }

    static @NotNull IgnoredCompilationFiles getInstance(@NotNull Project project) {
        return project.getUserData(Holder.KEY) != null
            ? project.getUserData(Holder.KEY)
            : create(project);
    }

    private static synchronized IgnoredCompilationFiles create(Project project) {
        IgnoredCompilationFiles existing = project.getUserData(Holder.KEY);
        if (existing != null) return existing;
        IgnoredCompilationFiles created = new IgnoredCompilationFiles(project);
        project.putUserData(Holder.KEY, created);
        return created;
    }

    private static final class Holder {
        private static final com.intellij.openapi.util.Key<IgnoredCompilationFiles> KEY =
            com.intellij.openapi.util.Key.create("prepushchecker.ignoredCompilationFiles");
    }

    @NotNull List<Entry> list() { return entries; }
    @NotNull Set<String> enabledPaths() { return enabledPaths; }

    boolean isIgnored(@NotNull String normalizedProjectPath) {
        return enabledPaths.contains(normalizedProjectPath);
    }

    boolean add(@NotNull String path) {
        String normalized = DiagnosticPathMatcher.normalizeProjectPath(project, path);
        if (normalized == null || !PushValidationPaths.isCompilableSource(normalized)) return false;
        Map<String, Boolean> values = mutableEntries();
        values.put(normalized, true);
        save(values);
        return true;
    }

    void remove(@NotNull String path) {
        Map<String, Boolean> values = mutableEntries();
        values.remove(path);
        save(values);
    }

    void setEnabled(@NotNull String path, boolean enabled) {
        Map<String, Boolean> values = mutableEntries();
        if (values.containsKey(path)) {
            values.put(path, enabled);
            save(values);
        }
    }

    private Map<String, Boolean> mutableEntries() {
        Map<String, Boolean> values = new LinkedHashMap<>();
        for (Entry entry : entries) values.put(entry.path(), entry.enabled());
        return values;
    }

    private void reload() {
        Map<String, Boolean> values = new LinkedHashMap<>();
        String raw = PropertiesComponent.getInstance(project).getValue(KEY, "");
        for (String line : raw.split("\\n")) {
            if (line.length() < 3 || line.charAt(1) != '\t') continue;
            String path = DiagnosticPathMatcher.normalizeProjectPath(project, line.substring(2));
            if (path != null && PushValidationPaths.isCompilableSource(path)) {
                values.put(path, line.charAt(0) == '1');
            }
        }
        updateCache(values);
    }

    private void save(Map<String, Boolean> values) {
        StringBuilder raw = new StringBuilder();
        values.forEach((path, enabled) -> raw.append(enabled ? '1' : '0').append('\t')
            .append(path).append('\n'));
        PropertiesComponent.getInstance(project).setValue(KEY, raw.toString(), "");
        updateCache(values);
        PrePushCheckerSettings.syncSettingsFile(project);
        CompilationErrorService.getInstance(project).ignoredFilesChanged();
    }

    private void updateCache(Map<String, Boolean> values) {
        List<Entry> next = new ArrayList<>(values.size());
        values.forEach((path, enabled) -> next.add(new Entry(path, enabled)));
        entries = List.copyOf(next);
        enabledPaths = next.stream().filter(Entry::enabled).map(Entry::path)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
