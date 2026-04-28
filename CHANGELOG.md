# Changelog

All notable changes to **Pre-Push Compilation Checker** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
