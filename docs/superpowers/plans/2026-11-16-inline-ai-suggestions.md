# Inline AI Suggestions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Copilot-style ghost-text code suggestions at the caret, with their own AI provider/model and a settings page, released as 2.11.0.

**Architecture:** A new `com.github.prepushchecker.inline` package. Editor delegates (typed / Enter / Backspace) feed an application service that debounces, collects PSI context on a pooled thread, calls an existing `CommitMessageProvider` with a new `AiRequest.Kind.COMPLETION`, and shows the answer as inlays the plugin renders itself. Tab and Esc are wrapped with `editorActionHandler` registrations.

**Tech Stack:** Java 17 (logic), Kotlin UI DSL (settings page), IntelliJ Platform 2023.3 SDK, JUnit 4 + platform test framework.

**Spec:** `docs/superpowers/specs/2026-11-16-inline-ai-suggestions-design.md`

## Global Constraints

- `sinceBuild = 233`, no upper bound. `.claude/rules/jetbrains-api-compat.md` applies to every task: no deprecated, experimental, internal or scheduled-for-removal API. Do **not** use `com.intellij.codeInsight.inline.completion.*`.
- Read actions only through `ReadActions.compute(Callable)`, background thread only. No network, process or PSI work on the EDT. Inlay and document mutations on the EDT only.
- Feature default is **off** (`enabled = false`). Nothing is sent to a provider while it is off.
- Files rejected by `SensitivePaths.isExcluded(path)` are never a target and never context.
- Tab and Esc must fall through to the original handler when the editor shows no suggestion of ours.
- Fast providers (automatic trigger allowed): `OPENAI`, `ANTHROPIC`, `GEMINI`, `OLLAMA`. All other ids are shortcut-only.
- Work on branch `feature/inline-suggestions`, not `main`. One commit per task.
- Test command form: `./gradlew test --tests "<fully.qualified.TestClass>"`.

## Review Focus

1. Model answers with far more than asked (whole file, 200 lines, prose plus fences): suggestion is cut to `maxLines` and fences/prose never reach the editor. Test in Task 4.
2. Editor closed or project disposed while a request is in flight: answer is dropped, no exception. Test in Task 7.
3. Tab pressed in editor B while a suggestion shows in editor A: B indents normally, A keeps its suggestion. Test in Task 6.
4. Non-Java file, or indexing in progress (dumb mode): context is prefix/suffix (plus open tabs) only, no exception, a request still goes out. Test in Task 5.
5. Caret at offset 0, empty file, and a file longer than the prefix/suffix limits: no negative offsets, limits applied from the caret outward. Test in Task 5.

---

## File Structure

Create, all under `src/main/java/com/github/prepushchecker/inline/` unless noted:

| File | Responsibility |
|---|---|
| `InlineSuggestSettings.java` | Persistent state, fast/slow provider rule, effective trigger |
| `InlineContext.java` | Records `InlineContext`, `InlineContext.Snippet` |
| `InlinePromptBuilder.java` | Pure: context + settings to system/user prompt |
| `InlineSuggestionCleaner.java` | Pure: raw model answer to insertable text |
| `InlineContextCollector.java` | PSI/editor context under a read action |
| `InlineGhostText.java` | Inlays, renderer, show/dismiss/type-through/accept |
| `InlineTabHandler.java`, `InlineEscapeHandler.java` | `EditorTab` / `EditorEscape` wrappers |
| `InlineTriggerPolicy.java` | Pure: event + editor facts to decision |
| `InlineSuggestionCache.java` | LRU of 20 suggestions |
| `InlineSuggestService.java` | Debounce, request lifecycle, failure pause |
| `InlineTypedHandler.java`, `InlineEnterHandler.java`, `InlineBackspaceHandler.java` | Feed events to the service |
| `InlineSuggestAction.java`, `ToggleInlineSuggestAction.java` | Shortcut and toggle |
| `src/main/kotlin/com/github/prepushchecker/inline/InlineSuggestConfigurable.kt` | Settings page |

Modify: `commitgen/AiRequest.java`, `commitgen/AiOutputSanitizer.java`, `commitgen/cli/AiCliRunner.java`, all ten classes in `commitgen/providers/`, `META-INF/plugin.xml`, `build.gradle.kts`, `gradle.properties`, `CHANGELOG.md`, `README.md`.

---

### Task 1: `AiRequest` completion kind and model override

**Files:**
- Modify: `src/main/java/com/github/prepushchecker/commitgen/AiRequest.java`
- Modify: `providers/OpenAiProvider.java`, `AnthropicProvider.java`, `GeminiProvider.java`, `OllamaProvider.java`, `GhCopilotProvider.java`, `CodexCliProvider.java`, `ClaudeCliProvider.java`, `LlmCliProvider.java`, `CustomCliProvider.java`
- Test: `src/test/java/com/github/prepushchecker/commitgen/AiRequestTest.java`, `src/test/java/com/github/prepushchecker/commitgen/OllamaProviderCompletionTest.java`

**Interfaces:**
- Produces:
  - `record AiRequest(String system, String user, Kind kind, int maxOutputTokens, Duration timeout, @Nullable String model)`; `Kind` gains `COMPLETION`.
  - `static final Duration COMPLETION_FAST_TIMEOUT = Duration.ofSeconds(15)`, `COMPLETION_SLOW_TIMEOUT = Duration.ofSeconds(60)`.
  - `static AiRequest completion(String system, String user, @Nullable String model, Duration timeout)`: kind `COMPLETION`, 256 output tokens.
  - `String modelOr(String fallback)`: `model` when non-blank, else `fallback`.
  - `structured()`: true only for `REVIEW` and `FIX`. `withTimeout` keeps `model`.

- [ ] **Step 1: Write failing tests**

