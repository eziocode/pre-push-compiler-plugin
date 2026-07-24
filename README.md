# Pre-Push Compilation Checker

> An IntelliJ IDEA plugin that blocks git pushes when compilation errors exist — before they reach your remote.

![Platform](https://img.shields.io/badge/platform-IntelliJ%202023.3%2B-orange)
![Version](https://img.shields.io/badge/version-2.0.5-blue)
![Java](https://img.shields.io/badge/java-17%2B-green)

---

## Overview

Pre-Push Compilation Checker intercepts every `git push` and ensures your code compiles cleanly before it leaves your machine. It works with both the IntelliJ push dialog and terminal `git push` commands, giving you a fast feedback loop without waiting for CI to tell you your push was broken.

---

## Features

- **One push guard everywhere** — IntelliJ, terminal, Sublime Merge, and other Git clients use the same managed `pre-push` hook
- **Smart compile scope** — compiles modules containing changed files plus dependent modules; automatically falls back to a full project build when build files (`build.gradle`, `pom.xml`, etc.) or file deletions are involved
- **False-error recovery** — every failed incremental IntelliJ check gets one clean project rebuild; only the rebuild result can block a push
- **Push-only compilation** — edits, Git changes, and project startup never trigger a build; compilation runs only for explicit builds and pre-push validation
- **In-flight-only sharing** — concurrent identical pushes share one compiler run; later pushes always ask the incremental compiler again
- **Symbolic A/B detection** — parses `git diff HEAD` of unpushed local files for newly added method/class/field declarations and scans HEAD content of pushed files for word-boundary references; blocks the push instantly when the pushed commit references a symbol defined only in an unpushed local edit (no compile required)
- **Non-modal IntelliJ flow** — normal validation stays inside IntelliJ's native background Git task and never obscures the editor
- **Failure-only UI** — confirmed errors block the push and produce one notification with an action to open navigable errors
- **Isolated HEAD snapshot in the external hook** — when the IDE socket is unreachable, the hook builds a temporary detached worktree so old `target/`, `build/`, or local-only files cannot contaminate the pushed snapshot
- **Terminal push guard** — installs a managed `pre-push` Git hook, honors `core.hooksPath`, reuses the running IDE compiler when available, then falls back to Gradle or adaptive Maven compilation against an isolated HEAD snapshot
- **Compilation Checker tool window** — right-side panel that shows errors from the last check, with file-type icons and editor navigation
- **Git hook repair action** — rechecks and repairs the terminal hook from the tool window if another tool overwrites or edits it
- **Navigable error list** — double-click or press Enter on any error entry to jump to the source file in the editor
- **Auto-copy commit SHA to clipboard** — when validation allows a push, or after an IDE commit, the HEAD commit SHA is copied in full or short form; push-time copying is silent
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

1. You click **Push** or **Commit and Push**. IntelliJ starts its normal background Git task.
2. Git invokes the same managed `pre-push` hook used by terminal and GUI clients.
3. The hook sends repository root, HEAD, pushed-ref fingerprint, and changed paths to the IDE compiler service.
4. IntelliJ incrementally compiles affected modules plus dependents, or the project for build-file changes.
5. Any incremental failure triggers one clean project rebuild. Only confirmed rebuild errors block the push.
6. Success continues the original native push silently. Failure shows one notification and stores navigable errors in the Compilation Checker tool window.
7. Branch or file changes during validation abort with a retry message; commits and working-tree state are never modified.

### Terminal / External Git Push (Sublime Merge, SourceTree, GitHub Desktop, …)

On project open—or immediately when the plugin is installed/reinstalled into an already-open IDE—the plugin installs a managed `pre-push` hook in your repo's effective hooks directory. Repositories that set `core.hooksPath` are handled explicitly, so the hook is installed where terminal and external Git clients will actually execute it.

When you push from a terminal or an external git client:

1. The hook filters out non-code pushes (tags, empty pushes, deletion-only pushes).
2. It honors the `PRE_PUSH_CHECKER_COMMAND` environment variable if set, letting you plug in any custom check command.
3. If IntelliJ is open, the hook asks the plugin's local loopback server to run the IDE's incremental compiler. Only callers waiting for the same active snapshot share work; completed verdicts are never reused.
4. If IntelliJ is unavailable or busy, the hook creates a temporary detached worktree at `HEAD` and runs `./gradlew classes testClasses` (or the Maven equivalent) there. Maven keeps the parallel `-T1C` fast path and retries once sequentially with `clean` only for likely classpath/reactor races. The worktree is removed on completion or interrupt.
5. The full compiler output is written to `.idea/pre-push-checker/last-run.log`. When IntelliJ is open:
   - It **parses the log**, extracts the error locations, and shows them in the **Compilation Checker** tool window so you can double-click to jump to the offending line.
   - It raises a **balloon notification** with an *Open Compilation Checker* action so the errors are front-and-centre even if you were working in a different tool window.

If another plugin or a manual edit overwrites the hook, the plugin rechecks it on startup and the tool window can repair it on demand. Repair recreates the managed hook, restores one canonical plugin snippet, removes duplicate or partial plugin snippets, and preserves unrelated custom hook logic.

> The hook is written idempotently — plugin-owned lines live inside an explicit `BEGIN`/`END` block, custom `pre-push` logic outside that block is preserved, and stale plugin-managed hooks are cleaned from legacy `.git/hooks` when `core.hooksPath` is active.

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

See [CHANGELOG.md](CHANGELOG.md) for the full release history. Latest release: **2.0.5**.
