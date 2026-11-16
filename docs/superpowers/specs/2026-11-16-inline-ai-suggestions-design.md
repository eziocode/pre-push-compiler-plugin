# Inline AI Suggestions: Design

Date: 2026-11-16
Status: awaiting approval
Target release: 2.11.0

## Intent

Give the plugin Copilot-style inline code suggestions: grey "ghost text" at the caret that
continues the line or block being typed, accepted with Tab. Suggestions should reflect the
project's own code (its classes and method signatures), not only the current file. The feature
has an on/off switch and its own settings page.

Why: the plugin already carries a full AI provider layer (commit messages, code review). Developers
who have configured a provider there can get next-line completion from the same plugin without
installing a separate assistant.

## Goals

- Ghost text appears after a short typing pause, or on a shortcut.
- Tab accepts, Esc dismisses, typing the same characters as the suggestion keeps it.
- Prompt includes project knowledge: code before and after the caret, signatures of project
  classes the file uses, excerpts of other open files.
- Inline suggestions have their **own provider and model**, independent of the provider used
  for commit messages and review (decided by the user).
- One settings page: enable/disable plus the options listed under Settings.
- Works on every supported IDE build (since 233) with zero new Plugin Verifier findings.

## Non-goals (v1)

- Streaming partial output into the editor.
- Accepting word by word or line by line (Tab accepts the whole suggestion).
- Cycling between alternative suggestions.
- A project index or embeddings search. "Project knowledge" is what PSI and open editors give.
- A per-repository instructions file for completion (one free-text "extra instructions" field only).
- Usage or cost statistics.

## Anti-goals

- Sending code to an AI provider without the user having switched the feature on. Default is **off**.
- Sending files that `SensitivePaths.isExcluded` rejects.
- Any editor freeze: no network, process or PSI work on the EDT.
- Stealing Tab or Esc when no suggestion of ours is showing.
- New deprecated, experimental, internal or scheduled-for-removal API usage.

## Constraints

- `sinceBuild = 233`, no upper bound. `.claude/rules/jetbrains-api-compat.md` applies in full.
- Read actions through `ReadActions.compute` (background thread only).
- Existing providers are synchronous: `CommitMessageProvider.generate(AiRequest)`.
- CLI providers start a process per request (seconds). HTTP providers answer in well under a second
  to a few seconds.

## Approaches

1. **Platform `InlineCompletionProvider`.** The IDE's own ghost-text API. Exists on 233 but was
   experimental there and its suggestion types changed in 2024.1, so one implementation cannot
   cover 233 and current builds, and the Verifier flags it. Rejected.
2. **Own ghost text on the inlay API (chosen).** `InlayModel.addInlineElement` /
   `addBlockElement` with an `EditorCustomElementRenderer`, plus `editorActionHandler`
   registrations for Tab and Esc. All stable since long before 233. More code (we own rendering,
   lifecycle and key handling) but one implementation for all builds and no Verifier noise.
3. **Entries in the normal completion popup** (`CompletionContributor`). Stable, little code, but it
   is a popup list, not next-line ghost text, and the contributor would have to block on the network.
   Rejected: not what was asked for.

Backend, picked by the user: inline suggestions get their own provider and model setting.
Automatic suggestions run only on the fast HTTP providers; a CLI provider can be chosen but is
shortcut-only. The alternative (reuse the commit/review provider, shortcut-only for everyone) was
simpler but not Copilot-like.

## Design

New package `com.github.prepushchecker.inline` (Java, settings page in Kotlin like the others).

### Settings

`InlineSuggestSettings`: application-level `PersistentStateComponent`, registered as
`applicationService`. Application-level because providers and API keys are application-level.