```java
// AiRequestTest (plain JUnit 4)
@Test public void structuredOnlyForReviewAndFix() {
    assertFalse(AiRequest.commitMessage("s", "u").structured());
    assertTrue(AiRequest.review("s", "u").structured());
    assertTrue(AiRequest.fix("s", "u").structured());
    assertFalse(AiRequest.completion("s", "u", null, AiRequest.COMPLETION_FAST_TIMEOUT).structured());
}
@Test public void completionDefaults() {
    AiRequest r = AiRequest.completion("s", "u", "m1", AiRequest.COMPLETION_FAST_TIMEOUT);
    assertEquals(256, r.maxOutputTokens());
    assertEquals(Duration.ofSeconds(15), r.timeout());
    assertEquals("m1", r.modelOr("fallback"));
    assertEquals("fallback", AiRequest.commitMessage("s", "u").modelOr("fallback"));
    assertEquals("fallback", AiRequest.completion("s", "u", "  ", r.timeout()).modelOr("fallback"));
    assertEquals("m1", r.withTimeout(Duration.ofSeconds(60)).model());
}
```

`OllamaProviderCompletionTest extends BasePlatformTestCase`: start a `com.sun.net.httpserver.HttpServer` on port 0 that records the request body and answers `{"message":{"content":"x = 1"}}`; point `CommitMessageSettings.getInstance().settings().ollamaBaseUrl` at it (restore in `tearDown`). Assert `new OllamaProvider().generate(AiRequest.completion("s","u","qwen-test",AiRequest.COMPLETION_FAST_TIMEOUT))` returns `"x = 1"` and the recorded body contains `"model":"qwen-test"`.

- [ ] **Step 2: Run both, expect compile failure** (`completion` undefined).
- [ ] **Step 3: Implement the `AiRequest` changes.** Existing factories pass `null` for `model`. Fix every other `new AiRequest(` call site (grep `src/main` and `src/test`).
- [ ] **Step 4: Providers.** Each provider replaces its settings model `m` with `request.modelOr(m)`: the four HTTP providers and `GhCopilotProvider` where they read the model; the CLI providers in `generate(...)` where they pass `s.codexModel` / `s.claudeModel` / `s.llmModel` / `s.customCliModel`. `OllamaProvider`: the 120 s minimum applies only when `request.kind() != Kind.COMPLETION`.
- [ ] **Step 5: Run** `./gradlew test --tests "com.github.prepushchecker.commitgen.*"`. Expected: PASS, including existing `AiOutputSanitizerTest` and `CommitMessagePromptBuilderTest`.
- [ ] **Step 6: Commit** `feat: add completion request kind and per-request model override`.

---

### Task 2: CLI output mode for completions

**Files:**
- Modify: `commitgen/AiOutputSanitizer.java`, `commitgen/cli/AiCliRunner.java`, `providers/CodexCliProvider.java`, `ClaudeCliProvider.java`, `LlmCliProvider.java`, `CustomCliProvider.java`
- Test: `src/test/java/com/github/prepushchecker/commitgen/AiOutputSanitizerTest.java` (add cases)

**Interfaces:**
- Consumes: `AiRequest.Kind.COMPLETION` (Task 1).
- Produces:
  - `static String AiOutputSanitizer.cleanCompletion(@Nullable String raw)`: removes ANSI/control noise and thinking/tool tag blocks (reuse `stripTerminalNoise`, `TAG_BLOCK`, `STRAY_TAG`), unwraps one outer code fence, removes leading and trailing blank lines and trailing whitespace. It must **not** call `dropNoiseLines`, `dropPreambleAndTrailer`, `tidy` or `unquote`, and must keep the first line's leading indentation.
  - `static String AiCliRunner.finish(String label, @Nullable String text, String authHint, AiRequest request)`: `COMPLETION` returns `cleanCompletion(text)` and throws the same "empty result" `RuntimeException` as `requireText` when blank; every other kind delegates to `finish(label, text, authHint, request.structured())`.

- [ ] **Step 1: Write failing tests** in `AiOutputSanitizerTest`:

```java
@Test public void completionKeepsCodeThatLooksLikeNoise() {
    String code = "    [info] = load();\n    String s = \"quoted\";\n    │ box";
    assertEquals(code, AiOutputSanitizer.cleanCompletion(code));
}
@Test public void completionUnwrapsFenceAndAnsi() {
    assertEquals("return a + b;",
        AiOutputSanitizer.cleanCompletion("\u001B[32m```java\nreturn a + b;\n```\u001B[0m\n"));
}
@Test public void completionDropsThinkingBlock() {
    assertEquals("x++;", AiOutputSanitizer.cleanCompletion("<thinking>plan</thinking>\nx++;"));
}
@Test public void completionBlankStaysBlank() {
    assertEquals("", AiOutputSanitizer.cleanCompletion("  \n\n"));
    assertEquals("", AiOutputSanitizer.cleanCompletion(null));
}
```

- [ ] **Step 2: Run** `./gradlew test --tests "com.github.prepushchecker.commitgen.AiOutputSanitizerTest"`. Expected: FAIL, `cleanCompletion` undefined.
- [ ] **Step 3: Implement `cleanCompletion` and the `finish` overload.** Switch the four CLI providers' `AiCliRunner.finish(..., request.structured())` calls to `finish(..., request)`.
- [ ] **Step 4: Run the same test class.** Expected: PASS, old cases included.
- [ ] **Step 5: Commit** `feat: add completion output mode for CLI providers`.

---

### Task 3: Settings state

**Files:**
- Create: `inline/InlineSuggestSettings.java`
- Modify: `src/main/resources/META-INF/plugin.xml` (add `<applicationService serviceImplementation="com.github.prepushchecker.inline.InlineSuggestSettings"/>`)
- Test: `src/test/java/com/github/prepushchecker/inline/InlineSuggestSettingsTest.java`

