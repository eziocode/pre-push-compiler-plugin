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
 * requests and runs one request at a time on a pooled thread. Keystrokes never kill a running request
 * (a CLI provider takes seconds); they only ask for a follow-up. An answer that arrives after the user
 * kept typing is shown from the caret when the typed text is a prefix of it, otherwise the follow-up
 * runs. Instantiated lazily on first use, so it costs nothing while the feature is off.
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
    /** The first automatic failure since start-up (or since settings changed) was reported (EDT). */
    private boolean failureReported;
    /** Most recent answer, re-usable while the user types its first characters (EDT). */
    private @Nullable Answer lastAnswer;
    /** Document whose change is being processed (EDT only); caret moves caused by it are not user moves. */
    private @Nullable Document changing;
    /** Message of the last automatic failure, cleared by the next success or a settings change. */
    private volatile @Nullable String lastError;

    /** What the status bar shows. */
    public enum Status { IDLE, LOADING, DISABLED, PAUSED, ERROR }

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
                if (!showCached(host, target)) request(host, target, s);
            }
        }
    }

    /**
     * EDT. Asks for a fresh automatic suggestion. A request already running for this editor is left
     * alone and only told that a follow-up is wanted should its answer not fit the new text, unless its
     * answer can no longer be used (text before its offset changed, or the caret moved before it): then
     * it is cancelled so a slow CLI does not delay the next suggestion.
     */
    private void request(Editor editor, Target target, InlineSuggestSettings.State s) {
        Request r = current;
        if (r != null && !r.cancelled && r.editor == editor && r.isRunning() && stillUseful(r, editor)) {
            r.followUp = true;
            return;
        }
        schedule(editor, target, s);
    }

    /** EDT. Whether {@code r}'s answer could still be shown: same text before its offset, caret at or after it. */
    private static boolean stillUseful(@NotNull Request r, @NotNull Editor editor) {
        Document document = editor.getDocument();
        if (document != r.document) return false;
        int caret = editor.getCaretModel().getOffset();
        int start = r.offset - r.prefixTail.length();
        if (start < 0 || caret < r.offset || r.offset > document.getTextLength()) return false;
        return r.prefixTail.contentEquals(document.getCharsSequence().subSequence(start, r.offset));
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
        failureReported = false;
        lastAnswer = null;
        lastError = null;
        cache.clear();
        cancelCurrent();
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) InlineGhostText.dismiss(editor);
        fireStatus();
    }

    /** Current state for the status bar widget. */
    public @NotNull Status status() {
        if (!settings().enabled) return Status.DISABLED;
        if (paused) return Status.PAUSED;
        Request r = current;
        if (r != null && !r.cancelled && r.isRunning()) return Status.LOADING;
        return lastError != null ? Status.ERROR : Status.IDLE;
    }

    public @Nullable String lastError() {
        return lastError;
    }

    /** Tells the status bar to repaint; safe from any thread. Pure UI update, so any modality is fine. */
    static void fireStatus() {
        ApplicationManager.getApplication().invokeLater(() ->
            ApplicationManager.getApplication().getMessageBus().syncPublisher(InlineStatusListener.TOPIC).statusChanged(),
            ModalityState.any());
    }

    public boolean isPaused() {
        return paused;
    }

    static boolean hasPendingRequest(@NotNull Editor editor) {
        InlineSuggestService service = ApplicationManager.getApplication().getServiceIfCreated(InlineSuggestService.class);
        Request r = service == null ? null : service.current;
        return r != null && !r.cancelled && r.editor == host(editor) && (r.scheduled || r.isRunning());
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
        if (hit != null) {
            InlineGhostText.show(editor, offset, hit);
            return true;
        }
        Answer a = lastAnswer;
        return a != null && showAdapted(editor, a);
    }

    /**
     * EDT. Shows {@code a} at the caret when the text before its offset is unchanged and the characters
     * typed since (offset..caret) are a strict prefix of it; the rest of the answer is shown.
     */
    private static boolean showAdapted(Editor editor, Answer a) {
        Document document = editor.getDocument();
        if (document != a.document || editor.isDisposed()) return false;
        int caret = editor.getCaretModel().getOffset();
        int start = a.offset - a.prefixTail.length();
        if (start < 0 || caret < a.offset || caret > document.getTextLength()) return false;
        CharSequence chars = document.getCharsSequence();
        if (!a.prefixTail.contentEquals(chars.subSequence(start, a.offset))) return false;
        String typed = chars.subSequence(a.offset, caret).toString();
        if (typed.length() >= a.text.length() || !a.text.startsWith(typed)) return false;
        InlineGhostText.show(editor, caret, a.text.substring(typed.length()));
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
        if (old != null) {
            old.cancel();
            fireStatus();
        }
    }

    private void cancelCurrent() {
        alarm.cancelAllRequests();
        Request r = current;
        if (r != null) {
            r.cancel();
            fireStatus();
        }
    }

    private void launch(Request r) {
        synchronized (lock) {
            if (r.cancelled || r != current) return;
            Future<?> f = ApplicationManager.getApplication().executeOnPooledThread(() -> run(r));
            r.future = f;
        }
        fireStatus();
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
                result = null;
            } catch (Throwable t) {
                if (!r.cancelled) post(r, null, t);
                return;
            }
            if (!r.cancelled) post(r, result, null);   // also a null result: a follow-up may be due
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
                onDone(r, result, error);
            } finally {
                pendingCallbacks.decrementAndGet();
            }
        }, r.modality);
    }

    /** EDT. The request ended: show its answer if it still fits, report a failure, or run the follow-up. */
    private void onDone(Request r, @Nullable String text, @Nullable Throwable error) {
        if (r.cancelled || r != current) return;
        r.finished = true;
        if (r.editor.isDisposed() || r.project.isDisposed()) return;
        fireStatus();
        if (error != null) {
            onFailure(r, error);
            return;
        }
        failures = 0;
        paused = false;
        lastError = null;
        if (!onSuccess(r, text) && r.followUp) followUp(r.editor);
    }

    /** EDT. {@code true} when a suggestion is now showing. */
    private boolean onSuccess(Request r, @Nullable String text) {
        if (!settings().enabled) return false;
        if (r.manual) HintManager.getInstance().hideAllHints();
        if (text == null || text.isEmpty()) {
            if (r.manual && !r.editor.isDisposed()) HintManager.getInstance().showInformationHint(r.editor, "No suggestion here");
            return false;
        }
        cache.put(r.file.getPath(), r.prefixTail, text);
        Answer answer = new Answer(r.document, r.offset, r.prefixTail, text);
        lastAnswer = answer;
        if (InlineGhostText.isShowing(r.editor)) return true;  // e.g. a cache hit while it ran
        Target target = target(r.editor, settings());
        if (target == null || !InlineTriggerPolicy.eligible(target.facts)) return false;
        return showAdapted(r.editor, answer);
    }

    /** EDT. The user typed while the last request ran and its answer did not fit: ask again. */
    private void followUp(Editor editor) {
        InlineSuggestSettings.State s = settings();
        if (!s.enabled) return;
        Target target = target(editor, s);
        if (target == null) return;
        InlineTriggerPolicy.Decision decision = InlineTriggerPolicy.decide(InlineTriggerPolicy.Event.TYPED,
            target.facts, true, InlineSuggestSettings.effectiveTrigger(s), paused);
        if (decision == InlineTriggerPolicy.Decision.REQUEST && !showCached(editor, target)) {
            schedule(editor, target, s);
        }
    }

    /** EDT. */
    private void onFailure(Request r, Throwable error) {
        String message = message(error);
        if (r.manual) {
            HintManager.getInstance().showErrorHint(r.editor, message);
            return;
        }
        LOG.info("Inline suggestion request failed: " + message);
        lastError = message;
        failures++;
        if (failures >= FAILURES_BEFORE_PAUSE && !paused) {
            paused = true;
            fireStatus();
            notifyPaused(r.project, message);
        } else if (!failureReported) {
            failureReported = true;
            notifyFailed(r.project, message);
        }
    }

    private static void notifyFailed(Project project, String message) {
        notify(project, "Inline suggestion failed: " + StringUtil.escapeXmlEntities(message),
            NotificationType.WARNING);
    }

    private static void notifyPaused(Project project, String message) {
        notify(project, "Inline suggestions paused: " + StringUtil.escapeXmlEntities(message),
            NotificationType.WARNING);
    }

    private static void notify(Project project, String content, NotificationType type) {
        if (project.isDisposed()) return;
        Notification notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(content, type);
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

    /** An answer for {@code offset} in {@code document}, given the text {@code prefixTail} before it. */
    private record Answer(Document document, int offset, String prefixTail, String text) {
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
            // A running request is not cancelled: its answer is checked against the new text on arrival.
            if (!ApplicationManager.getApplication().isDispatchThread()) {
                ApplicationManager.getApplication().invokeLater(() -> dismissAll(document));
                return;
            }
            try {
                boolean insertion = event.getOldLength() == 0 && event.getNewLength() > 0;
                for (Editor editor : EditorFactory.getInstance().getEditors(document)) {
                    // While a completion popup is open, LookupTypedHandler types identifier characters
                    // itself and never calls TypedHandlerDelegate.charTyped; trigger from here instead.
                    if (insertion && LookupManager.getActiveLookup(editor) != null) afterLookupTyping(editor);
                    if (!InlineGhostText.isShowing(editor)) continue;
                    if (!insertion || !InlineGhostText.typeThrough(editor, event.getOffset(), event.getNewFragment())) {
                        InlineGhostText.dismiss(editor);
                    }
                }
            } finally {
                changing = null;
            }
        }

        private void afterLookupTyping(Editor editor) {
            pendingCallbacks.incrementAndGet();
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    if (!editor.isDisposed()) onEvent(editor, InlineTriggerPolicy.Event.TYPED);
                } finally {
                    pendingCallbacks.decrementAndGet();
                }
            });
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

    /**
     * Registered as a project listener. Deliberately keeps the suggestion when a completion popup opens:
     * as in Copilot, ghost text and the popup coexist (Tab goes to the popup while it is visible).
     */
    public static final class LookupListener implements LookupManagerListener {
        @Override
        public void activeLookupChanged(@Nullable Lookup oldLookup, @Nullable Lookup newLookup) {
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
        /** Its answer reached the EDT (or never will). */
        volatile boolean finished;
        /** The user typed while it ran (EDT). */
        boolean followUp;

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

        /** Launched and its answer has not been handled yet. */
        boolean isRunning() {
            return future != null && !finished;
        }

        /** Cancelled requests never count as failures. */
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
