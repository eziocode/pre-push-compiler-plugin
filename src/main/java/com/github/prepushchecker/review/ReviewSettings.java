package com.github.prepushchecker.review;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Per-project review settings, stored in the workspace like the other Pre-Push Checker
 * settings. A rule file with {@code mandatory: true} forces the mandate for everyone; otherwise
 * {@link #isMandatoryFallback} decides, so the file can only make the gate stricter.
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
    private static final String IGNORE_MERGES = "prepushchecker.review.ignoreMergeChanges";

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

    /**
     * Skip code a push only carries because a remote branch was merged in (e.g. {@code git merge
     * main}): only the developer's own commits and merge conflict resolutions are reviewed.
     * Default on.
     */
    public static boolean isIgnoreMergeChanges(@NotNull Project project) {
        return PropertiesComponent.getInstance(project).getBoolean(IGNORE_MERGES, true);
    }

    public static void setIgnoreMergeChanges(@NotNull Project project, boolean ignore) {
        PropertiesComponent.getInstance(project).setValue(IGNORE_MERGES, ignore, true);
    }

    /** Rule file path relative to each repository root; blank = {@link ReviewRules#DEFAULT_FILE_NAME}. */
    public static @NotNull String ruleFilePath(@NotNull Project project) {
        return PropertiesComponent.getInstance(project).getValue(RULE_FILE, "");
    }

    public static void setRuleFilePath(@NotNull Project project, @Nullable String path) {
        PropertiesComponent.getInstance(project).setValue(RULE_FILE, path == null ? "" : path.trim(), "");
    }

    /** The effective mandate for this project: see {@link #effectiveMandate}. */
    public static boolean isMandatory(@NotNull Project project, @Nullable ReviewRules rules) {
        return effectiveMandate(rules == null ? null : rules.mandatory(), isMandatoryFallback(project));
    }

    /**
     * {@code mandatory: true} in the rule file forces the mandate for the whole team. A file with
     * {@code mandatory: false} or no line leaves it to each developer's own IDE toggle: the file
     * never switches off a mandate someone turned on for themselves. The pre-push hook applies
     * the same rule when the IDE is closed.
     */
    static boolean effectiveMandate(@Nullable Boolean ruleFile, boolean ideToggle) {
        return Boolean.TRUE.equals(ruleFile) || ideToggle;
    }
}