| Field | Default | Meaning |
|---|---|---|
| `enabled` | `false` | Master switch |
| `provider` | `OLLAMA` | A `CommitMessageProvider.Id` |
| `model` | `""` | Blank = that provider's model from the AI Provider page |
| `trigger` | `AUTO` | `AUTO` (after a typing pause) or `MANUAL` (shortcut only) |
| `debounceMs` | `350` | Typing pause before an automatic request, 100 to 2000 |
| `multiLine` | `true` | Off = suggestion cut at the first line break |
| `maxLines` | `8` | Cap on suggestion length, 1 to 30 |
| `useProjectContext` | `true` | Add class signatures and open-file excerpts to the prompt |
| `contextChars` | `6000` | Budget for that extra context, 0 to 20000 |
| `disabledExtensions` | `""` | Comma-separated file extensions with no suggestions (e.g. `md, txt`) |
| `extraInstructions` | `""` | Free text appended to the system prompt |

Keys, base URLs and CLI paths stay on the existing AI Provider page; this page links to it.

Fast providers (automatic trigger allowed): `OPENAI`, `ANTHROPIC`, `GEMINI`, `OLLAMA`. Every other
id is treated as slow: effective trigger is `MANUAL` whatever the setting says, and the page shows a
note saying so.

`InlineSuggestConfigurable`: `applicationConfigurable` under `prepushchecker.settings`, display name
"Inline Suggestions", Kotlin UI DSL in the style of `CodeReviewConfigurable`. The enable checkbox
carries a comment that code around the caret is sent to the chosen provider. All other groups are
`enabledIf` the checkbox.

```
[x] Suggest code inline while typing (ghost text, Tab to accept)
    Sends code around the caret to the provider below.

Provider   [ Ollama (Local)        v]   Model [ qwen2.5-coder     ]   Configure provider...
Trigger    (o) Automatically after [350] ms pause   ( ) Only on shortcut (Alt+\)
Suggestion [x] Multi-line   Max lines [8]
Context    [x] Include project classes and open files   Budget [6000] chars
Disabled for extensions [ md, txt            ]
Extra instructions      [                     ]
```

### Request path

- `AiRequest`: new `Kind.COMPLETION`, factory `completion(system, user, model)`, 256 output tokens,
  15 s timeout, and a new nullable `model` component (null for all existing factories).
  `structured()` becomes true only for `REVIEW` and `FIX`.
- The four HTTP providers use `request.model()` when non-null and honour short timeouts
  (`OllamaProvider` currently forces at least 120 s). CLI providers pass the model through where
  they already take one. Providers must not apply commit-message cleanup to `COMPLETION` answers.