**Interfaces:**
- Produces:
  - `@State(name = "PrePushInlineSuggestSettings", storages = @Storage("prePushInlineSuggest.xml"))` application service, `static InlineSuggestSettings getInstance()`, `State settings()`.
  - `State` public fields with these defaults: `boolean enabled=false; String provider="OLLAMA"; String model=""; String trigger="AUTO"; int debounceMs=350; boolean multiLine=true; int maxLines=8; boolean useProjectContext=true; int contextChars=6000; String disabledExtensions=""; String extraInstructions=""`.
  - `enum Trigger { AUTO, MANUAL }`.
  - `static void clamp(State s)`: `debounceMs` 100..2000, `maxLines` 1..30, `contextChars` 0..20000; unknown `provider` becomes `"OLLAMA"`, unknown `trigger` becomes `"AUTO"`. Called from `loadState`.
  - `static CommitMessageProvider.Id providerOf(State s)`, `static boolean isFast(CommitMessageProvider.Id id)`, `static Trigger effectiveTrigger(State s)` (`MANUAL` whenever the provider is not fast), `static boolean isExtensionDisabled(State s, @Nullable String ext)` (comma-separated, trimmed, case-insensitive, leading dot ignored).

- [ ] **Step 1: Write failing tests** (plain JUnit 4, static methods only):

```java
@Test public void defaults() {
    var s = new InlineSuggestSettings.State();
    assertFalse(s.enabled);
    assertEquals(CommitMessageProvider.Id.OLLAMA, InlineSuggestSettings.providerOf(s));
    assertEquals(InlineSuggestSettings.Trigger.AUTO, InlineSuggestSettings.effectiveTrigger(s));
}
@Test public void slowProviderForcesManual() {
    var s = new InlineSuggestSettings.State();
    for (var id : CommitMessageProvider.Id.values()) {
        s.provider = id.name();
        boolean fast = EnumSet.of(Id.OPENAI, Id.ANTHROPIC, Id.GEMINI, Id.OLLAMA).contains(id);
        assertEquals(fast, InlineSuggestSettings.isFast(id));
        assertEquals(fast ? Trigger.AUTO : Trigger.MANUAL, InlineSuggestSettings.effectiveTrigger(s));
    }
}
@Test public void clampFixesBadValues() {
    var s = new InlineSuggestSettings.State();
    s.debounceMs = 5; s.maxLines = 99; s.contextChars = -1; s.provider = "NOPE"; s.trigger = "x";
    InlineSuggestSettings.clamp(s);
    assertEquals(100, s.debounceMs); assertEquals(30, s.maxLines); assertEquals(0, s.contextChars);
    assertEquals("OLLAMA", s.provider); assertEquals("AUTO", s.trigger);
}
@Test public void disabledExtensions() {
    var s = new InlineSuggestSettings.State();
    s.disabledExtensions = " md, .TXT ,";
    assertTrue(InlineSuggestSettings.isExtensionDisabled(s, "MD"));
    assertTrue(InlineSuggestSettings.isExtensionDisabled(s, "txt"));
    assertFalse(InlineSuggestSettings.isExtensionDisabled(s, "java"));
    assertFalse(InlineSuggestSettings.isExtensionDisabled(s, null));
}
```

- [ ] **Step 2: Run** `./gradlew test --tests "com.github.prepushchecker.inline.InlineSuggestSettingsTest"`. Expected: compile failure.
- [ ] **Step 3: Implement**, following `CommitMessageSettings` for the service shape.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: add inline suggestion settings state`.

---

### Task 4: Prompt builder and suggestion cleaner

**Files:**
- Create: `inline/InlineContext.java`, `inline/InlinePromptBuilder.java`, `inline/InlineSuggestionCleaner.java`
- Test: `src/test/java/com/github/prepushchecker/inline/InlinePromptBuilderTest.java`, `InlineSuggestionCleanerTest.java`

**Interfaces:**
- Consumes: `InlineSuggestSettings.State` (Task 3); `AiOutputSanitizer.cleanCompletion(String)` (Task 2).
- Produces:
  - `record InlineContext(String filePath, String language, String prefix, String suffix, List<Snippet> related)` with nested `record Snippet(String title, String text)`.
  - `InlinePromptBuilder.CURSOR = "<CURSOR>"`; `record Prompt(String system, String user)`; `static Prompt build(InlineContext ctx, InlineSuggestSettings.State s)`.
  - `static String InlineSuggestionCleaner.clean(@Nullable String raw, String prefix, String suffix, boolean multiLine, int maxLines)`; `""` means "show nothing".

Prompt rules: system prompt states the answer must be only the text to insert at `<CURSOR>`, no explanation, no code fences, no repetition of text before or after the cursor; adds "one line only" when `!multiLine`, otherwise "at most N lines" with `maxLines`; appends `extraInstructions` when non-blank. User prompt: `File: <path> (<language>)`, then each snippet as `--- <title> ---` + text, then `--- current file ---` + `prefix + CURSOR + suffix`.

Cleaner order: (1) `AiOutputSanitizer.cleanCompletion`; (2) if the result starts with the last non-blank line of `prefix` (trimmed) and is longer than it, drop that echoed part; (3) cut at the first line break when `!multiLine`, else keep at most `maxLines` lines; (4) if the tail of the result equals the first non-blank line of `suffix` (trimmed), drop that tail; (5) strip trailing whitespace; (6) whitespace-only becomes `""`.

- [ ] **Step 1: Write failing tests**

```java
// InlinePromptBuilderTest
InlineContext ctx = new InlineContext("src/A.java", "JAVA", "int a = ", ";\n}",
    List.of(new InlineContext.Snippet("B.java", "class B { int size(); }")));
