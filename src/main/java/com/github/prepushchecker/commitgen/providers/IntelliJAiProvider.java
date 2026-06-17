package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Delegates to the JetBrains AI Assistant plugin ({@code com.intellij.ml.llm})
 * when it is installed and the user is signed in to their JetBrains account.
 * <p>
 * Because the AI Assistant plugin does not expose a stable public API, this
 * provider uses reflection to locate and call the underlying service. If the
 * plugin is not installed, or if the reflection call fails (e.g., due to API
 * changes), a descriptive exception is thrown so the user can switch to another
 * provider.
 */
public final class IntelliJAiProvider implements CommitMessageProvider {

    private static final String AI_PLUGIN_ID = "com.intellij.ml.llm";

    // Candidate service class names across IntelliJ 2024.x versions
    private static final String[] CANDIDATE_CLASSES = {
        "com.intellij.ml.llm.core.LlmApiService",
        "com.intellij.ml.llm.shared.LlmApiService",
        "com.intellij.ai.llm.service.LlmService",
    };

    @Override
    public @NotNull String generate(@NotNull String systemPrompt, @NotNull String userPrompt)
            throws Exception {
        checkPluginAvailable();

        Class<?> serviceClass = findServiceClass();
        if (serviceClass == null) {
            throw new UnsupportedOperationException(
                "JetBrains AI Assistant is installed but its internal API could not be located "
                    + "(the API may have changed in this IDE version). "
                    + "Please use a different provider or file an issue.");
        }
        return callGenerate(serviceClass, systemPrompt, userPrompt);
    }

    @Override
    public @NotNull Id id() { return Id.INTELLIJ_AI; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void checkPluginAvailable() {
        IdeaPluginDescriptor descriptor =
            PluginManagerCore.getPlugin(PluginId.getId(AI_PLUGIN_ID));
        if (descriptor == null || !descriptor.isEnabled()) {
            throw new IllegalStateException(
                "JetBrains AI Assistant plugin (" + AI_PLUGIN_ID + ") is not installed or not enabled.\n"
                    + "Install it from Settings → Plugins → Marketplace and sign in to your JetBrains account.");
        }
    }

    @Nullable
    private static Class<?> findServiceClass() {
        for (String candidate : CANDIDATE_CLASSES) {
            try {
                return Class.forName(candidate);
            } catch (ClassNotFoundException ignored) {
                // try next
            }
        }
        return null;
    }

    @NotNull
    private static String callGenerate(
            @NotNull Class<?> serviceClass,
            @NotNull String systemPrompt,
            @NotNull String userPrompt) throws Exception {
        // Look for a static getInstance() then call a generate/complete method
        Object instance = null;
        try {
            Method getInstance = serviceClass.getMethod("getInstance");
            instance = getInstance.invoke(null);
        } catch (NoSuchMethodException ignored) {
            // service may be obtained via ApplicationManager
            try {
                Class<?> appManager =
                    Class.forName("com.intellij.openapi.application.ApplicationManager");
                Method getApp = appManager.getMethod("getApplication");
                Object app = getApp.invoke(null);
                Method getService = app.getClass().getMethod("getService", Class.class);
                instance = getService.invoke(app, serviceClass);
            } catch (Exception e) {
                throw new UnsupportedOperationException(
                    "Could not obtain JetBrains AI service instance.", e);
            }
        }
        if (instance == null) {
            throw new UnsupportedOperationException("JetBrains AI service instance is null.");
        }

        // Try common method signatures
        String fullPrompt = systemPrompt + "\n\n" + userPrompt;
        for (Method m : serviceClass.getMethods()) {
            String name = m.getName().toLowerCase();
            if ((name.contains("generate") || name.contains("complete") || name.contains("chat"))
                    && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == String.class) {
                Object result = m.invoke(instance, fullPrompt);
                return result != null ? result.toString().trim() : "";
            }
        }
        throw new UnsupportedOperationException(
            "No suitable generate/complete method found in JetBrains AI service "
                + serviceClass.getName() + ". The plugin API may have changed.");
    }
}
