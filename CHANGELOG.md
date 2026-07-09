# Changelog

All notable changes to **Pre-Push Compilation Checker** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.9.1]

### Fixed

- **Branch-wise prefix convention not followed (regression).**
  The AI commit message generator resolved the current branch by shelling out to `git` against `project.getBasePath()` *first*, only consulting IntelliJ's Git integration as a fallback. In multi-root projects — or whenever the project base path is not the repository root that owns the staged changes — this returned the wrong or a stale branch, so the rules-file branch gate selected the wrong prefix (while GitHub Copilot, which reads the IDE's live branch, worked correctly with the same `.md`).
  Fix: branch resolution now consults IntelliJ's **`GitRepositoryManager` → `GitRepository.getCurrentBranch()`** (the IDE's live, authoritative repository model that watches `.git/HEAD`) as the primary source for every repo root, falling back to a `git` subprocess only when the model is not populated (detached HEAD, non-git4idea/test contexts). The branch that owns the collected diff is threaded straight through into both the system prompt (prefix/`{branch}` substitution and the git-facts block) and the user prompt (`Current branch: <name>`), so the prefix reflects the actually checked-out branch. No branch names or prefixes are hardcoded — the rules file still drives every decision, repository-agnostically.

- **Incorrect commit SHA copied to clipboard.**
  The clipboard read `HEAD` from the raw `commit.getRoot()` / panel-root path, which can point at a submodule directory or an unrelated root in multi-root layouts, so the copied id was not the commit git actually created.
  Fix: every committed/pushed root is now mapped to its enclosing repository via IntelliJ's Git integration (**`getRepositoryForRootQuick` / `getRepositoryForFileQuick`**) before the authoritative `git rev-parse HEAD` is run against that repository's canonical root. This applies to both the *After Commit* and *After Push* triggers. The copied SHA is therefore always the real Git commit id, across any repository. Base-path and VCS-log lookups are retained only as ordered fallbacks.

### Tests

- Added regression coverage asserting the resolved active branch is threaded into the prompt (`CommitMessagePromptBuilderTest`) and that repository-root selection for the clipboard SHA is prefix-safe in multi-root layouts (`CommitShaClipboardCheckinHandlerTest`).

---

## [1.8.9]

### Fixed

- **`ISSUEFIX:` not applied on the default branch (branch-gate mis-classification).**
  1.8.8 resolved the default branch only from git (`git symbolic-ref refs/remotes/origin/HEAD`). When `origin/HEAD` is unset locally, or the team's default branch name does not match what `origin/HEAD` points to, the gate wrongly classified the real default branch as *non-default* and therefore suppressed `ISSUEFIX:` on it (while still applying it elsewhere).
  Fix: added an explicit **Default branch (rules gate)** setting under *Settings → AI Commit Message Generator*. When set it is authoritative for the branch gate, so `ISSUEFIX:`/`DOCS:` are treated as allowed on that branch and forbidden on every other branch — exactly as the rules file specifies. Git auto-detection (now also consulting `init.defaultBranch`) is used only when the field is blank, and when nothing can be resolved the plugin no longer asserts a wrong verdict — it instructs the model to run Step 0 itself.
  Branch-name comparison is now case-insensitive and tolerant of `refs/heads/` / `origin/` prefixes, and the verdict block spells out the allowed/forbidden prefixes explicitly.

### [1.8.8]

### Fixed

- **Branch-specific commit rules applied inconsistently.**
  The branch gate in a team rules file (e.g. `.github/git-commit-instructions.md`) relied entirely on the AI running `git branch --show-current` and guessing whether the current branch was the repository default. As a result the prefix (`ISSUEFIX:` vs `METHOD_ENTRY:`/`TESTCASE:`) applied on some branches but not others.
  Fix: the plugin now resolves the current branch **and** the repository default branch (`git symbolic-ref --short refs/remotes/origin/HEAD`, falling back to `git rev-parse --abbrev-ref origin/HEAD`) in code, then injects a concrete *default / non-default* verdict into the system prompt so the branch gate is deterministic instead of guessed by the model.

- **`.github/git-commit-instructions.md` not auto-detected.**
  Added it to the default rules-file candidate list (previously only `.github/commit-instructions.md` and `COMMIT_RULES.md` were auto-detected), so teams using this filename get their rules applied without setting a custom path.