@Test public void userPromptHasCursorBetweenPrefixAndSuffix() {
    var p = InlinePromptBuilder.build(ctx, new InlineSuggestSettings.State());
    assertTrue(p.user().contains("int a = <CURSOR>;\n}"));
    assertTrue(p.user().contains("--- B.java ---"));
    assertTrue(p.user().contains("File: src/A.java (JAVA)"));
    assertTrue(p.user().indexOf("--- B.java ---") < p.user().indexOf("--- current file ---"));
}
@Test public void systemPromptReflectsSettings() {
    var s = new InlineSuggestSettings.State();
    s.maxLines = 5; s.extraInstructions = "Use tabs.";
    String sys = InlinePromptBuilder.build(ctx, s).system();
    assertTrue(sys.contains("at most 5 lines")); assertTrue(sys.contains("Use tabs."));
    s.multiLine = false;
    assertTrue(InlinePromptBuilder.build(ctx, s).system().contains("one line only"));
}

// InlineSuggestionCleanerTest
@Test public void stripsFence()      { assertEquals("b + c", clean("```java\nb + c\n```", "int a = ", ";", true, 8)); }
@Test public void stripsEchoedPrefix(){ assertEquals("b + c", clean("int a = b + c", "x();\nint a = ", ";", true, 8)); }
@Test public void singleLineCuts()   { assertEquals("b", clean("b\nc\nd", "", "", false, 8)); }
@Test public void capsAtMaxLines()   { assertEquals("1\n2", clean("1\n2\n3\n4", "", "", true, 2)); }
@Test public void dropsRepeatedSuffix(){ assertEquals("foo()", clean("foo();", "x = ", ";\n}", true, 8)); }
@Test public void emptyIsNothing()   { assertEquals("", clean("  \n", "", "", true, 8)); assertEquals("", clean(null, "", "", true, 8)); }
@Test public void keepsLeadingIndent(){ assertEquals("    return 1;", clean("    return 1;", "{\n", "\n}", true, 8)); }
@Test public void oversizedProseAndFences() {   // Review Focus 1
    String raw = "Here is the code:\n```java\n" + "line;\n".repeat(200) + "```\nHope this helps!";
    String out = clean(raw, "", "", true, 8);
    assertEquals(8, out.split("\n").length);
    assertFalse(out.contains("```")); assertFalse(out.contains("Hope")); assertFalse(out.contains("Here is"));
}
```

`oversizedProseAndFences` needs `cleanCompletion` to keep only the fenced body when the answer has exactly one fenced block surrounded by prose: add that rule to `AiOutputSanitizer.cleanCompletion` and a matching case to `AiOutputSanitizerTest`.

- [ ] **Step 2: Run** `./gradlew test --tests "com.github.prepushchecker.inline.Inline*Test"`. Expected: compile failure.
- [ ] **Step 3: Implement the three files** and the `cleanCompletion` rule.
- [ ] **Step 4: Run** the same command plus `AiOutputSanitizerTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat: add inline prompt builder and suggestion cleaner`.

---

### Task 5: Context collector

**Files:**
- Create: `inline/InlineContextCollector.java`
- Modify: `build.gradle.kts` (add `testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Plugin.Java)` next to the existing `testFramework` line)
- Test: `src/test/java/com/github/prepushchecker/inline/InlineContextCollectorTest.java` (`LightJavaCodeInsightFixtureTestCase`)

**Interfaces:**
- Consumes: `InlineContext`, `InlineContext.Snippet` (Task 4); `InlineSuggestSettings.State` (Task 3); `ReadActions.compute`; `SensitivePaths.isExcluded`.
- Produces:
  - `static final int PREFIX_CHARS = 4000, SUFFIX_CHARS = 1000, OPEN_FILE_LINES = 60`.
  - `static @Nullable InlineContext collect(Project project, VirtualFile file, Document document, int offset, InlineSuggestSettings.State s)`. Background thread only; wraps its work in `ReadActions.compute`. Returns `null` when the file is sensitive or `offset` is outside `0..document.getTextLength()`.

Behaviour: `filePath` relative to the project base path (file name when outside it); `language` from the PSI file's language id, file-type name when there is no PSI. Related snippets only when `s.useProjectContext`, total text capped at `s.contextChars`, in this order:
1. Java PSI file and `!DumbService.isDumb(project)`: walk the file's `PsiJavaCodeReferenceElement`s, resolve to `PsiClass`, keep classes whose file is in project source content (`ProjectFileIndex.isInSourceContent`), exclude classes declared in the current file, dedupe, order by distance of nearest reference from `offset`. Snippet title = qualified name; text = class header line, fields, non-private method signatures without bodies.
2. Other files open in `FileEditorManager.getOpenFiles()` with the same file type, not sensitive: title = file name, text = first `OPEN_FILE_LINES` lines.

A snippet that does not fit in the remaining budget is skipped, not truncated.

- [ ] **Step 1: Write failing tests.** Fixture: `Helper.java` in package `p` (`public class Helper { private int hidden() {return 0;} public String greet(String n) { return n; } }`), `Other.java` in `q`, and `Main.java` in `p` that references `Helper` (no import) and imports `q.Other` and `java.util.List`.

```java
public void testJavaSignaturesIncludeSamePackageAndSkipLibraries() {
    InlineContext c = collectAtCaret("Main.java", defaults());      // helper: runs collect on a pooled thread, waits
    List<String> titles = c.related().stream().map(InlineContext.Snippet::title).toList();
    assertTrue(titles.contains("p.Helper")); assertTrue(titles.contains("q.Other"));
    assertFalse(titles.contains("java.util.List"));
    String helper = textOf(c, "p.Helper");
    assertTrue(helper.contains("String greet(String n)"));
    assertFalse(helper.contains("hidden")); assertFalse(helper.contains("return n"));
}
public void testPrefixSuffixLimitsAndEdges() {                      // Review Focus 5
    String big = "a".repeat(6000) + "<caret>" + "b".repeat(3000);
    InlineContext c = collectText("Big.txt", big, defaults());
    assertEquals(4000, c.prefix().length()); assertEquals(1000, c.suffix().length());
    InlineContext empty = collectText("Empty.txt", "<caret>", defaults());
    assertEquals("", empty.prefix()); assertEquals("", empty.suffix());
}
public void testNonJavaAndDumbModeGiveNoSignatures() {              // Review Focus 4
    assertTrue(collectText("notes.txt", "hello <caret>", defaults()).related().isEmpty());
    DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
        InlineContext c = collectAtCaret("Main.java", defaults());
        assertNotNull(c);
        assertTrue(c.related().stream().noneMatch(s -> s.title().equals("p.Helper")));
    });
}
public void testBudgetAndSwitch() {
    var s = defaults(); s.contextChars = 0;
    assertTrue(collectAtCaret("Main.java", s).related().isEmpty());
    s = defaults(); s.useProjectContext = false;
    assertTrue(collectAtCaret("Main.java", s).related().isEmpty());
}
public void testSensitiveFileIsRejected() {
    assertNull(collectText(".env", "KEY=<caret>", defaults()));
}
```

If `DumbModeTestUtils` is not present in the 2023.3 test framework, use `DumbServiceImpl.getInstance(project).setDumb(true/false)` in a try/finally (test code only).

- [ ] **Step 2: Run** `./gradlew test --tests "com.github.prepushchecker.inline.InlineContextCollectorTest"`. Expected: compile failure.
- [ ] **Step 3: Implement `collect`.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: collect project context for inline suggestions`.

