# Pre-Push Compilation Checker

> An IntelliJ IDEA plugin that blocks git pushes when compilation errors exist — before they reach your remote.

![Platform](https://img.shields.io/badge/platform-IntelliJ%202023.3%2B-orange)
![Version](https://img.shields.io/badge/version-1.4.1-blue)
![Java](https://img.shields.io/badge/java-17%2B-green)

---

## Overview

Pre-Push Compilation Checker intercepts every `git push` and ensures your code compiles cleanly before it leaves your machine. It works with both the IntelliJ push dialog and terminal `git push` commands, giving you a fast feedback loop without waiting for CI to tell you your push was broken.

---

## Features

- **IDE Push Guard** — hooks into IntelliJ's native push dialog (`prePushHandler` extension point)
- **Smart compile scope** — compiles modules containing changed files plus dependent modules; automatically falls back to a full project build when build files (`build.gradle`, `pom.xml`, etc.) or file deletions are involved
- **IDE problem check** — if IntelliJ already reports errors in the files being pushed, the push is blocked immediately without triggering a redundant build
- **Zero-cost warmup** — debounced background compiles keep the IDE compiler cache hot so most pushes reuse a fresh verdict
- **Symbolic A/B detection** — parses `git diff HEAD` of unpushed local files for newly added method/class/field declarations and scans HEAD content of pushed files for word-boundary references; blocks the push instantly when the pushed commit references a symbol defined only in an unpushed local edit (no compile required)
- **Auto-retry on success** — when the background pre-push check passes, the plugin runs `git push` automatically per repository root with non-interactive credential settings and a 120s timeout
- **Failure-choice dialog** — when errors are found, presents Reset Commit / Push Anyway / Leave Commit / Cancel options so you can recover without leaving the IDE
- **HEAD-snapshot stash in the external hook** — when the IDE socket is unreachable, the hook stashes the working tree before invoking Gradle/Maven so the build sees only HEAD content and A/B mismatches still surface; an `EXIT INT TERM HUP` trap guarantees stash pop on any interrupt
- **Terminal push guard** — installs a managed `pre-push` Git hook that reuses the running IDE compiler when available, then falls back to Gradle or Maven against a clean HEAD snapshot
- **Compilation Checker tool window** — right-side panel that shows errors from the last check, with file-type icons and editor navigation
- **Navigable error list** — double-click or press Enter on any error entry to jump to the source file in the editor
- **Refresh action** — re-run the compilation check from within the push-block dialog without cancelling the push flow
- **Gradle & Maven support** — detects Gradle wrapper, system Gradle, Maven wrapper, and system Maven automatically

---

## Requirements

| Item | Minimum |
|------|---------|
| IntelliJ IDEA | 2023.3 (build 233) |
| Java | 17 |
| Project type | Java / Kotlin (JVM) |

---

## Installation

### From JetBrains Marketplace *(recommended)*

1. Open **Settings → Plugins → Marketplace**
2. Search for **Pre-Push Compilation Checker**, or open the [plugin page](https://plugins.jetbrains.com/plugin/31297-pre-push-compilation-checker/) directly
3. Click **Install** and restart IntelliJ IDEA

### From Disk

1. Download the latest `.zip` from [Releases](https://github.com/eziocode/IntelliJ-Plugins/releases)
2. Open **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Select the downloaded `.zip` and restart

---

## How It Works

### IDE Push (Git Dialog)

1. You click **Push** in the Git Push dialog.
2. The plugin inspects every commit being pushed and collects the changed source files.
3. It first consults IntelliJ's problem solver — if the IDE already reports errors in those files the push is blocked immediately.
4. If the strict A/B guard is enabled, a symbolic check parses `git diff HEAD` of unpushed local files and scans pushed files at HEAD for references to declarations that exist only in those unpushed edits. Matches block the push with a message naming each pushed file and the symbol it references.
5. Otherwise it compiles the affected modules plus dependent modules (or the full project for build-file / deletion changes).
6. If compilation fails, a modal dialog presents **Reset Commit** (soft-reset the pushed commits, keep changes in the working tree), **Push Anyway** (run `git push` despite errors), **Leave Commit** (no-op), or **Cancel**.
7. When compilation passes, the plugin auto-retries the push for you (`git push` per repo root, non-interactive credentials, 120s timeout). No manual retry needed.

### Terminal / External Git Push (Sublime Merge, SourceTree, GitHub Desktop, …)

On project open the plugin installs a managed `pre-push` hook in your repo's hooks directory. The hooks directory is resolved the same way git itself does (via `git rev-parse --git-path hooks`), so worktrees, submodules, and repos that set `core.hooksPath` are handled correctly.

When you push from a terminal or an external git client:

1. The hook filters out non-code pushes (tags, empty pushes, deletion-only pushes).
2. It honors the `PRE_PUSH_CHECKER_COMMAND` environment variable if set, letting you plug in any custom check command.
3. If IntelliJ is open, the hook asks the plugin's local loopback server to reuse the IDE's incremental compiler and cached warmup verdicts (which now also runs the symbolic A/B check).
4. If IntelliJ is unavailable or busy, the hook stashes the working tree (`git stash push --include-untracked`) so the build sees only HEAD content, runs `./gradlew classes testClasses` (or the Maven `test-compile` equivalent), then pops the stash on completion or interrupt (`trap ... EXIT INT TERM HUP`). This closes the A/B coverage gap when the IDE is closed.
5. The full compiler output is written to `.idea/pre-push-checker/last-run.log`. When IntelliJ is open:
   - It **parses the log**, extracts the error locations, and shows them in the **Compilation Checker** tool window so you can double-click to jump to the offending line.
   - It raises a **balloon notification** with an *Open Compilation Checker* action so the errors are front-and-centre even if you were working in a different tool window.

> The hook is written idempotently — it only overwrites hooks it previously installed, and chains non-destructively when a custom `pre-push` hook already exists.

---

## Tool Window

Open **View → Tool Windows → Compilation Checker** (or click the side panel icon) to:

- View errors from the last pre-push check or manual run, rendered as `FileName:line:col — message` with the full path available on hover
- Toggle **Enable strict A/B dependency guard** for the project. It is off by default; when enabled, pushes are blocked if relevant local source/build changes could make the live working tree differ from the pushed snapshot.
- See file-type icons for quick visual identification
- **Run Check** button (hammer icon) — triggers a full project compile on demand
- **Report Issue** button (warning icon) — opens the plugin's GitHub Issues page with a pre-populated title so you can file a bug in two clicks
- Double-click or press **Enter** on any entry — jumps to the file and line in the editor

---

## Building from Source

```bash
# Build distributable zip
./gradlew buildPlugin          # → build/distributions/*.zip

# Launch a sandbox IDE with the plugin loaded
./gradlew runIde

# Verify plugin structure against JetBrains guidelines
./gradlew verifyPlugin
```

---

## License

MIT © [eziocode](https://github.com/eziocode)

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the full release history. Latest release: **1.4.1**.