- CLI providers (design author's call): supported properly on the shortcut, not best-effort.
  `AiCliRunner.finish` gets a third mode for `COMPLETION`: strip terminal and agent noise (ANSI
  codes, transcript and thinking lines, one outer code fence) but never apply
  `AiOutputSanitizer.clean`, whose commit-message rules would delete real code. Unit-tested per
  CLI with captured sample output. Timeout for slow providers is 60 s instead of 15 s; the
  shortcut shows a "Waiting for suggestion..." editor hint meanwhile, and Esc cancels.
- Provider instance comes from the existing `CommitMessageGeneratorService.providerFor(id)`.

### Context ("project knowledge")

`InlineContextCollector`, run on a pooled thread inside `ReadActions.compute`:

- Prefix: up to 4000 chars before the caret. Suffix: up to 1000 chars after it.
- File path relative to the project, and language name.
- If `useProjectContext`, within `contextChars`, in this order:
  1. Java files only, smart mode only: every class reference in the file that resolves to a class
     in project sources (not libraries), same-package classes included, whether imported or not.
     Ordered by distance of the nearest reference from the caret, deduplicated. For each: class
     header, fields, non-private method signatures, no bodies. Wildcard and static imports are not
     expanded on their own; a class counts only if the file references it. (Design author's call.)
  2. Excerpts (first 60 lines) of other open editor tabs with the same file type.
- Any file `SensitivePaths.isExcluded` rejects is skipped, as context and as a target.

`InlinePromptBuilder` (pure, unit-tested): system prompt says "output only the text to insert at
`<CURSOR>`, no explanation, no code fences", plus single-line or max-lines limits and
`extraInstructions`; user prompt is the context blocks followed by prefix + `<CURSOR>` + suffix.

`InlineSuggestionCleaner` (pure, unit-tested): strips code fences, strips an echoed prefix, cuts at
`maxLines` (or the first line break), drops trailing text that repeats the start of the suffix,
returns nothing for an empty or whitespace-only result.

### Engine

`InlineSuggestService`, application service, `Disposable`:

- Listens through `EditorFactory.getEventMulticaster()` (document and caret listeners, tied to the
  service's lifetime).
- A document change schedules a request when all hold: feature enabled, effective trigger `AUTO`,
  the change came from typing in the focused editor, single caret, no selection, document writable,
  no completion popup or live template active, file belongs to an open project, extension not
  disabled, path not sensitive.
- What counts as typing (design author's call): a typed character or Enter schedules a request.
  Backspace only looks in the cache and never sends a request. Paste, auto-inserted closing pairs,
  completion-popup insertions, undo/redo, reformat and any other change do not trigger.
- Debounce with `Alarm` on a pooled thread. Each request records the document modification stamp
  and caret offset; an answer is shown only if both still match. A newer request cancels the older
  one (`Future.cancel(true)`).
- Small LRU cache (20 entries) keyed by file plus prefix tail, so backspacing to an earlier state
  shows the earlier suggestion without a request.
- Manual trigger: action `PrePushChecker.InlineSuggest`, default shortcut Alt+\ (Option+\ on macOS),
  same path without debounce. Works when the feature is enabled, on any provider.
- Second action `PrePushChecker.ToggleInlineSuggest` ("Toggle Inline AI Suggestions") flips `enabled`.
- Failures: automatic requests fail silently (logged). After three failures in a row, one
  notification with a link to the settings page, and automatic requests pause until a manual trigger
  succeeds or settings change. A manual request that fails shows the error as an editor hint.

### Ghost text

`InlineGhostText`, EDT only, at most one suggestion per editor:

- First line: inline inlay at the caret offset. Remaining lines: one block inlay below the caret
  line. Renderer paints in the editor font with the scheme's inlay-hint foreground, italic.
- Dismissed on: caret move, any document change that is not type-through, focus loss, Esc, editor
  release, completion popup opening.
- Type-through: if the typed text equals the start of the suggestion, the suggestion is shortened
  instead of dismissed and no new request is made.
- Tab: `editorActionHandler` for `EditorTab`, ordered first. If the editor shows our suggestion,
  insert it in one write command (single undo step) and move the caret to its end; otherwise call
  the original handler. Esc: same pattern on `EditorEscape`.

## Testing

- Unit: `InlinePromptBuilder`, `InlineSuggestionCleaner`, trigger gating and staleness policy,
  cache, `AiRequest` (`structured()` for every kind).
- Platform test (`BasePlatformTestCase`) with a fake provider: suggestion shows, Tab inserts and is
  one undo step, Esc and caret move dismiss, type-through shortens, Tab without a suggestion still
  indents.
- Existing commit-message and review tests stay green (provider changes).
- Verification per `.claude/rules/jetbrains-api-compat.md`: `compileJava`, `verifyPlugin`, and
  `verifyPlugin -PverifyLocalIde=...` with empty deprecated and internal-API reports.

## Release

`pluginVersion` 2.11.0, `CHANGELOG.md` entry, feature bullet in the `plugin.xml` description and
README.

## Left to the builder

- Exact prompt wording and renderer colours.
- How each CLI provider receives a model override.
- Class and file split inside the `inline` package.

## Known limitation

If another inline-completion plugin (GitHub Copilot, JetBrains full-line completion) is active, both
may show ghost text at once. No stable API detects this. The feature is off by default and the
settings page says so.
