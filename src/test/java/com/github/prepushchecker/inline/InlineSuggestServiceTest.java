package com.github.prepushchecker.inline;

import com.github.prepushchecker.commitgen.AiRequest;
import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.notification.Notification;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class InlineSuggestServiceTest extends BasePlatformTestCase {

    private final FakeProvider fake = new FakeProvider();
    private InlineSuggestSettings.State saved;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        InlineSuggestSettings.State s = state();
        saved = new InlineSuggestSettings.State();
        copy(s, saved);
        s.enabled = true;
        s.debounceMs = 300;                       // multi-key fixture typing stays inside one debounce window
        s.provider = CommitMessageProvider.Id.OLLAMA.name();
        s.trigger = InlineSuggestSettings.Trigger.AUTO.name();
        s.disabledExtensions = "";
        InlineSuggestService.getInstance().setProviderFactory(id -> fake);
        InlineSuggestService.getInstance().settingsChanged();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            settle();
            InlineSuggestService.getInstance().setProviderFactory(null);
            copy(saved, state());
            InlineSuggestService.getInstance().settingsChanged();
        } catch (Throwable t) {
            addSuppressedException(t);
        } finally {
            super.tearDown();
        }
    }

    private static InlineSuggestSettings.State state() {
        return InlineSuggestSettings.getInstance().settings();
    }

    private static void copy(InlineSuggestSettings.State from, InlineSuggestSettings.State to) {
        to.enabled = from.enabled;
        to.provider = from.provider;
        to.model = from.model;
        to.trigger = from.trigger;
        to.debounceMs = from.debounceMs;
        to.multiLine = from.multiLine;
        to.maxLines = from.maxLines;
        to.useProjectContext = from.useProjectContext;
        to.contextChars = from.contextChars;
        to.disabledExtensions = from.disabledExtensions;
        to.extraInstructions = from.extraInstructions;
    }

    private static void settle() {
        InlineSuggestService.getInstance().waitForIdle(PlatformTestUtil::dispatchAllEventsInIdeEventQueue);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    }

    public void testTypingShowsSuggestion() {
        myFixture.configureByText("A.txt", "<caret>");
        fake.answer("world");
        myFixture.type("hello ");
        settle();
        assertEquals("world", InlineGhostText.current(myFixture.getEditor()));
        assertEquals(1, fake.calls());           // six keystrokes, one debounced request
    }

    public void testDisabledSendsNothing() {
        state().enabled = false;
        myFixture.configureByText("A.txt", "<caret>");
        myFixture.type("abc");
        settle();
        InlineSuggestService.getInstance().requestNow(myFixture.getEditor());
        settle();
        assertEquals(0, fake.calls());
    }

    public void testCliProviderSupportsAutomaticSuggestions() {
        state().provider = "CLAUDE_CLI";
        myFixture.configureByText("A.txt", "<caret>");
        fake.answer("x");
        myFixture.type("abc");
        settle();
        assertEquals(1, fake.calls());
        assertEquals("x", InlineGhostText.current(myFixture.getEditor()));
    }

    public void testAnswerAfterTypeAheadShowsRemainder() {
        myFixture.configureByText("A.txt", "<caret>");
        fake.answerAfter("world", () -> myFixture.type("wo"));   // user keeps typing while the CLI runs
        myFixture.type("hello ");
        settle();
        assertEquals("rld", InlineGhostText.current(myFixture.getEditor()));
        assertEquals(myFixture.getEditor().getCaretModel().getOffset(), InlineGhostText.offset(myFixture.getEditor()));
        assertEquals(1, fake.calls());                          // usable answer: no follow-up request
    }

    public void testKeystrokesDoNotCancelRunningRequest() {
        myFixture.configureByText("A.txt", "<caret>");
        fake.answerOnRelease("zzz");                            // a slow CLI: blocks until released
        fake.answer("d");
        myFixture.type("a");
        fake.awaitStarted();
        myFixture.type("bc");                                   // does not match the answer
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        fake.release();
        settle();
        assertEquals(0, fake.interrupted());                    // the running request was not killed
        assertEquals(2, fake.calls());                          // one follow-up, not one per keystroke
        assertTrue(fake.prompts().get(1).contains("abc"));
        assertEquals("d", InlineGhostText.current(myFixture.getEditor()));
    }

    public void testCachedAnswerAdaptsToTypeAhead() {
        myFixture.configureByText("A.txt", "<caret>");
        fake.answer("world");
        myFixture.type("hello ");
        settle();
        InlineGhostText.dismiss(myFixture.getEditor());
        myFixture.type("w");                                    // no ghost showing: cache must adapt
        settle();
        assertEquals("orld", InlineGhostText.current(myFixture.getEditor()));
        assertEquals(1, fake.calls());
    }

    public void testSuggestionShowsWhileLookupIsActive() {
        myFixture.configureByText("A.java",
            "class A { void f() { int alpha1 = 0; int alpha2 = 0; al<caret> } }");
        myFixture.completeBasic();
        assertNotNull("lookup expected", LookupManager.getActiveLookup(myFixture.getEditor()));
        fake.answer("ha1 = 1;");
        myFixture.type("p");                                    // goes through the lookup's typed handler
        settle();
        assertNotNull("lookup must stay open", LookupManager.getActiveLookup(myFixture.getEditor()));
        assertEquals(1, fake.calls());
        assertEquals("ha1 = 1;", InlineGhostText.current(myFixture.getEditor()));
    }

    public void testLookupOpeningKeepsSuggestion() {
        myFixture.configureByText("A.java",
            "class A { void f() { int alpha1 = 0; int alpha2 = 0; al<caret> } }");
        InlineGhostText.show(myFixture.getEditor(), myFixture.getCaretOffset(), "pha1;");
        myFixture.completeBasic();
        assertNotNull(LookupManager.getActiveLookup(myFixture.getEditor()));
        assertEquals("pha1;", InlineGhostText.current(myFixture.getEditor()));
    }

    public void testFirstAutomaticFailureIsReported() {
        List<Notification> seen = new CopyOnWriteArrayList<>();
        getProject().getMessageBus().connect(getTestRootDisposable())
            .subscribe(Notifications.TOPIC, new Notifications() {
                @Override
                public void notify(@NotNull Notification notification) {
                    seen.add(notification);
                }
            });
        myFixture.configureByText("A.txt", "<caret>");
        fake.fail("codex: not signed in");
        myFixture.type("a");
        settle();
        assertEquals(1, seen.size());
        assertTrue(seen.get(0).getContent(), seen.get(0).getContent().contains("codex: not signed in"));
        fake.fail("boom");
        myFixture.type("b");
        settle();
        assertEquals(1, seen.size());                           // only the first failure pops up
        assertFalse(InlineSuggestService.getInstance().isPaused());
    }

    public void testStaleAnswerIsDropped() {
        myFixture.configureByText("A.txt", "<caret>");
        fake.answerAfter("old", () -> myFixture.type("z"));   // document changes while the request runs
        InlineSuggestService.getInstance().requestNow(myFixture.getEditor());
        settle();
        assertFalse("old".equals(InlineGhostText.current(myFixture.getEditor())));
    }

    public void testEscapeCancelsManualRequest() {
        state().trigger = "MANUAL";
        myFixture.configureByText("A.txt", "<caret>");
        fake.answerAfter("cancelled", () -> myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ESCAPE));
        InlineSuggestService.getInstance().requestNow(myFixture.getEditor());
        settle();
        assertFalse(InlineGhostText.isShowing(myFixture.getEditor()));
        assertEquals(1, fake.calls());
    }

    public void testFocusLossCancelsInFlightResponse() {
        myFixture.configureByText("A.txt", "<caret>");
        fake.answerAfter("late", () -> InlineSuggestService.getInstance().onFocusLost(myFixture.getEditor()));
        InlineSuggestService.getInstance().requestNow(myFixture.getEditor());
        settle();
        assertFalse(InlineGhostText.isShowing(myFixture.getEditor()));
        assertEquals(1, fake.calls());
    }

    public void testEditorClosedDuringRequest() {              // Review Focus 2
        myFixture.configureByText("A.txt", "<caret>");
        VirtualFile vf = myFixture.getFile().getVirtualFile();
        fake.answerAfter("x", () -> FileEditorManager.getInstance(getProject()).closeFile(vf));
        InlineSuggestService.getInstance().requestNow(myFixture.getEditor());
        settle();                                              // must not throw
    }

    public void testPausesAfterThreeFailuresAndResumes() {
        myFixture.configureByText("A.txt", "<caret>");
        for (int i = 0; i < 3; i++) {
            fake.fail("boom");
            myFixture.type("a");
            settle();
        }
        assertTrue(InlineSuggestService.getInstance().isPaused());
        int before = fake.calls();
        myFixture.type("b");
        settle();
        assertEquals(before, fake.calls());
        InlineSuggestService.getInstance().settingsChanged();
        assertFalse(InlineSuggestService.getInstance().isPaused());
    }

    public void testSuccessResetsFailureCount() {
        myFixture.configureByText("A.txt", "<caret>");
        for (int i = 0; i < 2; i++) {
            fake.fail("boom");
            myFixture.type("a");
            settle();
        }
        fake.answer("ok");
        myFixture.type("b");
        settle();
        for (int i = 0; i < 2; i++) {
            fake.fail("boom");
            myFixture.type("c");
            settle();
        }
        assertFalse(InlineSuggestService.getInstance().isPaused());
    }

    public void testManualFailureDoesNotPause() {
        state().provider = "CLAUDE_CLI";
        myFixture.configureByText("A.txt", "<caret>");
        for (int i = 0; i < 3; i++) {
            fake.fail("boom");
            InlineSuggestService.getInstance().requestNow(myFixture.getEditor());
            settle();
        }
        assertEquals(3, fake.calls());
        assertFalse(InlineSuggestService.getInstance().isPaused());
    }

    public void testBackspaceUsesCacheOnly() {
        myFixture.configureByText("A.txt", "<caret>");
        fake.answer("bc");
        myFixture.type("a");
        settle();
        fake.answer("zz");
        myFixture.type("x");
        settle();
        int before = fake.calls();
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE);
        settle();
        assertEquals(before, fake.calls());
        assertEquals("bc", InlineGhostText.current(myFixture.getEditor()));
    }

    public void testTypeThroughKeepsSuggestion() {
        myFixture.configureByText("A.txt", "<caret>");
        fake.answer("world");
        myFixture.type("hello ");
        settle();
        myFixture.type("wo");
        settle();
        assertEquals("rld", InlineGhostText.current(myFixture.getEditor()));
        assertEquals(1, fake.calls());
    }

    public void testCaretMoveDismisses() {
        myFixture.configureByText("A.txt", "ab<caret>");
        InlineGhostText.show(myFixture.getEditor(), 2, "c");
        myFixture.getEditor().getCaretModel().moveToOffset(0);
        assertFalse(InlineGhostText.isShowing(myFixture.getEditor()));
    }

    public void testOtherDocumentChangeDismisses() {
        myFixture.configureByText("A.txt", "ab<caret>");
        InlineGhostText.show(myFixture.getEditor(), 2, "c");
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(),
            () -> myFixture.getEditor().getDocument().deleteString(0, 1));
        assertFalse(InlineGhostText.isShowing(myFixture.getEditor()));
    }

    public void testSensitiveFileSendsNothing() {
        myFixture.configureByText(".env", "<caret>");
        myFixture.type("KEY=");
        settle();
        InlineSuggestService.getInstance().requestNow(myFixture.getEditor());
        settle();
        assertEquals(0, fake.calls());
    }

    public void testDisabledExtensionSendsNothing() {
        state().disabledExtensions = "md";
        myFixture.configureByText("A.md", "<caret>");
        myFixture.type("abc");
        settle();
        InlineSuggestService.getInstance().requestNow(myFixture.getEditor());
        settle();
        assertEquals(0, fake.calls());
    }

    /** Provider whose answers are queued by the test; an empty queue answers "". */
    private static final class FakeProvider implements CommitMessageProvider {
        private final Queue<Object> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger interrupted = new AtomicInteger();
        private final List<String> prompts = new CopyOnWriteArrayList<>();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private record Release(String text) {
        }

        void answer(String text) {
            queue.add(text);
        }

        void answerAfter(String text, Runnable onEdt) {
            queue.add(new Object[]{text, onEdt});
        }

        /** Next call blocks (like a slow CLI) until {@link #release()}. */
        void answerOnRelease(String text) {
            queue.add(new Release(text));
        }

        /** EDT: pumps events until the blocking call has started. */
        void awaitStarted() {
            long deadline = System.currentTimeMillis() + 10_000;
            while (started.getCount() > 0 && System.currentTimeMillis() < deadline) {
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            assertEquals("provider call did not start", 0, started.getCount());
        }

        void release() {
            released.countDown();
        }

        void fail(String message) {
            queue.add(new RuntimeException(message));
        }

        int calls() {
            return calls.get();
        }

        int interrupted() {
            return interrupted.get();
        }

        List<String> prompts() {
            return prompts;
        }

        @Override
        public @NotNull String generate(@NotNull AiRequest request) throws Exception {
            calls.incrementAndGet();
            prompts.add(request.user());
            assertEquals(AiRequest.Kind.COMPLETION, request.kind());
            Object next = queue.poll();
            if (next instanceof RuntimeException e) throw e;
            if (next instanceof Release r) {
                started.countDown();
                try {
                    if (!released.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
                } catch (InterruptedException e) {
                    interrupted.incrementAndGet();
                    throw e;
                }
                return r.text();
            }
            if (next instanceof Object[] pair) {
                ApplicationManager.getApplication().invokeAndWait((Runnable) pair[1]);
                try {
                    Thread.sleep(300);                          // a slow CLI is still running after the keystrokes
                } catch (InterruptedException e) {
                    interrupted.incrementAndGet();
                    throw e;
                }
                if (Thread.currentThread().isInterrupted()) interrupted.incrementAndGet();
                return (String) pair[0];
            }
            return Objects.toString(next, "");
        }

        @Override
        public @NotNull Id id() {
            return Id.OLLAMA;
        }
    }
}
