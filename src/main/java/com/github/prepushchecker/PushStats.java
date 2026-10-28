package com.github.prepushchecker;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.intellij.util.xmlb.annotations.XCollection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Counts what the push check did, on this machine only: pushes it let through, pushes it blocked
 * and why, and how often the clean-verdict cache answered. Only a push attempt that this plugin
 * validated is counted. Manual builds, smart background builds and bypassed pushes are not.
 *
 * <p>Nothing leaves the machine. State is stored in {@code prePushStats.xml} and is not synced
 * between machines. Every counted verdict also writes one {@code [push-stats]} line to idea.log.
 */
@Service(Service.Level.APP)
@State(name = "PrePushStats", storages = @Storage(value = "prePushStats.xml", roamingType = RoamingType.DISABLED))
public final class PushStats implements PersistentStateComponent<PushStats.State> {
    private static final Logger LOG = Logger.getInstance(PushStats.class);

    static final int MAX_RECENT = 100;
    static final int MAX_PROJECTS = 50;
    static final int MAX_LOG_STAMPS = 50;
    /** An identical blocked push (same request key) inside this window is one block, not many. */
    static final long BLOCKED_DEDUPE_MILLIS = TimeUnit.MINUTES.toMillis(10);
    /** Concurrent identical requests share one validation; they are one allowed push. */
    static final long ALLOWED_DEDUPE_MILLIS = TimeUnit.SECONDS.toMillis(30);

    /** What blocked a push. A push can have several. */
    enum Kind { COMPILE, LAYER, FORBIDDEN_JAR, SYMBOLIC }

    /** Where the verdict was produced. */
    enum Source { IDE, EXTERNAL }

    enum Result { ALLOWED, BLOCKED, TIMEOUT, STALE, INFRASTRUCTURE }

    /** Mutable counters; public fields because the XML serializer needs them. */
    public static final class Counters {
        public long allowed;
        public long blocked;
        public long blockedCompile;
        public long blockedLayer;
        public long blockedJar;
        public long blockedSymbolic;
        public long externalVerdicts;
        public long cacheHits;
        public long timeouts;
        public long stale;
        public long infrastructure;
        public long bypassActivations;
        /** Pushes allowed right after a block on the same branch: the block led to a fix. */
        public long fixedAfterBlock;
        public long firstAt;
        public long lastAt;

        long checks() {
            return allowed + blocked;
        }

        Counters copy() {
            Counters copy = new Counters();
            XmlSerializerUtil.copyBean(this, copy);
            return copy;
        }
    }

    /** Counters for one project, plus what is needed to avoid counting the same push twice. */
    public static final class ProjectEntry {
        public String path = "";
        public String name = "";
        public Counters counters = new Counters();
        public String lastBlockedBranch = "";
        public String lastKey = "";
        public String lastKeyResult = "";
        public long lastKeyAt;
    }

    /** One line of history. */
    public static final class Event {
        public long at;
        public String project = "";
        public String result = "";
        public String kinds = "";
        public String source = "";
        public String branch = "";
    }

    public static final class State {
        public Counters total = new Counters();
        @XCollection(style = XCollection.Style.v2)
        public List<ProjectEntry> projects = new ArrayList<>();
        @XCollection(style = XCollection.Style.v2)
        public List<Event> recent = new ArrayList<>();
        /** Hook log path to the last modified time already counted, so a restart does not recount it. */
        public Map<String, Long> logStamps = new LinkedHashMap<>();
    }

    private State state = new State();
    private final LongSupplier clock;

    public PushStats() {
        this(System::currentTimeMillis);
    }

    PushStats(@NotNull LongSupplier clock) {
        this.clock = clock;
    }

    public static @NotNull PushStats getInstance() {
        return ApplicationManager.getApplication().getService(PushStats.class);
    }

    @Override
    public synchronized @NotNull State getState() {
        return state;
    }

    @Override
    public synchronized void loadState(@NotNull State loaded) {
        state = loaded;
        if (state.total == null) state.total = new Counters();
        if (state.projects == null) state.projects = new ArrayList<>();
        if (state.recent == null) state.recent = new ArrayList<>();
        if (state.logStamps == null) state.logStamps = new LinkedHashMap<>();
    }