---

### Task 6: Ghost text, Tab and Esc

**Files:**
- Create: `inline/InlineGhostText.java`, `inline/InlineTabHandler.java`, `inline/InlineEscapeHandler.java`
- Modify: `plugin.xml`:
  ```xml
  <editorActionHandler action="EditorTab" implementationClass="com.github.prepushchecker.inline.InlineTabHandler" order="first"/>
  <editorActionHandler action="EditorEscape" implementationClass="com.github.prepushchecker.inline.InlineEscapeHandler" order="first"/>
  ```
- Test: `src/test/java/com/github/prepushchecker/inline/InlineGhostTextTest.java` (`BasePlatformTestCase`)

**Interfaces:**
- Produces (all static, EDT only, state kept in editor user data under one `Key`; at most one suggestion per editor):
  - `void show(Editor editor, int offset, String text)`: replaces any existing suggestion. First line as an inline inlay at `offset`; remaining lines as one block inlay below that line. No-op for blank text or a disposed editor.
  - `boolean isShowing(Editor editor)`, `@Nullable String current(Editor editor)`, `int offset(Editor editor)` (-1 when none).
  - `void dismiss(Editor editor)`: disposes the inlays; safe when none.
  - `boolean typeThrough(Editor editor, int insertOffset, CharSequence inserted)`: if showing, `insertOffset == offset`, and the suggestion starts with `inserted` and is longer, re-show the remainder at `insertOffset + inserted.length()` and return true. If `inserted` equals the whole suggestion, dismiss and return true. Otherwise return false and leave state alone.
  - `boolean accept(Editor editor)`: when showing, insert the text at its offset inside one `WriteCommandAction` (command name "Accept Inline Suggestion"), move the caret to the end, dismiss, return true; false otherwise.
- Handlers: both extend `EditorActionHandler` with a constructor taking the original `EditorActionHandler`. `InlineTabHandler`: `accept(editor)` or delegate. `InlineEscapeHandler`: dismiss when showing, else delegate. `isEnabledForCaret` returns true when showing, else the original's answer.
- Renderer: `EditorCustomElementRenderer`; editor font, italic, foreground from `DefaultLanguageHighlighterColors.INLAY_TEXT_WITHOUT_BACKGROUND` in the editor's colour scheme, falling back to `JBColor.GRAY`. Tabs in the text are painted as the editor's tab width in spaces.

- [ ] **Step 1: Write failing tests**

```java
public void testShowAndAcceptIsOneUndoStep() {
    myFixture.configureByText("A.java", "int a = <caret>;");
    Editor e = myFixture.getEditor();
    InlineGhostText.show(e, e.getCaretModel().getOffset(), "b +\n    c");
    assertEquals("b +\n    c", InlineGhostText.current(e));
    assertEquals(1, e.getInlayModel().getInlineElementsInRange(0, e.getDocument().getTextLength()).size());
    assertEquals(1, e.getInlayModel().getBlockElementsInRange(0, e.getDocument().getTextLength()).size());
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_TAB);
    myFixture.checkResult("int a = b +\n    c<caret>;");
    assertFalse(InlineGhostText.isShowing(e));
    myFixture.performEditorAction(IdeActions.ACTION_UNDO);
    myFixture.checkResult("int a = <caret>;");
}
public void testTabWithoutSuggestionStillIndents() {
    myFixture.configureByText("A.txt", "<caret>x");
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_TAB);
    assertFalse(myFixture.getEditor().getDocument().getText().startsWith("x"));
}
public void testEscapeDismisses() {
    myFixture.configureByText("A.txt", "<caret>");
    InlineGhostText.show(myFixture.getEditor(), 0, "abc");
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ESCAPE);
    assertFalse(InlineGhostText.isShowing(myFixture.getEditor()));
    myFixture.checkResult("<caret>");
}
public void testTypeThrough() {
    myFixture.configureByText("A.txt", "<caret>");
    Editor e = myFixture.getEditor();
    InlineGhostText.show(e, 0, "abc");
    assertTrue(InlineGhostText.typeThrough(e, 0, "a"));
    assertEquals("bc", InlineGhostText.current(e)); assertEquals(1, InlineGhostText.offset(e));
    assertFalse(InlineGhostText.typeThrough(e, 1, "x"));
    assertEquals("bc", InlineGhostText.current(e));
    assertTrue(InlineGhostText.typeThrough(e, 1, "bc"));
    assertFalse(InlineGhostText.isShowing(e));
}
public void testTabInOtherEditorDoesNotAccept() {          // Review Focus 3
    PsiFile a = myFixture.addFileToProject("A.txt", "");
    myFixture.openFileInEditor(a.getVirtualFile());
    Editor ea = myFixture.getEditor();
    InlineGhostText.show(ea, 0, "abc");
    myFixture.configureByText("B.txt", "<caret>x");
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_TAB);
    assertEquals("", ea.getDocument().getText());
    assertTrue(InlineGhostText.isShowing(ea));
}
public void testShowBlankIsNoop() {
    myFixture.configureByText("A.txt", "<caret>");
    InlineGhostText.show(myFixture.getEditor(), 0, "  ");
    assertFalse(InlineGhostText.isShowing(myFixture.getEditor()));
}
```

