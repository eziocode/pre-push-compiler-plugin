package com.github.prepushchecker.inline;

import com.github.prepushchecker.commitgen.AiRequest;
import com.github.prepushchecker.commitgen.CommitMessageGeneratorService;
import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.SensitivePaths;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.codeInsight.lookup.LookupManagerListener;
import com.intellij.codeInsight.template.TemplateManager;
import com.intellij.injected.editor.EditorWindow;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorEventMulticaster;
import com.intellij.openapi.editor.ex.EditorEventMulticasterEx;
import com.intellij.openapi.editor.ex.FocusChangeListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Drives inline AI suggestions: decides on each editor event whether to request, debounces automatic
 * requests, runs one request at a time on a pooled thread, drops stale answers and shows fresh ones
 * as ghost text. Instantiated lazily on first use, so it costs nothing while the feature is off.
 */
public final class InlineSuggestService implements Disposable {
    public static final int FAILURES_BEFORE_PAUSE = 3;

    static final String NOTIFICATION_GROUP = "Pre-Push Compilation Checker";
    static final String SETTINGS_PAGE = "Inline Suggestions";

    private static final Logger LOG = Logger.getInstance(InlineSuggestService.class);
    private static final long IDLE_WAIT_MS = 10_000;

    private final InlineSuggestionCache cache = new InlineSuggestionCache();
    private final Alarm alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
    private final Object lock = new Object();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger pendingCallbacks = new AtomicInteger();

    private volatile @Nullable Request current;
    private volatile @Nullable Function<CommitMessageProvider.Id, CommitMessageProvider> providerFactory;
    private volatile boolean paused;
    private volatile int failures;
    /** Document whose change is being processed (EDT only); caret moves caused by it are not user moves. */
    private @Nullable Document changing;

    public InlineSuggestService() {
        EditorEventMulticaster multicaster = EditorFactory.getInstance().getEventMulticaster();
        multicaster.addDocumentListener(new DocumentTracker(), this);
        multicaster.addCaretListener(new CaretTracker(), this);
        if (multicaster instanceof EditorEventMulticasterEx ex) {
            ex.addFocusChangeListener(new FocusTracker(), this);
        }
    }

    public static InlineSuggestService getInstance() {
        return ApplicationManager.getApplication().getService(InlineSuggestService.class);
    }

    private static InlineSuggestSettings.State settings() {
        return InlineSuggestSettings.getInstance().settings();
    }

    // ---------------------------------------------------------------- triggers

    /** EDT. Called by the typed, Enter and Backspace delegates after the edit happened. */
    public void onEvent(@NotNull Editor editor, @NotNull InlineTriggerPolicy.Event event) {
        InlineSuggestSettings.State s = settings();
        if (!s.enabled) return;
        Editor host = host(editor);
        if (InlineGhostText.isShowing(host)) return;          // typed through the suggestion
        Target target = target(host, s);
        if (target == null) return;
        InlineTriggerPolicy.Decision decision = InlineTriggerPolicy.decide(
            event, target.facts, true, InlineSuggestSettings.effectiveTrigger(s), paused);
        switch (decision) {
            case NONE -> { }
            case CACHE_ONLY -> showCached(host, target);
            case REQUEST -> {
                if (!showCached(host, target)) schedule(host, target, s);
            }
        }
    }

    /** EDT. Manual request: ignores trigger mode, pause and debounce. */
    public void requestNow(@NotNull Editor editor) {
        InlineSuggestSettings.State s = settings();
        if (!s.enabled) return;
        Editor host = host(editor);
        Target target = target(host, s);
        if (target == null || !InlineTriggerPolicy.eligible(target.facts)) {
            if (target != null && (target.facts.sensitive() || target.facts.extensionDisabled())) {
                HintManager.getInstance().showInformationHint(host, "Inline suggestions are off for this file");
            }
            return;
        }
        InlineGhostText.dismiss(host);
        HintManager.getInstance().showInformationHint(host, "Waiting for suggestion...");
        Request r = newRequest(host, target, s, true);
        replaceCurrent(r);
        launch(r);
    }

