package com.github.prepushchecker.review;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Per-project review settings, stored in the workspace like the other Pre-Push Checker
 * settings. The rule file's {@code mandatory:} line, when present, overrides
 * {@link #isMandatoryFallback}.
 */
public final class ReviewSettings {

    /** When a review runs. Either way, content that was already reviewed is never reviewed again. */
    public enum Mode {
        /** The developer runs Review; the push only checks that it was done. */
        ON_DEMAND,
        /** The push reviews any in-scope file that was not reviewed yet. */
        ON_PUSH
    }

    private static final String ENABLED = "prepushchecker.review.enabled";
    private static final String MODE = "prepushchecker.review.mode";
    private static final String MANDATORY = "prepushchecker.review.mandatory";
    private static final String RULE_FILE = "prepushchecker.review.ruleFile";

    private ReviewSettings() {}

    /** Master switch. Off: no review button effect on pushes, no gate. Default on (needs a rule file anyway). */
    public static boolean isEnabled(@NotNull Project project) {
        return PropertiesComponent.getInstance(project).getBoolean(ENABLED, true);
    }

    public static void setEnabled(@NotNull Project project, boolean enabled) {
        PropertiesComponent.getInstance(project).setValue(ENABLED, enabled, true);
    }

    public static @NotNull Mode mode(@NotNull Project project) {
        String v = PropertiesComponent.getInstance(project).getValue(MODE, Mode.ON_DEMAND.name());
        try {
            return Mode.valueOf(v);
        } catch (IllegalArgumentException e) {
            return Mode.ON_DEMAND;
        }
    }

    public static void setMode(@NotNull Project project, @NotNull Mode mode) {
        PropertiesComponent.getInstance(project).setValue(MODE, mode.name(), Mode.ON_DEMAND.name());
    }

    public static boolean isMandatoryFallback(@NotNull Project project) {
        return PropertiesComponent.getInstance(project).getBoolean(MANDATORY, false);
    }

    public static void setMandatoryFallback(@NotNull Project project, boolean mandatory) {
        PropertiesComponent.getInstance(project).setValue(MANDATORY, mandatory, false);
    }

    /** Rule file path relative to each repository root; blank = {@link ReviewRules#DEFAULT_FILE_NAME}. */
    public static @NotNull String ruleFilePath(@NotNull Project project) {
        return PropertiesComponent.getInstance(project).getValue(RULE_FILE, "");
    }

    public static void setRuleFilePath(@NotNull Project project, @Nullable String path) {
        PropertiesComponent.getInstance(project).setValue(RULE_FILE, path == null ? "" : path.trim(), "");
    }

    /** The effective mandate: the rule file wins, the IDE toggle applies when the file is silent. */
    public static boolean isMandatory(@NotNull Project project, @Nullable ReviewRules rules) {
        if (rules != null && rules.mandatory() != null) return rules.mandatory();
        return isMandatoryFallback(project);
    }
}