    /**
     * Which kinds of failure the error lines show, with how many lines of each. Matches the
     * message text the checks produce; anything else is a compiler error.
     */
    static @NotNull Map<Kind, Integer> classify(@NotNull Collection<String> errors) {
        Map<Kind, Integer> kinds = new EnumMap<>(Kind.class);
        for (String error : errors) {
            kinds.merge(kindOf(error), 1, Integer::sum);
        }
        return kinds;
    }

    private static Kind kindOf(String error) {
        if (error.contains("Forbidden jar:")) return Kind.FORBIDDEN_JAR;
        if (error.contains("Layer violation:") || error.contains("Layer rules error")
                || error.contains("Layer rules unreadable")) {
            return Kind.LAYER;
        }
        if (error.contains("defined only in unpushed local change")) return Kind.SYMBOLIC;
        return Kind.COMPILE;
    }

    /**
     * Counts one push verdict.
     *
     * @param requestKey identifies the push; an identical blocked or allowed push seen again within
     *                   its window is not counted twice. Blank disables this.
     * @param errors     the error lines of a blocked push, to tell the kinds apart
     * @param branch     current branch, used to see whether a block was followed by a fix
     * @return {@code false} when it was a repeat and nothing was counted
     */
    synchronized boolean record(
        @NotNull String projectPath,
        @NotNull String projectName,
        @NotNull Result result,
        @NotNull Collection<String> errors,
        @NotNull String requestKey,
        boolean cacheHit,
        @NotNull String branch,
        @NotNull Source source
    ) {
        long now = clock.getAsLong();
        ProjectEntry project = project(projectPath, projectName);
        if (isRepeat(project, result, requestKey, now)) {
            LOG.debug("[push-stats] repeat of an identical " + result + " push ignored for " + projectName);
            return false;
        }
        if (!requestKey.isBlank() && (result == Result.BLOCKED || result == Result.ALLOWED)) {
            project.lastKey = requestKey;
            project.lastKeyResult = result.name();
            project.lastKeyAt = now;
        }

        Map<Kind, Integer> kinds = new EnumMap<>(Kind.class);
        if (result == Result.BLOCKED) {
            kinds.putAll(classify(errors));
            if (kinds.isEmpty()) kinds.put(Kind.COMPILE, Math.max(1, errors.size()));
        }
        boolean fixedAfterBlock = result == Result.ALLOWED
            && !branch.isBlank() && branch.equals(project.lastBlockedBranch);
        if (result == Result.BLOCKED) {
            project.lastBlockedBranch = branch;
        } else if (result == Result.ALLOWED) {
            project.lastBlockedBranch = "";
        }

        apply(state.total, result, kinds, cacheHit, source, fixedAfterBlock, now);
        apply(project.counters, result, kinds, cacheHit, source, fixedAfterBlock, now);
        addEvent(projectName, result, kinds, source, branch, now);
        LOG.info("[push-stats] project=" + projectName + " verdict=" + result
            + " kinds=" + describe(kinds) + " cacheHit=" + cacheHit + " source=" + source);
        return true;
    }

    /** Counts a bypass switch being turned on. Pushes made while it is on are not visible to the plugin. */
    synchronized void recordBypassActivation(@NotNull String projectPath, @NotNull String projectName) {
        long now = clock.getAsLong();
        project(projectPath, projectName).counters.bypassActivations++;
        state.total.bypassActivations++;
        state.total.lastAt = now;
    }

    /**
     * {@code true} once per hook log write: when {@code stamp} is newer than the one already
     * counted for {@code logPath}. Remembers it, so the same log is not counted after a restart.
     */
    synchronized boolean claimLogStamp(@NotNull String logPath, long stamp) {
        Long seen = state.logStamps.get(logPath);
        if (seen != null && seen >= stamp) return false;
        state.logStamps.remove(logPath);
        state.logStamps.put(logPath, stamp);
        while (state.logStamps.size() > MAX_LOG_STAMPS) {
            state.logStamps.remove(state.logStamps.keySet().iterator().next());
        }
        return true;
    }

    synchronized @NotNull Counters totals() {
        return state.total.copy();
    }

    synchronized @NotNull Counters forProject(@Nullable String projectPath) {
        if (projectPath == null) return new Counters();
        for (ProjectEntry entry : state.projects) {
            if (entry.path.equals(projectPath)) return entry.counters.copy();
        }
        return new Counters();
    }

    synchronized @NotNull List<Event> recent() {
        return List.copyOf(state.recent);
    }