    /** Clears pause state, failure count and cache; stops work when the feature was switched off. */
    public void settingsChanged() {
        paused = false;
        failures = 0;
        cache.clear();
        cancelCurrent();
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) InlineGhostText.dismiss(editor);
    }

    public boolean isPaused() {
        return paused;
    }

    static boolean hasPendingRequest(@NotNull Editor editor) {
        InlineSuggestService service = ApplicationManager.getApplication().getServiceIfCreated(InlineSuggestService.class);
        Request r = service == null ? null : service.current;
        return r != null && !r.cancelled && r.editor == host(editor)
            && (r.scheduled || r.future != null && !r.future.isDone());
    }

    /** Esc while a request for {@code editor} runs: cancel it. No-op when the service was never used. */
    static void cancelRequestFor(@NotNull Editor editor) {
        if (!settings().enabled) return;
        InlineSuggestService service = ApplicationManager.getApplication().getServiceIfCreated(InlineSuggestService.class);
        if (service == null) return;
        Request r = service.current;
        if (r != null && r.editor == host(editor)) service.cancelCurrent();
    }

    // ---------------------------------------------------------------- request lifecycle

    private boolean showCached(Editor editor, Target target) {
        int offset = editor.getCaretModel().getOffset();
        String hit = cache.get(target.file.getPath(), prefixTail(editor.getDocument(), offset));
        if (hit == null) return false;
        InlineGhostText.show(editor, offset, hit);
        return true;
    }

    private void schedule(Editor editor, Target target, InlineSuggestSettings.State s) {
        Request r = newRequest(editor, target, s, false);
        replaceCurrent(r);
        r.scheduled = true;
        alarm.addRequest(() -> {
            try {
                launch(r);
            } finally {
                r.scheduled = false;
            }
        }, s.debounceMs);
    }

    private Request newRequest(Editor editor, Target target, InlineSuggestSettings.State s, boolean manual) {
        Document document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        return new Request(editor, target.project, target.file, document,
            document.getModificationStamp(), offset, prefixTail(document, offset), copyOf(s), manual,
            ModalityState.defaultModalityState());
    }

    private void replaceCurrent(Request r) {
        alarm.cancelAllRequests();
        Request old;
        synchronized (lock) {
            old = current;
            current = r;
        }
        if (old != null) old.cancel();
    }

    private void cancelCurrent() {
        alarm.cancelAllRequests();
        Request r = current;
        if (r != null) r.cancel();
    }

    private void launch(Request r) {
        synchronized (lock) {
            if (r.cancelled || r != current) return;
            Future<?> f = ApplicationManager.getApplication().executeOnPooledThread(() -> run(r));
            r.future = f;
        }
    }

    /** Pooled thread. */
    private void run(Request r) {
        running.incrementAndGet();
        try {
            if (r.cancelled || r.project.isDisposed()) return;
            String result;
            try {
                result = compute(r);
            } catch (ProcessCanceledException e) {
                return;
            } catch (Throwable t) {
                if (!r.cancelled) post(r, null, t);
                return;
            }
            if (result != null && !r.cancelled) post(r, result, null);
        } finally {
            Thread.interrupted();  // a cancel(true) must not leak into the pooled thread's next task
            running.decrementAndGet();
        }
    }

    /** Pooled thread. {@code null} when there is nothing to ask (e.g. sensitive file, offset gone). */
    private @Nullable String compute(Request r) throws Exception {
        InlineSuggestSettings.State s = r.settings;
        InlineContext ctx = InlineContextCollector.collect(r.project, r.file, r.document, r.offset, s);
        if (ctx == null || r.cancelled) return null;
        InlinePromptBuilder.Prompt prompt = InlinePromptBuilder.build(ctx, s);
        CommitMessageProvider.Id id = InlineSuggestSettings.providerOf(s);
        String model = s.model == null || s.model.isBlank() ? null : s.model.trim();
        AiRequest request = AiRequest.completion(prompt.system(), prompt.user(), model,
            InlineSuggestSettings.isFast(id) ? AiRequest.COMPLETION_FAST_TIMEOUT : AiRequest.COMPLETION_SLOW_TIMEOUT);
        Function<CommitMessageProvider.Id, CommitMessageProvider> factory = providerFactory;
        CommitMessageProvider provider = factory != null ? factory.apply(id) : CommitMessageGeneratorService.providerFor(id);
        String raw = provider.generate(request);
        return InlineSuggestionCleaner.clean(raw, ctx.prefix(), ctx.suffix(), s.multiLine, s.maxLines);
    }

    private void post(Request r, @Nullable String result, @Nullable Throwable error) {
        pendingCallbacks.incrementAndGet();
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                if (error != null) onFailure(r, error);
                else onSuccess(r, result);
            } finally {
                pendingCallbacks.decrementAndGet();
            }
        }, r.modality);
    }

    /** EDT. */
    private void onSuccess(Request r, String text) {
        if (r.cancelled || r != current) return;
        failures = 0;
        paused = false;
        if (!settings().enabled || r.editor.isDisposed() || r.project.isDisposed()) return;
        if (r.document.getModificationStamp() != r.stamp || r.editor.getCaretModel().getOffset() != r.offset) return;
        Target target = target(r.editor, settings());
        if (target == null || !InlineTriggerPolicy.eligible(target.facts)) return;
        if (r.manual) HintManager.getInstance().hideAllHints();
        if (text.isEmpty()) return;
        cache.put(r.file.getPath(), r.prefixTail, text);
        InlineGhostText.show(r.editor, r.offset, text);
    }

    /** EDT. */
    private void onFailure(Request r, Throwable error) {
        if (r.cancelled || r != current || r.editor.isDisposed() || r.project.isDisposed()) return;
        if (r.document.getModificationStamp() != r.stamp || r.editor.getCaretModel().getOffset() != r.offset) return;
        String message = message(error);
        if (r.manual) {
            if (!r.editor.isDisposed() && !r.project.isDisposed()) {
                HintManager.getInstance().showErrorHint(r.editor, message);
            }
            return;
        }
        LOG.debug("Inline suggestion request failed", error);
        failures++;
        if (failures >= FAILURES_BEFORE_PAUSE && !paused) {
            paused = true;
            notifyPaused(r.project, message);
        }
    }

    private static void notifyPaused(Project project, String message) {
        if (project.isDisposed()) return;
        Notification notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Inline suggestions paused: " + StringUtil.escapeXmlEntities(message),
                NotificationType.WARNING);
        notification.addAction(NotificationAction.createSimpleExpiring("Open Inline Suggestions settings",
            () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, SETTINGS_PAGE)));
        notification.notify(project);
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    // ---------------------------------------------------------------- editor state

    private record Target(Project project, VirtualFile file, InlineTriggerPolicy.EditorFacts facts) {
    }

    /** EDT. {@code null} when the editor has no project or file. */
    private static @Nullable Target target(Editor editor, InlineSuggestSettings.State s) {
        if (editor.isDisposed()) return null;
        Project project = editor.getProject();
        Document document = editor.getDocument();
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (project == null || project.isDisposed() || file == null) return null;
        InlineTriggerPolicy.EditorFacts facts = new InlineTriggerPolicy.EditorFacts(
            editor == FileEditorManager.getInstance(project).getSelectedTextEditor(),
            editor.getCaretModel().getCaretCount(),
            editor.getSelectionModel().hasSelection(),
            document.isWritable() && !editor.isViewer(),
            LookupManager.getActiveLookup(editor) != null,
            TemplateManager.getInstance(project).getActiveTemplate(editor) != null,
            ProjectFileIndex.getInstance(project).isInContent(file),
            InlineSuggestSettings.isExtensionDisabled(s, file.getExtension()),
            SensitivePaths.isExcluded(file.getPath()));
        return new Target(project, file, facts);
    }

    private static Editor host(Editor editor) {
        return editor instanceof EditorWindow window ? window.getDelegate() : editor;
    }

    private static String prefixTail(Document document, int offset) {
        int end = Math.max(0, Math.min(offset, document.getTextLength()));
        int start = Math.max(0, end - InlineSuggestionCache.KEY_TAIL);
        return document.getCharsSequence().subSequence(start, end).toString();
    }

    private static InlineSuggestSettings.State copyOf(InlineSuggestSettings.State s) {
        InlineSuggestSettings.State c = new InlineSuggestSettings.State();
        c.enabled = s.enabled;
        c.provider = s.provider;
        c.model = s.model;
        c.trigger = s.trigger;
        c.debounceMs = s.debounceMs;
        c.multiLine = s.multiLine;
        c.maxLines = s.maxLines;
        c.useProjectContext = s.useProjectContext;
        c.contextChars = s.contextChars;
        c.disabledExtensions = s.disabledExtensions;
        c.extraInstructions = s.extraInstructions;
        return c;
    }

    // ---------------------------------------------------------------- listeners

    private final class DocumentTracker implements DocumentListener {
        @Override
        public void beforeDocumentChange(@NotNull DocumentEvent event) {
            if (settings().enabled && ApplicationManager.getApplication().isDispatchThread()) {
                changing = event.getDocument();
            }
        }

        @Override
        public void documentChanged(@NotNull DocumentEvent event) {
            if (!settings().enabled) {
                changing = null;
                return;
            }
            Document document = event.getDocument();
            Request r = current;
            if (r != null && r.document == document) cancelCurrent();
            if (!ApplicationManager.getApplication().isDispatchThread()) {
                ApplicationManager.getApplication().invokeLater(() -> dismissAll(document));
                return;
            }
            try {
                boolean insertion = event.getOldLength() == 0 && event.getNewLength() > 0;
                for (Editor editor : EditorFactory.getInstance().getEditors(document)) {
                    if (!InlineGhostText.isShowing(editor)) continue;
                    if (!insertion || !InlineGhostText.typeThrough(editor, event.getOffset(), event.getNewFragment())) {
                        InlineGhostText.dismiss(editor);
                    }
                }
            } finally {
                changing = null;
            }
        }

        private void dismissAll(Document document) {
            for (Editor editor : EditorFactory.getInstance().getEditors(document)) {
                InlineGhostText.dismiss(editor);
            }
        }
    }

    private final class CaretTracker implements CaretListener {
        @Override
        public void caretPositionChanged(@NotNull CaretEvent event) {
            if (!settings().enabled) return;
            Editor editor = event.getEditor();
            if (editor.getDocument() == changing || !InlineGhostText.isShowing(editor)) return;
            if (editor.getCaretModel().getCaretCount() != 1
                || editor.getCaretModel().getOffset() != InlineGhostText.offset(editor)) {
                InlineGhostText.dismiss(editor);
            }
        }

        @Override
        public void caretAdded(@NotNull CaretEvent event) {
            if (settings().enabled) InlineGhostText.dismiss(event.getEditor());
        }
    }

    void onFocusLost(@NotNull Editor editor) {
        cancelRequestFor(editor);
        InlineGhostText.dismiss(editor);
    }

    private final class FocusTracker implements FocusChangeListener {
        @Override
        public void focusLost(@NotNull Editor editor) {
            if (settings().enabled) onFocusLost(editor);
        }
    }

    /** Dismisses the suggestion when a completion popup opens. Registered as a project listener. */
    public static final class LookupListener implements LookupManagerListener {
        @Override
        public void activeLookupChanged(@Nullable Lookup oldLookup, @Nullable Lookup newLookup) {
            if (newLookup != null && settings().enabled) {
                InlineGhostText.dismiss(host(newLookup.getEditor()));
            }
        }
    }

    // ---------------------------------------------------------------- request record

    private static final class Request {
        final Editor editor;
        final Project project;
        final VirtualFile file;
        final Document document;
        final long stamp;
        final int offset;
        final String prefixTail;
        final InlineSuggestSettings.State settings;
        final boolean manual;
        final ModalityState modality;
        volatile boolean cancelled;
        volatile boolean scheduled;
        volatile @Nullable Future<?> future;

        Request(Editor editor, Project project, VirtualFile file, Document document, long stamp,
                int offset, String prefixTail, InlineSuggestSettings.State settings, boolean manual,
                ModalityState modality) {
            this.editor = editor;
            this.project = project;
            this.file = file;
            this.document = document;
            this.stamp = stamp;
            this.offset = offset;
            this.prefixTail = prefixTail;
            this.settings = settings;
            this.manual = manual;
            this.modality = modality;
        }

        /** Cancelled or stale requests never count as failures. */
        void cancel() {
            cancelled = true;
            scheduled = false;
            Future<?> f = future;
            if (f != null) f.cancel(true);
        }
    }

    // ---------------------------------------------------------------- lifecycle and tests

    @Override
    public void dispose() {
        cancelCurrent();
    }

    @TestOnly
    public void setProviderFactory(@Nullable Function<CommitMessageProvider.Id, CommitMessageProvider> f) {
        providerFactory = f;
    }

    /** Waits (10 s at most) until no request is scheduled, running or waiting to be applied. */
    @TestOnly
    public void waitForIdle() {
        waitForIdle(() -> { });
    }

    /**
     * As {@link #waitForIdle()}, running {@code pump} between checks. Tests call this on the EDT and
     * pass an event-queue pump so requests that need the EDT cannot deadlock.
     */
    @TestOnly
    public void waitForIdle(@NotNull Runnable pump) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(IDLE_WAIT_MS);
        while (!isIdle() && System.nanoTime() < deadline) {
            pump.run();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean isIdle() {
        Request r = current;
        return (r == null || !r.scheduled && (r.future == null || r.future.isDone()))
            && running.get() == 0 && pendingCallbacks.get() == 0;
    }
}
