# Ignored compilation files implementation plan

> For agentic workers, use superpowers:subagent-driven-development or superpowers:executing-plans to implement the tasks below.

**Goal** — Allow a push when all reported compilation errors belong to enabled, individually selected source files. Keep compiling those files and checking their dependents.

**Architecture** — Preserve the Git hook as the push entry point. Make the detached-worktree fallback and IntelliJ strict snapshot distinguish a proven ignored-only compiler failure from incomplete diagnostic evidence. Keep presentation limits independent of the verdict.

**Tech stack** — Java 17, IntelliJ Platform 2023.3, POSIX shell and awk, Gradle and JUnit.

**Spec** — The November 19 user request and the exact-file settings contract in `README.md`, under the tool window description.

## Global constraints

- Keep project-local selections and the current hook protocol compatible with existing installs.
- Preserve infrastructure, dependency, custom-command and independent validation failures.
- Compare full source identities. Files with the same name in two modules must remain distinguishable.

## Review focus

1. The default macOS awk rejects the hook's three-argument `match`. Exercise the generated hook with the system interpreter.
2. Kotlin file URIs, spaces and macOS private path aliases must resolve against the actual build root.
3. A pathless error or timeout after an ignored file diagnostic must block. A warning alone cannot explain a failed process.
4. A single nonignored error after the diagnostic display limit must block. Disabling an ignore must invalidate any optimistic reuse.
5. An authoritative ignored-only snapshot must finish validation without an additional IDE compilation or a clean Maven retry.

## Task 1 — Terminal fallback

**Files** — `src/main/java/com/github/prepushchecker/GitHookInstaller.java`, `src/test/java/com/github/prepushchecker/GitHookInstallerTest.java`.

- [x] Extend the temporary Git repository fixture with a fake Gradle or Maven wrapper. Assert hook exit 0 for an enabled ignored file alone and exit 1 for a disabled file, a same-name file in another module, mixed files, an independent build failure, and warnings alone. Include an absolute snapshot path containing spaces.
- [x] Run `./gradlew test --tests '*GitHookInstallerTest'`. Confirm the ignored-only case fails with system awk on macOS.
- [x] Replace the GNU capture-array calls in `extract_error_records` with POSIX parsing. Classify the full build output, including independent failure records and unsupported error formats. Match the normalized file paths against a settings set loaded once per attempt. Treat an incomplete parse as blocking.
- [x] Evaluate the proven ignored-only verdict before considering an expensive Maven recovery build. Keep existing recovery for unresolved dependencies or suspected stale output. Assert the wrapper invocation count and rerun the hook fixture.

## Task 2 — IntelliJ compiler and strict snapshot

**Files** — `src/main/java/com/github/prepushchecker/{CompilationSupport,IdeCompilationRunner,PrePushSnapshotGuard,ExternalPushErrorLoader,SmartBuildService}.java`; tests in `src/test/java/com/github/prepushchecker/{IgnoredCompilationFiles,PrePushSnapshotGuard,ExternalPushErrorLoader}Test.java`.

- [x] Add an IntelliJ compiler fixture using a real file and compiler message. Verify ignored-only, mixed, and missing diagnostic attribution. Include more diagnostics than the display limit, then disable an ignore between validations.
- [x] Add snapshot tests asserting `wasChecked()` for a proven ignored-only failure. Test a timeout and a separate infrastructure error after the same ignored diagnostic. These must return blocking evidence.
- [x] Distinguish checked ignored-only from inconclusive in `validateHeadSnapshotIfNeeded`. Decide on complete output before narrowing to pushed files, compacting the display, or invoking recovery. Use the active snapshot root for path mapping. Keep raw file errors available for settings changes and decline cache reuse if provenance is incomplete.
- [x] Run `./gradlew test --tests '*IgnoredCompilationFilesTest' --tests '*PrePushSnapshotGuardTest' --tests '*ExternalPushErrorLoaderTest'`. Verify the normal IDE and snapshot result paths, including the number of builds.
- [x] Attempt `./gradlew test buildPlugin` and inspect the generated distribution for the updated hook implementation.


## Verification on November 19

The default macOS awk reproduced the original failure. The generated hook then passed real Git pushes against a local bare repository using Maven Compiler Plugin 3.13.0. The remote accepted one or two deliberately broken files when every failing file was selected. It rejected the mixed case and a push whose ignore selection changed during the build.

The 87 tests covering the hook, compiler callback, snapshot guard, diagnostic parser, ignore settings and smart-build store passed against the packaged plugin. Gradle could not open its lock-coordination socket in the sandbox. The ZIP was built with the cached IntelliJ SDK, Kotlin and Java compilers, and JetBrains bytecode instrumentation. The generated hook and both ZIP and JAR contents were checked.

The full suite ran 525 tests on the updated package. The same 22 tests failed on the previous package in the same sandbox. All six new tests passed.
