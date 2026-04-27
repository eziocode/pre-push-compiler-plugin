# Changelog

All notable changes to **Pre-Push Compilation Checker** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