    /** Clears one project's counters, or everything when {@code projectPath} is {@code null}. */
    synchronized void reset(@Nullable String projectPath) {
        if (projectPath == null) {
            state = new State();
            return;
        }
        for (ProjectEntry entry : List.copyOf(state.projects)) {
            if (!entry.path.equals(projectPath)) continue;
            subtract(state.total, entry.counters);
            state.projects.remove(entry);
            state.recent.removeIf(event -> event.project.equals(entry.name));
        }
    }

    private boolean isRepeat(ProjectEntry project, Result result, String requestKey, long now) {
        if (requestKey.isBlank() || !requestKey.equals(project.lastKey)
                || !result.name().equals(project.lastKeyResult)) {
            return false;
        }
        long window = result == Result.BLOCKED ? BLOCKED_DEDUPE_MILLIS
            : result == Result.ALLOWED ? ALLOWED_DEDUPE_MILLIS : 0;
        return now - project.lastKeyAt < window;
    }

    private static void apply(
        Counters counters,
        Result result,
        Map<Kind, Integer> kinds,
        boolean cacheHit,
        Source source,
        boolean fixedAfterBlock,
        long now
    ) {
        if (counters.firstAt == 0) counters.firstAt = now;
        counters.lastAt = now;
        switch (result) {
            case ALLOWED -> {
                counters.allowed++;
                if (cacheHit) counters.cacheHits++;
                if (fixedAfterBlock) counters.fixedAfterBlock++;
            }
            case BLOCKED -> {
                counters.blocked++;
                if (kinds.containsKey(Kind.COMPILE)) counters.blockedCompile++;
                if (kinds.containsKey(Kind.LAYER)) counters.blockedLayer++;
                if (kinds.containsKey(Kind.FORBIDDEN_JAR)) counters.blockedJar++;
                if (kinds.containsKey(Kind.SYMBOLIC)) counters.blockedSymbolic++;
            }
            case TIMEOUT -> counters.timeouts++;
            case STALE -> counters.stale++;
            case INFRASTRUCTURE -> counters.infrastructure++;
        }
        if (source == Source.EXTERNAL && (result == Result.ALLOWED || result == Result.BLOCKED)) {
            counters.externalVerdicts++;
        }
    }

    private static void subtract(Counters from, Counters part) {
        from.allowed -= part.allowed;
        from.blocked -= part.blocked;
        from.blockedCompile -= part.blockedCompile;
        from.blockedLayer -= part.blockedLayer;
        from.blockedJar -= part.blockedJar;
        from.blockedSymbolic -= part.blockedSymbolic;
        from.externalVerdicts -= part.externalVerdicts;
        from.cacheHits -= part.cacheHits;
        from.timeouts -= part.timeouts;
        from.stale -= part.stale;
        from.infrastructure -= part.infrastructure;
        from.bypassActivations -= part.bypassActivations;
        from.fixedAfterBlock -= part.fixedAfterBlock;
    }

    private ProjectEntry project(String path, String name) {
        for (ProjectEntry entry : state.projects) {
            if (entry.path.equals(path)) {
                entry.name = name;
                return entry;
            }
        }
        ProjectEntry created = new ProjectEntry();
        created.path = path;
        created.name = name;
        state.projects.add(created);
        if (state.projects.size() > MAX_PROJECTS) {
            // Oldest-touched project goes; its counts stay in the all-projects total.
            state.projects.stream()
                .filter(entry -> entry != created)
                .min((a, b) -> Long.compare(a.counters.lastAt, b.counters.lastAt))
                .ifPresent(state.projects::remove);
        }
        return created;
    }

    private void addEvent(String project, Result result, Map<Kind, Integer> kinds, Source source,
                          String branch, long now) {
        Event event = new Event();
        event.at = now;
        event.project = project;
        event.result = result.name();
        event.kinds = describe(kinds);
        event.source = source.name();
        event.branch = branch;
        state.recent.add(0, event);
        while (state.recent.size() > MAX_RECENT) state.recent.remove(state.recent.size() - 1);
    }

    static @NotNull String describe(@NotNull Map<Kind, Integer> kinds) {
        if (kinds.isEmpty()) return "-";
        StringBuilder text = new StringBuilder();
        kinds.forEach((kind, count) -> {
            if (text.length() > 0) text.append(',');
            text.append(kind.name().toLowerCase(Locale.ROOT)).append(':').append(count);
        });
        return text.toString();
    }
}
