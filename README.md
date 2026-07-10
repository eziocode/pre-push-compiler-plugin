# Pre-Push Compilation Checker

> An IntelliJ IDEA plugin that blocks git pushes when compilation errors exist — before they reach your remote.

![Platform](https://img.shields.io/badge/platform-IntelliJ%202023.3%2B-orange)
![Version](https://img.shields.io/badge/version-1.9.3-blue)
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
- **Terminal push guard** — installs a managed `pre-push` Git hook, honors `core.hooksPath`, reuses the running IDE compiler when available, then falls back to Gradle or Maven against a clean HEAD snapshot
- **Compilation Checker tool window** — right-side panel that shows errors from the last check, with file-type icons and editor navigation
- **Git hook repair action** — rechecks and repairs the terminal hook from the tool window if another tool overwrites or edits it
- **Navigable error list** — double-click or press Enter on any error entry to jump to the source file in the editor
- **Refresh action** — re-run the compilation check from within the push-block dialog without cancelling the push flow
- **Auto-copy commit SHA to clipboard** — when a push is allowed (or after an IDE commit, depending on your setting), the HEAD commit SHA is automatically copied to the clipboard and confirmed with a balloon notification. Configurable: enable/disable, full 40-char or short 7-char format, and copy trigger (after push or after commit)
- **Gradle & Maven support** — detects Gradle wrapper, system Gradle, Maven wrapper, and system Maven automatically

---

## AI Commit Message Generator

Generate a ready-to-use git commit message from your staged changes with a single click, powered by any of six AI providers.

### Providers

| Provider | Auth | Notes |
|----------|------|-------|
| **JetBrains AI** | IDE sign-in | Requires the AI Assistant plugin (`com.intellij.ml.llm`) |
| **OpenAI** | API key | Calls `gpt-4o` (or any configured model) |
| **Anthropic** | API key | Calls Claude 3.5 Sonnet |
| **Google Gemini** | API key | Calls Gemini 1.5 Flash |
| **Ollama** | None | Local model — `ollama serve` must be running |
| **Codex CLI** | ChatGPT OAuth / API key | Run `codex auth` once, then the plugin calls `codex -q` |

### Usage

1. Open **Settings → Tools → AI Commit Message Generator** to choose a provider, enter your API key (stored securely in PasswordSafe), and configure commit message rules.
2. In the **Compilation Checker** tool window, click the ⚡ **Generate Commit Message with AI** button.
   *Or* open the Git Commit dialog and choose **Generate Commit Message with AI** from the commit message area action menu.
3. The generated message is injected into the commit message field (commit dialog) or shown in a copy dialog (tool window).

### Custom Rules

| Rule | Default |
|------|---------|
| Conventional Commits format | ✓ enabled |
| Max subject line length | 72 characters |
| Prefix / ticket template | *(empty)* |
| Language / tone | English, concise |
| Auto-detect scope from file paths | ✓ enabled |
| Extra instructions | *(empty)* |

All rules are configurable per project in **Settings → Tools → AI Commit Message Generator**.

### Project-level rules file (shared with your team)

Place a markdown file at **`.github/commit-instructions.md`** (or `COMMIT_RULES.md` in the project root) and commit it to VCS. The plugin reads it at generation time and appends it to the AI system prompt — every developer on the team picks up the same rules automatically, with no IDE settings change required.

```
<project-root>/
├── .github/
│   └── commit-instructions.md   ← auto-detected (highest priority)
└── COMMIT_RULES.md              ← auto-detected fallback
```

You can also specify a custom path in **Settings → Tools → AI Commit Message Generator → Rules file path**.

A starter template is included at `.github/commit-instructions.md` in this repository.

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

On project open the plugin installs a managed `pre-push` hook in your repo's effective hooks directory. Repositories that set `core.hooksPath` are handled explicitly, so the hook is installed where terminal and external Git clients will actually execute it.

When you push from a terminal or an external git client:

1. The hook filters out non-code pushes (tags, empty pushes, deletion-only pushes).
2. It honors the `PRE_PUSH_CHECKER_COMMAND` environment variable if set, letting you plug in any custom check command.
3. If IntelliJ is open, the hook asks the plugin's local loopback server to reuse the IDE's incremental compiler and cached warmup verdicts (which now also runs the symbolic A/B check).
4. If IntelliJ is unavailable or busy, the hook stashes the working tree (`git stash push --include-untracked`) so the build sees only HEAD content, runs `./gradlew classes testClasses` (or the Maven `test-compile` equivalent), then pops the stash on completion or interrupt (`trap ... EXIT INT TERM HUP`). This closes the A/B coverage gap when the IDE is closed.
5. The full compiler output is written to `.idea/pre-push-checker/last-run.log`. When IntelliJ is open:
   - It **parses the log**, extracts the error locations, and shows them in the **Compilation Checker** tool window so you can double-click to jump to the offending line.
   - It raises a **balloon notification** with an *Open Compilation Checker* action so the errors are front-and-centre even if you were working in a different tool window.

If another plugin or a manual edit overwrites the hook, the plugin rechecks it on startup and the tool window can repair it on demand. Repair recreates the managed hook, restores one canonical plugin snippet, removes duplicate or partial plugin snippets, and preserves unrelated custom hook logic.

> The hook is written idempotently — it only overwrites plugin-managed content, chains non-destructively when a custom `pre-push` hook already exists, and cleans stale plugin-managed hooks from legacy `.git/hooks` when `core.hooksPath` is active.

### Disable / Uninstall Cleanup

When the plugin is disabled or uninstalled, it removes plugin-owned leftovers from tracked repositories:

- The managed `pre-push-prepushchecker` hook and plugin-managed wrapper/snippet
- `.idea/pre-push-checker/` cache and hook settings files
- The plugin's delimited block in `.git/info/exclude`
- Stale plugin-managed hooks in legacy `.git/hooks` when the repo uses `core.hooksPath`

User-authored hook logic and the repository's `core.hooksPath` setting are preserved.

---

## Tool Window

Open **View → Tool Windows → Compilation Checker** (or click the side panel icon) to:

- View errors from the last pre-push check or manual run, rendered as `FileName:line:col — message` with the full path available on hover
- Toggle **Enable strict A/B dependency guard** for the project. It is off by default; when enabled, pushes are blocked if relevant local source/build changes could make the live working tree differ from the pushed snapshot.
- Configure **clipboard SHA settings** — "Copy commit SHA to clipboard automatically" checkbox with sub-options for SHA format (`Full / Short`) and copy trigger (`After Push / After Commit`). When "After Commit" is selected, the SHA is copied right after an IDE commit before the push step.
- See file-type icons for quick visual identification
- **Run Check** button (hammer icon) — triggers a full project compile on demand
- **Recheck / Repair Git Hooks** button (refresh icon) — verifies the terminal hook path and repairs missing, edited, or duplicated plugin-managed hook content
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

See [CHANGELOG.md](CHANGELOG.md) for the full release history. Latest release: **1.9.3**.