- **YAML front-matter leaked into the AI prompt.**
  A leading `--- apply: always ---` block is now stripped from the rules file before it is sent to the model. `---` horizontal rules elsewhere in the body are preserved.

- **Wrong commit SHA copied to clipboard (After Push trigger).**
  The pushed tip was chosen by the highest committer *timestamp*, which is not the branch head after a rebase, amend, or cherry-pick (commit dates are not monotonic with DAG order), so a non-tip commit could be copied.
  Fix: the tip is now selected **topologically** — the commit in the push set that is not a parent of any other commit — with the timestamp heuristic retained only as a fallback when no unique tip can be determined.

- **Stale or wrong SHA copied to clipboard (After Commit trigger).**
  The primary source was git4idea's `repo.update()` + `getCurrentRevision()`, whose refresh is asynchronous and could return the pre-commit (parent) revision.
  Fix: the primary source is now a synchronous `git rev-parse HEAD` subprocess run after the commit object is written (which always returns the newly created commit). The git4idea model and `project.getBasePath()` lookups are retained as ordered fallbacks.

---

## [1.8.7]

### Fixed

- **Wrong SHA copied to clipboard after push (After Push trigger).**
  The previous implementation called `git rev-parse HEAD` from `commit.getRoot().getPath()`. When a push includes submodule-update commits their root resolves to the submodule directory, so `HEAD` there is the submodule's tip — not the commit the user actually pushed.
  Fix: reads the SHA directly from `VcsFullCommitDetails.getId()` (the commit with the highest timestamp across all push details), with no subprocess and no dependency on root-path resolution. The old git-subprocess loop and `project.getBasePath()` fallback are retained in order.

