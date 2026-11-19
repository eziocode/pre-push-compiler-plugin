package com.github.prepushchecker;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

/** Workspace-local first-run decision shared by startup and repository callbacks. */
@Service(Service.Level.PROJECT)
public final class HookSetup {
    private static final String KEY = "prepushchecker.hookSetup";
    private final PropertiesComponent properties;

    public HookSetup(Project project) {
        properties = PropertiesComponent.getInstance(project);
    }

    static HookSetup get(Project project) {
        return project.getService(HookSetup.class);
    }

    synchronized boolean claimPrompt() {
        if (properties.isValueSet(KEY)) return false;
        properties.setValue(KEY, "asked");
        return true;
    }

    synchronized void enable() {
        properties.setValue(KEY, "enabled");
    }

    synchronized boolean enabled() {
        return "enabled".equals(properties.getValue(KEY));
    }
}