- [ ] **Step 2: Run** `./gradlew test --tests "com.github.prepushchecker.inline.InlineGhostTextTest"`. Expected: compile failure.
- [ ] **Step 3: Implement the three classes and the registrations.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: render inline suggestions as ghost text with Tab and Esc`.

---

### Task 7: Engine, triggers and actions

**Files:**
- Create: `inline/InlineTriggerPolicy.java`, `InlineSuggestionCache.java`, `InlineSuggestService.java`, `InlineTypedHandler.java`, `InlineEnterHandler.java`, `InlineBackspaceHandler.java`, `InlineSuggestAction.java`, `ToggleInlineSuggestAction.java`
- Modify: `plugin.xml`:
  ```xml
  <applicationService serviceImplementation="com.github.prepushchecker.inline.InlineSuggestService"/>
  <typedHandler implementation="com.github.prepushchecker.inline.InlineTypedHandler"/>
  <enterHandlerDelegate implementation="com.github.prepushchecker.inline.InlineEnterHandler"/>
  <backspaceHandlerDelegate implementation="com.github.prepushchecker.inline.InlineBackspaceHandler"/>
  ```
  and in `<actions>`: `PrePushChecker.InlineSuggest` ("Suggest Code Inline (Pre-Push Checker)", `<keyboard-shortcut keymap="$default" first-keystroke="alt BACK_SLASH"/>`) and `PrePushChecker.ToggleInlineSuggest` ("Toggle Inline AI Suggestions (Pre-Push Checker)").
- Test: `src/test/java/com/github/prepushchecker/inline/InlineTriggerPolicyTest.java`, `InlineSuggestionCacheTest.java` (plain JUnit), `InlineSuggestServiceTest.java` (`BasePlatformTestCase`)

**Interfaces:**
- Consumes: everything from Tasks 1 to 6; `CommitMessageGeneratorService.providerFor(Id)`.
- Produces:
  - `InlineTriggerPolicy`: `enum Event { TYPED, ENTER, BACKSPACE, OTHER }`, `enum Decision { REQUEST, CACHE_ONLY, NONE }`, `record EditorFacts(boolean focused, int caretCount, boolean hasSelection, boolean writable, boolean lookupActive, boolean templateActive, boolean inProject, boolean extensionDisabled, boolean sensitive)`, `static boolean eligible(EditorFacts f)`, `static Decision decide(Event e, EditorFacts f, boolean enabled, InlineSuggestSettings.Trigger effective, boolean paused)`.
  - `InlineSuggestionCache`: `static final int CAPACITY = 20`, `KEY_TAIL = 200`; `void put(String path, String prefix, String suggestion)`, `@Nullable String get(String path, String prefix)`, `void clear()`. Key = path + last `KEY_TAIL` chars of prefix. Access-ordered LRU, synchronized.
  - `InlineSuggestService` (application service, `Disposable`): `static getInstance()`, `void onEvent(Editor editor, InlineTriggerPolicy.Event event)`, `void requestNow(Editor editor)`, `void settingsChanged()`, `boolean isPaused()`, `@TestOnly void setProviderFactory(@Nullable Function<CommitMessageProvider.Id, CommitMessageProvider> f)`, `@TestOnly void waitForIdle()`, `static final int FAILURES_BEFORE_PAUSE = 3`.

Decision table: not `enabled` or not `eligible` gives `NONE`. Eligible means focused, `caretCount == 1`, no selection, writable, no lookup, no template, in project, extension not disabled, not sensitive. Then `BACKSPACE` gives `CACHE_ONLY`; `TYPED`/`ENTER` give `REQUEST` when `effective == AUTO` and not paused, else `CACHE_ONLY`; `OTHER` gives `NONE`.

Service behaviour:
- Constructor registers a document listener and a caret listener on `EditorFactory.getInstance().getEventMulticaster()` with `this` as parent disposable. Document change in an editor that shows a suggestion: pure insertion that `InlineGhostText.typeThrough` accepts keeps it, anything else dismisses. Caret move to an offset other than the suggestion offset dismisses. Dismiss also when the editor loses focus (`FocusChangeListener` via `EditorFactoryListener`, or `EditorEx.addFocusListener`) and on lookup start.
- `onEvent`: build `EditorFacts` on the EDT, `decide`. If a suggestion is still showing after the event (type-through), do nothing. `CACHE_ONLY`: show the cache hit, if any. `REQUEST`: show a cache hit immediately and stop; otherwise cancel the pending request and schedule a new one after `debounceMs` on an `Alarm` (`Alarm.ThreadToUse.POOLED_THREAD`, parent `this`).
- A request captures `document.getModificationStamp()`, caret offset, editor and settings snapshot. On the pooled thread: `InlineContextCollector.collect` (null ends it), `InlinePromptBuilder.build`, `AiRequest.completion(system, user, state.model, isFast ? COMPLETION_FAST_TIMEOUT : COMPLETION_SLOW_TIMEOUT)`, `provider.generate`, `InlineSuggestionCleaner.clean`. Then `invokeLater`: drop the result if the editor is disposed, its project is disposed, the stamp or caret offset changed, or the feature was switched off; otherwise `cache.put` and `InlineGhostText.show`.
- A newer request cancels the older `Future` with `cancel(true)`; a cancelled or stale request never counts as a failure.
- Failures (exception from the provider): automatic requests log at `debug` and increment a counter; at `FAILURES_BEFORE_PAUSE` consecutive failures set paused and post one notification in group `Pre-Push Compilation Checker` ("Inline suggestions paused: <error>") with an action opening the Inline Suggestions settings page. Any success resets the counter and clears paused. `settingsChanged()` clears paused, the counter and the cache.
- `requestNow` (manual): requires `enabled` and `eligible`; ignores trigger, pause and debounce; shows `HintManager.showInformationHint(editor, "Waiting for suggestion...")`; an error shows `HintManager.showErrorHint(editor, message)`; Esc or a document change cancels it.
- Delegates: `InlineTypedHandler.charTyped` calls `onEvent(editor, TYPED)` and returns `Result.CONTINUE`; `InlineEnterHandler.postProcessEnter` calls `onEvent(editor, ENTER)` and returns `Result.Continue`; `InlineBackspaceHandler.charDeleted` calls `onEvent(editor, BACKSPACE)` and returns `false`, `beforeCharDeleted` is empty. Each returns at once when `enabled` is false.
- `InlineSuggestAction`: `getActionUpdateThread() = BGT`; enabled when an editor is present and the feature is on; calls `requestNow`. `ToggleInlineSuggestAction extends ToggleAction`: flips `State.enabled`, calls `settingsChanged()`, dismisses suggestions in all open editors when turning off.

- [ ] **Step 1: Write failing tests**

```java
// InlineTriggerPolicyTest
EditorFacts ok = new EditorFacts(true, 1, false, true, false, false, true, false, false);
@Test public void typedAndEnterRequestInAuto() {
    assertEquals(REQUEST, decide(TYPED, ok, true, Trigger.AUTO, false));
    assertEquals(REQUEST, decide(ENTER, ok, true, Trigger.AUTO, false));
}
@Test public void backspaceIsCacheOnly()  { assertEquals(CACHE_ONLY, decide(BACKSPACE, ok, true, Trigger.AUTO, false)); }
@Test public void otherNeverTriggers()    { assertEquals(NONE, decide(OTHER, ok, true, Trigger.AUTO, false)); }
@Test public void manualOrPausedIsCacheOnly() {
    assertEquals(CACHE_ONLY, decide(TYPED, ok, true, Trigger.MANUAL, false));
    assertEquals(CACHE_ONLY, decide(TYPED, ok, true, Trigger.AUTO, true));
}
@Test public void disabledOrIneligibleIsNone() {
    assertEquals(NONE, decide(TYPED, ok, false, Trigger.AUTO, false));
    // one assertion per fact flipped: unfocused, caretCount 2, selection, read-only, lookup, template,
    // not in project, extension disabled, sensitive -> each NONE
}