- **Stale or wrong SHA copied to clipboard after commit (After Commit trigger).**
  The previous implementation iterated `panel.getRoots()` with a bare `git rev-parse HEAD` subprocess, which could return a pre-commit cached value on some systems or pick the wrong repository root in multi-root projects.
  Fix: uses `GitRepositoryManager → repo.update() → getCurrentRevision()` as the primary path (forces a fresh read of `.git/HEAD` through git4idea's own model). The subprocess-per-root loop and `project.getBasePath()` are retained as ordered fallbacks.

---

## [1.8.5]

### Fixed

- **Auto-copy SHA not working on macOS (After Commit).**
  `CheckinProjectPanel.getRoots()` was called from a background thread inside `executeOnPooledThread`. On macOS, AWT's stricter threading model caused `getRoots()` to return an empty/invalid result so the HEAD SHA was never resolved and nothing reached the clipboard.
  Fix: roots are now captured on the EDT (where `checkinSuccessful()` is invoked) before dispatching to the pooled thread.

- **Auto-copy SHA not working on macOS (After Push) — edge-case fallback.**
  When `PushInfo.getCommits()` returned commits without a resolvable `VirtualFile` root (force-push or empty-push edge cases), the SHA lookup fell through with a null result and no copy occurred.
  Fix: added a fallback to `project.getBasePath()` so the HEAD SHA is still resolved even when commits carry no root.

- **Settings panel: Format / Copy-on sub-row misaligned.**
  The "Format: … Copy on: …" row below the "Copy commit SHA to clipboard automatically" checkbox was shifting right in the tool window. In a `BoxLayout Y_AXIS` container, the row's default `CENTER_ALIGNMENT` conflicted with the `LEFT_ALIGNMENT` of the checkboxes above it.
  Fix: `shaSubPanel.setAlignmentX(Component.LEFT_ALIGNMENT)`.

---

## [1.8.4]

### Added

- **Configurable clipboard SHA settings.** The Compilation Checker settings panel now exposes three options for the auto-copy feature introduced in 1.8.3:
  - **Enable / disable** — "Copy commit SHA to clipboard automatically" checkbox (default: on). Unchecking it disables the feature entirely while preserving your format and trigger preferences.
  - **SHA format** — `Full SHA (40 chars)` / `Short SHA (7 chars)` radio buttons. Short SHA uses the standard 7-character abbreviation.
  - **Copy trigger** — `After Push` / `After Commit` radio buttons. "After Push" is the previous default — the SHA is copied once the push is allowed. "After Commit" copies immediately after a successful IDE commit (before the push step), implemented via a `CheckinHandlerFactory`.

---

## [1.8.3]

### Added

- **Commit SHA auto-copied to clipboard after push.**
  Every time the pre-push check passes and the push is allowed, the full 40-character
  HEAD commit SHA is automatically written to the system clipboard. A balloon notification
  *"Commit SHA Copied"* confirms the action in the IDE — the SHA is immediately ready to
  paste into a PR description, ticket, Slack message, or anywhere else.
  Covers all push-allowed paths: clean compile pass, IDE-problems overridden with
  "Push Anyway", errors resolved, and no-source-change pushes.

---

## [1.8.2]

### Fixed

- **Branch-specific prefix not applied in AI commit message generator.**
  The `{branch}` placeholder in the *Prefix / ticket template* setting was never resolved —
  the raw token `{branch}` was sent verbatim to the AI, which either echoed it or silently
  dropped the instruction. The generator now reads the current branch name via
  `GitRepositoryManager` (with a `git rev-parse --abbrev-ref HEAD` subprocess fallback) and
  substitutes the placeholder before building the prompt.
  Degrades gracefully to an empty string on detached HEAD.

- **AI ignores branch gate in Decision Tree rule files — wrong prefix selected.**
  When a rules file (`.github/commit-instructions.md` / `COMMIT_RULES.md`) contains a
  Decision Tree with a branch gate (e.g. "default branch → `ISSUEFIX:`, feature branch →
  `TESTCASE:`"), the AI was producing the wrong prefix (`TESTCASE:`, `REFACTOR:`) even on
  the default branch. Root causes fixed:
  - The rules file is now placed **at the top** of the system prompt so it is evaluated
    before any other instruction, and a `=== … OVERRIDE all generic guidance ===` header
    makes its precedence explicit.
  - The generic Conventional Commits type list (`feat, fix, refactor, test, …`) is now
    **suppressed** when a rules file is present — it was the primary cause of the wrong
    prefix because the AI pattern-matched diff content to those generic types before
    applying the branch gate.
  - A `CRITICAL ENFORCEMENT` directive is injected immediately after the rules file content,
    naming the current branch and mandating step-by-step execution of the Decision Tree.
  - Staged file paths are extracted from the diff and listed as `Staged files:` in the user
    prompt so the AI can evaluate file-type steps (doc vs. non-doc) without parsing the
    full diff body.

- **AI unaware of current branch when applying rule-file prefix rules.**
  The system prompt previously contained no branch context, so branch-specific instructions
  could not be honoured. The user prompt now includes `Current branch: <name>` and the
  system prompt includes the branch in the enforcement directive.

### Changed

- **`.github/commit-instructions.md`** — added *Branch-specific prefix* and
  *Decision Tree support* sections documenting the `{branch}` placeholder, prompt
  structure, and enforcement behaviour.

---

## [1.8.1]

### Added

- **ChatGPT browser OAuth device sign-in flow.** The Codex provider in Settings now supports a full OAuth 2.0 device-authorization flow: a browser tab opens for code approval, the plugin polls for the token in the background, and the result is stored securely in IntelliJ PasswordSafe. The Codex settings card shows live sign-in status with **Sign In**, **Sign Out**, and **Refresh** actions.
- **CLI auth status in Settings.** The settings card for CLI-based providers (GitHub Copilot, llm, Claude CLI) now shows a live auth/availability status label so you can confirm a provider is ready without leaving the IDE.

### Fixed

- **Multi-repo diff collection.** The AI commit message generator now uses `GitRepositoryManager` to gather diffs from all Git roots in the project, with a per-root fallback chain (staged → unstaged → HEAD diff). Fixes blank prompts in mono-repos with sub-modules and multi-root workspaces.
- **EDT blocking in Codex auth refresh.** Auth-file reads (`~/.codex/auth.json`) and status checks are now performed on a background thread; the settings label updates on the EDT, eliminating UI freezes when the Codex card is opened.
- **Browse listener wiring for rules-file picker.** The file chooser browse listener was not wired correctly in certain IDE versions, preventing the rules-file path from being populated. Fixed by using the updated `addBrowseFolderListener` overload.
- **Commit control cast in Git Commit dialog.** Resolving the commit message control now uses `e.getData(...)` directly with `COMMIT_WORKFLOW_UI` instead of casting, fixing a `ClassCastException` on some IntelliJ builds.
- **OAuth browser launch on all platforms.** `Desktop.getDesktop().browse(...)` is now wrapped with per-OS fallbacks (`xdg-open`, `open`, `start`) so the sign-in browser tab opens correctly on Linux and older macOS JDK configurations.

### Changed

- **`CommitWorkflowUi` API for commit message injection.** The "Insert AI message" action now resolves the commit panel via `COMMIT_WORKFLOW_UI` and calls `commitMessageUi` — matching the stable public API and avoiding internal casts that broke on recent platform builds.
- **AI plugin availability checks via `PluginManagerCore`.** JetBrains AI plugin detection now verifies that the plugin's class loader is present (via `PluginManagerCore`) instead of calling the deprecated `isEnabled()` method, fixing false "AI Assistant not available" warnings on current IDE versions.
- **Removed OAuth token-refresh retry.** The Codex OAuth flow no longer retries a stale token on HTTP 401; it fails fast and prompts re-authentication instead, preventing silent infinite-retry loops.

---

## [1.8.0]

### Added

#### AI Commit Message Generator
A full AI-powered commit message generator, accessible from two places:
- ⚡ **Compilation Checker tool window** toolbar button
- ⚡ **Git Commit dialog** action (`Vcs.MessageActionGroup` — same row as the Copilot button)

The button is **disabled/greyed out** when there are no staged or local changes (matches Copilot's visual behaviour).

#### Nine AI providers

| Provider | Auth | Notes |
|----------|------|-------|
| **JetBrains AI** | IDE sign-in | Requires AI Assistant plugin (`com.intellij.ml.llm`), optional soft dep |
| **OpenAI** | API key → PasswordSafe | Calls `gpt-4o` or any configured model |
| **Anthropic** | API key → PasswordSafe | Calls Claude 3.5 Sonnet |
| **Google Gemini** | API key → PasswordSafe | Calls Gemini 1.5 Flash |
| **Ollama** | None | Local server, configurable base URL (default `localhost:11434`) |
| **Codex (ChatGPT Account)** | ChatGPT OAuth via Codex app | Reads `~/.codex/auth.json` written by the Codex desktop app — **no API key needed** |
| **GitHub Copilot** | `gh auth login` | Calls Copilot Chat API via `gh auth token` |
| **Claude CLI** | `ANTHROPIC_API_KEY` / PasswordSafe | Reads env var or stored key, calls Anthropic API |
| **llm CLI** | Per-provider key (`llm keys set`) | Simon Willison's tool — 60+ model providers via plugins |

#### Custom commit message rules
- **Conventional Commits** format enforcement (`feat:`, `fix:`, `chore:`, …)
- **Max subject line length** (default 72 characters)
- **Prefix / ticket template** (e.g. `[JIRA-{branch}]`)
- **Language / tone** (e.g. "English, concise")
- **Auto-detect scope** from changed file paths
- **Extra free-form instructions** appended to every prompt

#### Project-level rules file (team sharing)
Place `.github/commit-instructions.md` (or `COMMIT_RULES.md`) in the project root and commit it. The plugin appends it verbatim to the AI system prompt — every developer picks up the same rules with no IDE settings change. A starter template is included at `.github/commit-instructions.md`.

- **Browse button** in Settings — IntelliJ file chooser rooted at the open project, filtered to `.md` / `.txt`
- Selected paths are **auto-converted to project-relative** so the setting is portable across machines
- **Live status label** shows whether the file was found (`✓`) or is missing (`⚠`)

#### Settings page
**Settings → Tools → Pre-Push Checker — AI Commit Message Generator**
- Per-provider auth configuration with per-provider hints
- **Auto-detect buttons** for CLI providers (probes shell PATH, Homebrew, npm-global, asdf, pip, Cargo)
- Codex card shows live ChatGPT sign-in status and **"Open Codex App"** button
- GH Copilot card shows live `gh auth` status
- **Test Connection** button for all providers
- Model override per provider
- All commit message rule options

### Security
- **Shell injection guard reinstated** in `CliPathResolver.whichViaShell()` — only bare executable names matching `[A-Za-z0-9._+-]+` are passed into `sh -c` scripts, blocking injection via user-controlled Settings values (e.g. a compromised `commitMessageGenerator.xml`). Guard applied at both the method entry (chokepoint) and the call-site.

### Fixed
- **CLI auto-detection** — all CLI providers now resolve their executable by sourcing the user's rc files (`.zshenv`, `.zshrc`, `.bashrc`, etc.) and probing common install locations. Fixes "tool not found" errors when IntelliJ is launched as a GUI app with a stripped PATH.
- **`node: No such file or directory` (exit 127)** — resolved by injecting the shell-augmented PATH into every `ProcessBuilder` subprocess environment.
- **Codex CLI `stdin is not a terminal`** — the Codex CLI is a TUI agent that requires a real TTY. Fixed by switching to direct HTTP API calls using the ChatGPT OAuth token from `~/.codex/auth.json`, eliminating the subprocess entirely.
- **Codex CLI `unexpected argument` errors** — various `codex` CLI versions reject positional arguments and flags differently. The HTTP approach removes all CLI argument parsing issues.
- **Infinite "Contacting AI provider…"** — the Python PTY subprocess never exited. Fixed by removing the subprocess and using the direct HTTP approach.

---

## [1.7.2]

### Fixed
- **Consistent `.kts` classification.** Kotlin script files are now recognized as compilable sources by both the in-IDE push handler and the external loopback server, eliminating an inconsistency where a pushed `.kts` source file was visible to the loopback path but invisible to the in-IDE path. Build scripts such as `build.gradle.kts` / `settings.gradle.kts` remain classified as build files.
- **Stricter loopback `CHECK` protocol.** The per-project pre-push server now requires an exact `CHECK` request line instead of any line starting with `CHECK`, rejecting malformed clients with `ERR unknown-request`.
- **Leaked subprocess on snapshot-build cancellation.** When a strict A/B snapshot validation is cancelled (or fails with any unexpected throwable while waiting on git / Gradle / Maven), the spawned process is now force-destroyed before the exception propagates, so a cancelled check no longer leaves a multi-minute Gradle/Maven build running in the background.
- **Race in port-file publication.** The loopback server's `.idea/pre-push-checker/server.port` is now written via a temp file and atomic rename, so concurrent hook reads cannot observe a zero-byte / partial file and silently fall back to the build-tool path.
- **`ExternalPushErrorLoader.LAST_PARSED` no longer leaks across project open/close cycles.** The static dedup fingerprint map now drops its entry when the project is disposed, so repeated open/close cycles cannot accumulate unbounded entries.

---

## [1.7.1]

### Changed
- **Dedicated repair-hook icon.** The Compilation Checker tool window now uses a plugin-specific repair icon for the Recheck / Repair Git Hooks action instead of the generic refresh icon.

### Fixed
- **Stale build-output fallback false positives.** When the external Maven/Gradle fallback reports `bad class file` errors caused by missing class files under `target/classes`, `target/test-classes`, or `build/classes`, the hook now refreshes generated class output and retries once before blocking. The hook log now reflects the refreshed compile result instead of stale cache output.

---

## [1.7.0]

### Added
- **Hook repair action.** The Compilation Checker tool window can now repair and normalize managed Git hooks from the IDE, restoring missing or stale hook wiring without a manual reinstall.

### Changed
- **Improved `core.hooksPath` handling.** Hook installation, repair, exclude cleanup, and current-state inspection now resolve custom hook directories and shared git directories more reliably, including canonical path comparisons for symlinked hook locations.

---

## [1.6.0]

### Added
- **Clean-commit ledger.** `CompilationErrorService` now tracks HEAD SHAs that have been verified clean by a successful compile. After a rebase, merge, or pull that fast-forwards onto an already-verified upstream commit, the pre-push handler reuses the cached clean verdict instead of recompiling. Backed by the new `GitOperations` utility, which inspects local git state (HEAD SHA, merge/rebase-in-progress, upstream tracking) without shelling out for every check.
- **Optional pre-compile rebase advisory (`prepushchecker.rebasePrecheck.enabled`, off by default).** When enabled, `PrePushCompilationHandler` fetches each push root before scheduling the compile and prompts the user to rebase first if the remote is ahead. Ensures the build runs against the integrated tree so locally-clean-but-remotely-stale pushes do not surprise the CI. Off by default because every push would otherwise pay a network round-trip.
- **Repo-change-driven warmup compile.** `CompilationWarmupService` now subscribes to `GIT_REPO_CHANGE`, so branch switches, fetches, pulls, and rebases trigger a debounced background project compile (30s cooldown to absorb bursts of state-change events from a single git operation). The compiler cache is hot for the integrated tree by the time the user pushes, turning most post-VCS-operation pushes into no-ops.

---

## [1.5.3]

### Fixed
- **Generated getter/setter/builder false positives on first push.** Terminal, force-push, and GUI git client fallback compilation now retries once with full compile scope (`classes testClasses` / `test-compile`) when the narrow compile reports only generated-style missing-symbol errors (`get*`, `set*`, `is*`, `*Builder`). If the full retry still contains only those generated-symbol errors, the hook treats the output as a non-blocking false positive instead of requiring a second push.
- **Force Push retry bypass.** The plugin's force-push action now writes the one-shot bypass token before running `git push --force-with-lease`, preventing the managed hook from blocking the force-push retry.

---

## [1.5.1]

### Fixed
- **External push false-block race (Sublime Merge / terminal).** The managed hook now waits longer for IntelliJ's local compile service to come online before falling back to Maven/Gradle, which fixes first-attempt push failures that were passing on an immediate second push.
- **Generated getter/setter fallback noise.** When build-tool fallback reports generated-symbol style `cannot find symbol` failures (`get*`/`set*`/`is*`) and IntelliJ is running, the hook now retries IntelliJ incremental compile once before aborting the push.
- **Uninstall leftovers in previously-open repos.** The plugin now tracks managed repositories globally and proactively cleans their managed hook/cache/exclude entries on uninstall, not only for projects currently open in the IDE.

### Changed
- Added explicit fallback reason logging when IntelliJ incremental compile cannot be reached, making Sublime/terminal hook failures directly diagnosable from `.idea/pre-push-checker/last-run.log`.

---

## [1.4.8]

### Fixed
- **Reduced false push-blocks in external fallback compilation.** When IntelliJ socket compilation is unavailable and the managed hook falls back to Maven/Gradle, failures that look like generated-symbol noise (for example Lombok-style `get*`/`set*`/`is*` or `*Builder` `cannot find symbol`) are no longer treated as blocking if they do not reference files included in the outgoing push.

### Changed
- Refactored managed hook fallback checks into explicit script helpers (`compile_failure_touches_pushed_files`, `looks_like_generated_symbol_false_positive`) and tracked build-file changes separately so strict blocking behavior is preserved for build-graph edits.

---

## [1.4.6]

### Changed
- **Prefer IDE compilation more reliably when IntelliJ is running in background.** If the first socket attempt fails, the managed pre-push hook now detects a running IntelliJ process and retries IDE compile checks briefly before falling back to Gradle/Maven.

### Performance
- **Fallback compile result reuse for unchanged HEAD.** When fallback mode is used (IDE unavailable), the hook now reuses the last successful fallback result for the same `HEAD` and compile mode (`main`/`test`/`all`), avoiding redundant rebuilds on repeat pushes with no new commits.

---

## [1.4.5]

### Fixed
- **External IntelliJ fast-path false positives.** Terminal, Sublime Merge, and other external git clients no longer block pushes from stale IntelliJ editor problem-cache entries. The loopback server now reuses only cached successful compile verdicts; cached failures are rechecked with `CompilerManager.make` before the hook reports an error. This prevents stale generated-symbol failures for Lombok-style getters, setters, and builders from persisting until the user manually runs the IntelliJ compilation check.

---

## [1.4.4]

### Fixed
- **Lombok ghost errors in the Maven fallback.** When the IDE socket is unreachable and the hook (or the in-IDE strict snapshot guard) falls back to `mvn` / `./mvnw`, Maven's incremental compiler could re-compile a consumer against a stale `target/classes` copy of an `@Getter` / `@Setter` class — emitting false `cannot find symbol` errors (e.g. `setCloseIcon`, `setFromSheetView`, `isFromSheetView`) immediately after a merge that introduced new Lombok-generated members. Both Maven invocation sites now pass `-Dmaven.compiler.useIncrementalCompilation=false` so annotation processing always sees fresh sources. The IDE fast path (`PrePushLocalServer` → `CompilerManager.make`) was unaffected and is still used whenever IntelliJ is running.

---

## [1.4.3]

### Fixed
- Managed hook now includes full PATH reconstruction (`bootstrap_build_tool_path`) for GUI git clients (Sublime Merge, SourceTree, GitHub Desktop) that launch hooks with a stripped environment. Sources the user's shell rc files (`.zshrc`, `.bash_profile`, etc.) and appends known install locations (`/opt/homebrew/bin`, SDKMAN, jenv, asdf) so `mvn`/`gradle` resolve correctly without a Maven or Gradle wrapper present.

---

## [1.4.2]

### Changed
- No functional changes. Version bump for distribution.

---

## [1.4.1]

### Fixed
- Removed deprecated IntelliJ `PerformInBackgroundOption` usage from the background pre-push check and auto-retry push tasks while preserving the same always-background behavior through `Task.Backgroundable`'s default constructor. Plugin Verifier for IntelliJ IDEA 2026.1.1 now reports the plugin as compatible without deprecated API warnings.

---

## [1.4.0]

### Added
- **Symbolic A/B detection (`SymbolicAbCheck`).** Before the snapshot compile, the strict guard parses `git diff HEAD` of unpushed local files for newly added method/class/field declarations and scans the HEAD content of pushed files for word-boundary references. A match blocks the push immediately with a per-symbol message (`[ImportNewAction1.java] references 'test', defined only in unpushed local change to ImportUtil.java`). Catches the canonical A/B case in milliseconds without invoking a build, so it works even when the HEAD snapshot has unrelated compile noise (missing generated sources, multi-module install gaps).
- **macOS `/private` path normalization** in `normalizeSnapshotOutput`. javac/Maven canonicalize `/var/folders/...` to `/private/var/folders/...`; the snapshot worktree-path replacement now strips both forms so `filterSnapshotErrors` actually retains errors in pushed files.
- **Auto-retry push on success.** When the background pre-push compilation passes, the plugin automatically runs `git push` per repository root (sequential, 120s timeout per repo, isolated `Task.Backgroundable`). Stdin is redirected from `/dev/null` and `GIT_TERMINAL_PROMPT=0` / empty `*_ASKPASS` env vars are set so a missing credential surfaces immediately as an error instead of hanging on a TTY prompt. Per-push elapsed-ms is logged to `idea.log`.
- **Failure-choice dialog.** When the background compile finds errors, a modal dialog presents four options: **Reset Commit** (soft-reset the pushed commits, keep changes in the working tree, reuses the existing abort-commit runnable), **Push Anyway** (run the auto-push path despite errors), **Leave Commit** (no-op, keep commit and skip push), **Cancel**.
- **HEAD-snapshot stash in the managed pre-push hook fallback.** When the IDE socket is unreachable and the hook falls back to running Gradle/Maven directly, it now `git stash push --include-untracked`'s the working tree first so the build sees only HEAD content. A trap on `EXIT INT TERM HUP` guarantees the stash is popped even on user interrupt or build crash. Closes the A/B coverage gap when the IDE is closed and pushes happen via terminal / Sublime Merge / GitHub Desktop.

### Changed
- `scheduleBackgroundPrePushCheck` now takes the original `List<PushInfo>` so success/failure handlers can re-issue the push or run the soft-reset action.
- `notifyBackgroundCompilationFinished` replaced by `handleBackgroundCompletion`; success and failure each take their own UX path (auto-push vs. choice dialog) instead of a single notification.

### Fixed
- Strict A/B snapshot validation no longer slips through silently when the HEAD compile fails but no parsed errors fall under the pushed paths. The new symbolic check usually catches these cases first; the snapshot path remains the secondary safety net.

---

## [1.3.1]

### Added
- **Active strict A/B dependency guard.** The side-panel toggle now has behavior: when enabled, IDE pushes and external pushes routed through the running IDE are blocked if relevant local source/build changes could make the live working tree differ from the pushed snapshot.

---

## [1.3.0]

### Added
- **External pushes can now reuse IntelliJ's incremental compiler.** The managed hook contacts a local loopback server when the IDE is open, so terminal and GUI-client pushes can validate with JPS before falling back to Gradle or Maven.
- **Debounced warmup compiles** run after source saves, keeping compiler caches hot so pre-push checks can usually reuse a fresh verdict.
- **Dependent-module compile scopes** include modules that depend on changed modules, catching caller-side breakage without forcing a full project build on ordinary pushes.

### Changed
- External push handling now bounds concurrent client workers and caps requested path lists, falling back to project-scope compilation when a request is too large to keep memory usage predictable.
- Plugin-owned hook/log/cache paths are added to the repo-local `.git/info/exclude` and cleaned up when the plugin is disabled or uninstalled.

### Performance
- Compiler error lists are compacted to a bounded number of entries, and very long entries are truncated before being retained or shown in the tool window.
- External hook log parsing deduplicates and compacts parsed errors, and skips reparsing identical log snapshots.

---

## [1.2.0]

### Added
- **Errors from external pushes now surface inside IntelliJ.** The managed hook writes its full output to `.idea/pre-push-checker/last-run.log`. A new startup activity (`ExternalPushErrorLoader`) loads that log on project open and watches it for updates. When the hook reports a non-zero exit, matching `path:line:col: error: message` lines are parsed into the Compilation Checker tool window (same navigation / tooltip / icons as in-IDE pushes), and a notification fires with an **Open Compilation Checker** action.
- `Pre-Push Compilation Checker` notification group.

### Changed
- **Hook installation now resolves the hooks directory via `git rev-parse --git-path hooks`,** so pushes are guarded in:
  - worktrees (where `.git` is a file pointing to the real gitdir),
  - submodules,
  - repos that set `core.hooksPath` (Husky et al.).
  Falls back to parsing `.git` as either a directory or a `gitdir:` pointer when git itself is unavailable.
- External hook now compiles **both main and test** sources (`./gradlew classes testClasses` / `mvn test-compile`) so test-only breakage is caught.
- Managed wrapper is re-written on every project open, so hook upgrades ship automatically without a manual reinstall.
- Hook script no longer uses the Gradle `--daemon` flag (it's implicit and interferes with `--console=plain` in some setups) and now logs via `tee` to the shared log file.

### Fixed
- External pushes from Sublime Merge / SourceTree / GitHub Desktop now reliably trigger the pre-push compile check on repos with non-standard `.git` layouts.

---

## [1.1.0]

### Added
- **Report Issue** toolbar action in the Compilation Checker tool window — opens the plugin's GitHub Issues page with a pre-populated, concise title.
- Tooltips on the error list showing the full raw entry so the deep project path stays discoverable on hover.

### Changed
- Cleaner error-list rendering: entries show as `FileName:line:col  —  message` instead of the raw `[deep/path (line, col)] message`. Navigation still targets the real file.
- Replaced the **Clear** toolbar action with **Report Issue**.
- `PrePushCompilationHandler.runCompilation` now uses the progress indicator's modality instead of `ModalityState.any()`.

### Fixed
- Navigation on `[path (line, col)] message` entries — double-click / Enter now opens the file on IntelliJ builds that use the parenthesised format. Paths containing spaces are also parsed correctly.
- `Write-unsafe context` runtime exception when compile was dispatched to the EDT with `ModalityState.any()`.
- Null-safe `formatCompilerMessages` on both the `messages` array and `message.getMessage()`.

### Performance
- Per-extension icon cache in the list renderer.
- `PushValidationPaths.isCompilableSource` / `isRelevantPath` avoid stream allocation and double path normalisation.
- `formatCompilerMessages` reuses a single `StringBuilder` and pre-sizes the result list.
- `CompilationErrorService.setErrors` short-circuits when the new list equals the previous snapshot.

### Robustness
- `CompilationErrorService.fireListeners` isolates listener exceptions so one broken listener can't abort the fan-out.

---

## [1.0.1]

### Fixed
- EDT violation when triggering a compilation check from the tool window **Run Check** button.

---

## [1.0.0] — Initial Release

### Added
- Pre-push handler for the IntelliJ Git Push dialog (`prePushHandler` extension point).
- Smart module-level compile scope with automatic full-build fallback when build files or deletions are involved.
- IDE problem check using `WolfTheProblemSolver` — skips compilation when IntelliJ already reports errors in the pushed files.
- Compilation error dialog with file-type icons, editor navigation, and a Refresh action.
- Compilation Checker tool window (right side panel).
- Git hook installer for terminal / external-client push support. Supports Gradle (wrapper or system) and Maven (wrapper or system), plus the `PRE_PUSH_CHECKER_COMMAND` environment variable for custom check commands.
- `CompilationErrorService` — project-level service that shares error state between the pre-push handler and the tool window.
