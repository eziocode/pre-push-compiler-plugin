package com.github.prepushchecker.review;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

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
    /** Set once the enabled default was decided for this project (see {@link #migrateEnabledDefault}). */
    private static final String ENABLED_MIGRATED = "prepushchecker.review.enabledDefaultMigrated";
    private static final String MODE = "prepushchecker.review.mode";
    private static final String MANDATORY = "prepushchecker.review.mandatory";
    private static final String RULE_FILE = "prepushchecker.review.ruleFile";
    private static final String IGNORE_MERGES = "prepushchecker.review.ignoreMergeChanges";

    private static final String CONFIRM_THRESHOLD = "prepushchecker.review.confirmFileThreshold";
    private static final String CONFIRM_TIMEOUT = "prepushchecker.review.confirmTimeoutSeconds";
    private static final String CONFIRM_ACTION = "prepushchecker.review.confirmTimeoutAction";

    /** What an unanswered large/merge push prompt does after its timeout. */
    public enum TimeoutAction { ABORT, SKIP, REVIEW }

    public static final int DEFAULT_CONFIRM_THRESHOLD = 10;
    public static final int DEFAULT_CONFIRM_TIMEOUT_SECONDS = 60;

    private ReviewSettings() {}

    /** An on-push review of more files than this asks first. 0 disables the file-count prompt. */
    public static int confirmFileThreshold(@NotNull Project project) {
        return Math.max(0, PropertiesComponent.getInstance(project).getInt(CONFIRM_THRESHOLD, DEFAULT_CONFIRM_THRESHOLD));
    }

    public static void setConfirmFileThreshold(@NotNull Project project, int n) {
        PropertiesComponent.getInstance(project).setValue(CONFIRM_THRESHOLD, Math.max(0, n), DEFAULT_CONFIRM_THRESHOLD);
    }

    public static int confirmTimeoutSeconds(@NotNull Project project) {
        return Math.max(5, PropertiesComponent.getInstance(project).getInt(CONFIRM_TIMEOUT, DEFAULT_CONFIRM_TIMEOUT_SECONDS));
    }

    public static void setConfirmTimeoutSeconds(@NotNull Project project, int s) {
        PropertiesComponent.getInstance(project).setValue(CONFIRM_TIMEOUT, Math.max(5, s), DEFAULT_CONFIRM_TIMEOUT_SECONDS);
    }

    public static @NotNull TimeoutAction confirmTimeoutAction(@NotNull Project project) {
        String v = PropertiesComponent.getInstance(project).getValue(CONFIRM_ACTION, TimeoutAction.ABORT.name());
        try {
            return TimeoutAction.valueOf(v);
        } catch (IllegalArgumentException e) {
            return TimeoutAction.ABORT;
        }
    }

    public static void setConfirmTimeoutAction(@NotNull Project project, @NotNull TimeoutAction a) {
        PropertiesComponent.getInstance(project).setValue(CONFIRM_ACTION, a.name(), TimeoutAction.ABORT.name());
    }

    /**
     * Master switch. Off: no review button effect on pushes, no gate. Default off; projects that
     * already used review before the default changed keep it on (see {@link #migrateEnabledDefault}).
     */
    public static boolean isEnabled(@NotNull Project project) {
        PropertiesComponent props = PropertiesComponent.getInstance(project);
        if (!props.isValueSet(ENABLED_MIGRATED)) {
            migrateEnabledDefault(project, () -> usedReviewBefore(project));
        }
        return props.getBoolean(ENABLED, false);
    }

    public static void setEnabled(@NotNull Project project, boolean enabled) {
        PropertiesComponent props = PropertiesComponent.getInstance(project);
        props.setValue(ENABLED_MIGRATED, true);
        props.setValue(ENABLED, enabled, false);
    }

    /**
     * Runs once per project. Review used to default on; when it was never set explicitly and the
     * project already has review state or a rule file, keep it on by persisting {@code true}.
     * Everyone else gets the new default (off). Later rule files do not switch it on again.
     */
    static synchronized void migrateEnabledDefault(@NotNull Project project, @NotNull BooleanSupplier usedBefore) {
        PropertiesComponent props = PropertiesComponent.getInstance(project);
        if (props.isValueSet(ENABLED_MIGRATED)) return;
        if (!props.isValueSet(ENABLED) && usedBefore.getAsBoolean()) {
            props.setValue(ENABLED, true, false);
        }
        props.setValue(ENABLED_MIGRATED, true);
    }

    private static boolean usedReviewBefore(@NotNull Project project) {
        try {
            ReviewStateStore store = ReviewStateStore.getInstance(project);
            if (store != null && !store.isEmpty()) return true;
            String base = project.getBasePath();
            return base != null && Files.isRegularFile(ReviewRules.ruleFile(Path.of(base), ruleFilePath(project)));
        } catch (RuntimeException e) {
            return false;
        }
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