// InlineSuggestionCacheTest
@Test public void evictsOldestBeyondCapacity() {
    var c = new InlineSuggestionCache();
    for (int i = 0; i <= InlineSuggestionCache.CAPACITY; i++) c.put("f", "p" + i, "s" + i);
    assertNull(c.get("f", "p0")); assertEquals("s20", c.get("f", "p20"));
}
@Test public void keyUsesPrefixTailAndPath() {
    var c = new InlineSuggestionCache();
    c.put("f", "x".repeat(500) + "tail", "s");
    assertEquals("s", c.get("f", "y".repeat(300) + "x".repeat(196) + "tail"));
    assertNull(c.get("g", "x".repeat(500) + "tail"));
}
```

`InlineSuggestServiceTest`: `setUp` enables the feature, sets `debounceMs = 100`, provider `OLLAMA`, installs a fake provider factory whose provider returns a queued answer or throws a queued exception and counts calls; `tearDown` restores settings and passes `null` to `setProviderFactory`. A helper `settle()` calls `waitForIdle()` then `PlatformTestUtil.dispatchAllEventsInIdeEventQueue()`.

```java
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
    myFixture.type("abc"); settle();
    assertEquals(0, fake.calls());
}
public void testSlowProviderIsShortcutOnly() {
    state().provider = "CLAUDE_CLI";
    myFixture.configureByText("A.txt", "<caret>");
    fake.answer("x");
    myFixture.type("abc"); settle();
    assertEquals(0, fake.calls());
    InlineSuggestService.getInstance().requestNow(myFixture.getEditor()); settle();
    assertEquals("x", InlineGhostText.current(myFixture.getEditor()));
}
public void testStaleAnswerIsDropped() {
    myFixture.configureByText("A.txt", "<caret>");
    fake.answerAfter("old", () -> myFixture.type("z"));   // document changes while the request runs
    InlineSuggestService.getInstance().requestNow(myFixture.getEditor()); settle();
    assertNotEquals("old", InlineGhostText.current(myFixture.getEditor()));
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
    for (int i = 0; i < 3; i++) { fake.fail("boom"); myFixture.type("a"); settle(); }
    assertTrue(InlineSuggestService.getInstance().isPaused());
    int before = fake.calls();
    myFixture.type("b"); settle();
    assertEquals(before, fake.calls());
    InlineSuggestService.getInstance().settingsChanged();
    assertFalse(InlineSuggestService.getInstance().isPaused());
}
public void testBackspaceUsesCacheOnly() {
    myFixture.configureByText("A.txt", "<caret>");
    fake.answer("bc"); myFixture.type("a"); settle();
    fake.answer("zz"); myFixture.type("x"); settle();
    int before = fake.calls();
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE); settle();
    assertEquals(before, fake.calls());
    assertEquals("bc", InlineGhostText.current(myFixture.getEditor()));
}
public void testCaretMoveDismisses() {
    myFixture.configureByText("A.txt", "ab<caret>");
    InlineGhostText.show(myFixture.getEditor(), 2, "c");
    myFixture.getEditor().getCaretModel().moveToOffset(0);
    assertFalse(InlineGhostText.isShowing(myFixture.getEditor()));
}
public void testSensitiveFileSendsNothing() {
    myFixture.configureByText(".env", "<caret>");
    myFixture.type("KEY="); settle();
    InlineSuggestService.getInstance().requestNow(myFixture.getEditor()); settle();
    assertEquals(0, fake.calls());
}
```

- [ ] **Step 2: Run** `./gradlew test --tests "com.github.prepushchecker.inline.*"`. Expected: compile failure.
- [ ] **Step 3: Implement policy and cache; run their two test classes.** Expected: PASS.
- [ ] **Step 4: Implement the service, delegates, actions and registrations.**
- [ ] **Step 5: Run** `./gradlew test --tests "com.github.prepushchecker.inline.*"`. Expected: PASS.
- [ ] **Step 6: Commit** `feat: add inline suggestion engine, triggers and actions`.

---

### Task 8: Settings page, docs, release verification

**Files:**
- Create: `src/main/kotlin/com/github/prepushchecker/inline/InlineSuggestConfigurable.kt`
- Modify: `plugin.xml` (configurable + description bullet), `gradle.properties`, `CHANGELOG.md`, `README.md`

**Interfaces:**
- Consumes: `InlineSuggestSettings` (Task 3), `InlineSuggestService.settingsChanged()` (Task 7).
- Produces: `class InlineSuggestConfigurable : BoundSearchableConfigurable("Inline Suggestions", "prepushchecker.inline", "prepushchecker.inline")`, registered as

  ```xml
  <applicationConfigurable parentId="prepushchecker.settings" id="prepushchecker.inline"
      instance="com.github.prepushchecker.inline.InlineSuggestConfigurable" displayName="Inline Suggestions"/>
  ```

Page, in the style of `CodeReviewConfigurable.kt` (every group below the checkbox is `enabledIf(enabled.selected)`):

- Checkbox "Suggest code inline while typing (ghost text, Tab to accept)", comment "Sends code around the caret to the provider below. Off by default. If another inline completion plugin is active, both may show suggestions."
- Group "Provider": combo of `CommitMessageProvider.Id` (renderer: subclass `SimpleListCellRenderer<T>`, override `customize`, per the API rule); text field "Model" with empty text "Provider default"; link "Configure provider..." opening the AI Provider page (id `com.github.prepushchecker.commitgen.CommitMessageSettingsConfigurable`); a comment row visible only for non-fast providers: "This provider is too slow for automatic suggestions. Use the shortcut (Alt+\)."
- Group "Trigger": radio "Automatically after a typing pause" + int field `debounceMs` (100..2000) "ms"; radio "Only on shortcut (Alt+\)".
- Group "Suggestion": checkbox "Multi-line"; int field "Max lines" (1..30), enabled when multi-line is on.
- Group "Context": checkbox "Include project classes and open files"; int field "Budget" (0..20000) "characters".
- Row "Disabled for file extensions:" text field, comment "Comma-separated, e.g. md, txt".
- Row "Extra instructions:" text area, 3 rows.
- `apply()` calls `super.apply()`, then `InlineSuggestSettings.clamp(state)` and `InlineSuggestService.getInstance().settingsChanged()`.

- [ ] **Step 1: Implement the configurable and registration.**
- [ ] **Step 2: Release edits.** `gradle.properties`: `pluginVersion=2.11.0`. `CHANGELOG.md`: new `## [2.11.0] - 2026-11-16` section under `[Unreleased]`, `### Added` entry "Inline AI suggestions" covering: ghost text with Tab/Esc, own provider and model, automatic on OpenAI/Anthropic/Gemini/Ollama and shortcut-only (Alt+\) on other providers, project context, off by default, settings page path. One feature bullet in the `plugin.xml` `<description>` Features list and in `README.md`.
- [ ] **Step 3: Run** `./gradlew compileJava compileKotlin`. Expected: BUILD SUCCESSFUL, no new deprecation warnings.
- [ ] **Step 4: Run** `./gradlew test`. Expected: all tests pass.
- [ ] **Step 5: Run** `./gradlew verifyPlugin`. Expected: zero deprecated, experimental, scheduled-for-removal and internal API usages.
- [ ] **Step 6: Run** `./gradlew verifyPlugin -PverifyLocalIde="/Applications/IntelliJ IDEA.app"`, then read `build/reports/pluginVerifier/IU-*/plugins/*/*/{deprecated,internal-api,experimental-api}-usages.txt`. Expected: empty or absent. The task itself fails on about 17 unresolved `com.intellij.openapi.vcs.*` / `dvcs` classes: known local-install artifact, not a failure of this work.
- [ ] **Step 7: Manual check with `./gradlew runIde`:** enable the feature with Ollama, type in a Java file, see ghost text, Tab accepts, Esc dismisses, Alt+\ works, Tab still indents with the feature off. Record what was and was not checked.
- [ ] **Step 8: Run** `./gradlew buildPlugin`. Expected: `build/distributions/pre-push-compiler-plugin-2.11.0.zip`.
- [ ] **Step 9: Commit** `feat: inline suggestions settings page, release 2.11.0`.
