# Layer Jar Selection (all checked, uncheck to forbid) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each layer shows every known jar as a checkbox, all checked by default. The user unchecks the jars a layer must not use, and the push check blocks any file that imports a class available only from that layer's unchecked jars. The user never types into `.prepush-layers`.

**Architecture:** `.prepush-layers` gains two directives:
- top-level `scan <pattern>` defines the jar universe: IntelliJ library jars and the zips/dirs a GoBuild-style resolver may pull from, including jars nested in `.zip`.
- per-layer `forbid <pattern>` stores only the unchecked jars, so a new jar is allowed until someone unchecks it.

`ScanJars` + `ScanJarIndex` index the universe. `CrossLayerImportCheck.analyze` emits a new *Forbidden jar* diagnostic when every jar that has the imported class is forbidden for the importing layer. The Layers dialog shows a per-layer jar checkbox list, and the tool window offers one-click fixes.

**Tech Stack:** Java 17, IntelliJ Platform 2023.3 (`sinceBuild = "233"`), Swing (`DialogWrapper`, `CheckBoxList`, `FileChooser`), JUnit 3 `TestCase` / `HeavyPlatformTestCase`.

**Spec:** No separate spec doc. Source is the Town Hall thread "GoBuild - Smart Jar Resolving - A wrong implementation?" and the decisions in this session:
- The UI covers ignoring jars and folders completely; no manual file entries ("for ignore and adding jar selection user should able to make use of UI completely reducing manual entries in file").
- "make it pick all jars as selected as default user can ignore if needed, then compile based on that."
- Enforcement is the **import check**: an unchecked jar is forbidden. There is no extra javac run, and IntelliJ's classpath is not changed. Known limit: fully-qualified references without an import are not detected (the same as today's layer check).
- "Ignore" also covers **excluding a source folder**, which gets a folder picker.

## Global Constraints

- No deprecated, internal or scheduled-for-removal platform API (`.claude/rules/jetbrains-api-compat.md`). Read actions go only through `ReadActions.compute(Callable)`, on background threads.
- No blocking I/O on the EDT. Zip/jar listing triggered from the dialog runs under `ProgressManager.getInstance().runProcessWithProgressSynchronously(...)`.
- All rule paths are repo-relative with `/` separators. A jar nested in a zip has the display path `<zip repo-relative path>!/<entry name>`, e.g. `dist/TP_COMPONENTS.zip!/lib/commons-collections-3.2.jar`.
- Default is allow: a jar in the universe with no matching `forbid` is checked, i.e. allowed. The file stores only exclusions.
- `forbid` and `scan` lines must round-trip through `LayerRulesParser` → `LayerDraft` → `LayerRulesWriter` unchanged. The dialog's `roundTripProblem` must cover them.
- A rules file without `scan`/`forbid` behaves exactly as today: same messages, same jar-index cost. The existing later-layer `lib` check is unchanged.
- Diagnostics keep the `[path (line:1)] ` prefix so `CompilationEntryRenderer.extractPath` and navigation keep working.
- Release: bump `pluginVersion` in `gradle.properties` from `2.6.0` to `2.7.0`, and add a `CHANGELOG.md` entry under `[Unreleased]`.

## Review Focus

1. **The class is in both a forbidden and an allowed jar** (e.g. the same class in an old and a new jar). Expected: no error, because the layer can still get it from the allowed jar. Pinned in Task 3 (`testAllowedDuplicateWins`).
2. **A new jar appears after the rules were saved.** Expected: it shows checked in the dialog and is allowed by the check, with no file change needed. Pinned in Task 5 (`testNewJarDefaultsChecked`).
3. **Corrupt or unreadable zip / nested jar.** Expected: that jar is skipped, nothing throws, and the push check still runs. Pinned in Task 2 (`testCorruptZipAndNestedJarAreSkipped`).
4. **"Uncheck all versions" on a jar whose name has no version.** Expected: the forbid line is the exact file name pattern (`**/tools.jar`), not a wildcard that hits unrelated jars. Pinned in Task 4 (`testAnyVersion`).
5. **Quick fix on a rules file with comments.** Expected: the fix still applies and the layers stay equal. Comments are dropped, the same as the dialog's save, after a confirmation. Pinned in Task 6 (`testEditKeepsLayersAndDropsComments`).

---

## File Structure

| File | Responsibility |
|---|---|
| `LayerRules.java` (modify) | `Layer.forbids`, `LayerRules.scans`, `UNLISTED_LAYER`, `hasJarRules()` |
| `LayerRulesParser.java` (modify) | parse `scan` (global) and `forbid` (per layer) |
| `LayerDraft.java` (modify) | `forbids` list; `fromRules` copies it |
| `LayerRulesWriter.java` (modify) | `render(roots, scans, layers, headers)` writes both |
| `ScanJars.java` (create) | expand `scan` patterns to `ScannedJar`s (disk jars + jars inside zips) |
| `ScanJarIndex.java` (create) | `JarLookup` over scanned jars, cached class sets |
| `CrossLayerImportCheck.java` (modify) | *Forbidden jar* diagnostic; wiring in `run` |
| `JarPatterns.java` (create) | `anyVersion(path)`; `defaultScans(ideJars)` |
| `ScanDetector.java` (create) | suggest scan locations: zips holding jars, jar dirs unknown to IntelliJ |
| `LayersEditorDialog.java` (modify) | per-layer jar checkbox list, scan panel, folder pickers |
| `LayerDiagnostic.java` (create) | parse *Forbidden jar* / later-layer jar diagnostics |
| `LayerRulesEdit.java` (create) | pure text→text edits: add lib, add/remove forbid |
| `CompilationCheckerPanel.java` (modify) | right-click quick fixes on layer diagnostics |
| `AntLayerImporter.java`, tests calling `render` (modify) | pass `List.of()` for scans |

All paths are under `src/main/java/com/github/prepushchecker/`. Tests are under `src/test/java/com/github/prepushchecker/`.

---

### Task 1: `scan` and `forbid` in the rules model

**Files:**
- Modify: `LayerRules.java`, `LayerRulesParser.java`, `LayerDraft.java`, `LayerRulesWriter.java`, `AntLayerImporter.java:152`, `LayersEditorDialog.java:93`
- Test: `LayerRulesParserTest.java`, `LayerRulesWriterTest.java`, `LayersEditorIdeModelTest.java:164` (call-site update only)

**Interfaces:**
- Produces:
  - `record LayerRules.Layer(String name, int index, List<AntPathPattern> includes, List<AntPathPattern> excludes, List<AntPathPattern> libs, List<AntPathPattern> forbids)`
  - `LayerRules(List<String> roots, List<AntPathPattern> scans, List<Layer> layers)`; `List<AntPathPattern> scans()`
  - `static final int LayerRules.UNLISTED_LAYER = Integer.MAX_VALUE` (layer index for jars known only through `scan`)
  - `boolean LayerRules.hasJarRules()`: `hasLibs()` or any `forbids` non-empty or `scans` non-empty
  - `boolean LayerRules.Layer.isForbidden(String jarDisplayPath)`: any forbid pattern matches
  - `LayerDraft.forbids : List<String>`
  - `static String LayerRulesWriter.render(List<String> roots, List<String> scans, List<LayerDraft> layers, List<String> headers)`

- [ ] **Step 1: Write failing tests**

```java
// LayerRulesParserTest
public void testScanIsGlobalAndForbidIsPerLayer() {
    LayerRules rules = LayerRulesParser.parse(String.join("\n",
        "root source", "scan dist/TP_COMPONENTS.zip",
        "layer core", "  include source/core/**", "  forbid **/commons-collections-3*.jar",
        "layer app", "  include source/**", "scan lib/**")).rules();
    assertEquals(List.of("dist/TP_COMPONENTS.zip", "lib/**"),
        rules.scans().stream().map(AntPathPattern::source).toList());
    assertTrue(rules.layers().get(0).isForbidden("dist/TP_COMPONENTS.zip!/lib/commons-collections-3.2.jar"));
    assertFalse(rules.layers().get(1).isForbidden("dist/TP_COMPONENTS.zip!/lib/commons-collections-3.2.jar"));
    assertTrue(rules.hasJarRules());
}

public void testForbidBeforeLayerIsAProblem() {
    LayerRulesParser.Result r = LayerRulesParser.parse("root s\nforbid x.jar\nlayer a\ninclude s/**\nlayer b\ninclude t/**");
    assertFalse(r.ok());
    assertEquals("'forbid' before any 'layer'", r.problems().get(0).message());
}

// LayerRulesWriterTest
public void testScanAndForbidRoundTrip() {
    LayerDraft core = new LayerDraft("core");
    core.roots.add("source"); core.includes.add("source/core/**"); core.forbids.add("**/old-*.jar");
    LayerDraft app = new LayerDraft("app"); app.includes.add("source/**");
    String text = LayerRulesWriter.render(List.of(), List.of("dist/TP.zip"), List.of(core, app), List.of());
    assertTrue(text, text.contains("\nscan dist/TP.zip\n") && text.contains("  forbid **/old-*.jar\n"));
    LayerRules back = LayerRulesParser.parse(text).rules();
    assertEquals("dist/TP.zip", back.scans().get(0).source());
    assertEquals(List.of("**/old-*.jar"), LayerDraft.fromRules(back).get(0).forbids);
}
```

- [ ] **Step 2: Run the tests and check they fail**

Run: `./gradlew test --tests '*LayerRulesParserTest' --tests '*LayerRulesWriterTest'`
Expected: compile failure (`scans()`, `forbids`, the 4-arg `render` don't exist yet).

- [ ] **Step 3: Implement**

- Parser: `scan` is accepted at any position and is always global. `forbid` joins `include/exclude/lib` and errors with `'forbid' before any 'layer'` when it comes before a layer. Update the class Javadoc grammar block.
- Writer: emit `scan` lines right after the `root` lines, and `  forbid` lines after `  lib`. Add the header line `# 'scan' lists every jar source; all are allowed unless a layer 'forbid's them.`
- `LayerDraft.fromRules` copies `forbids`.
- Update the three existing `render(...)` callers to pass `List.of()` for scans. The dialog's own call is replaced in Task 5.

- [ ] **Step 4: Run tests and check they pass**: `./gradlew test --tests '*LayerRules*' --tests '*AntLayerImporterTest'`. Expected: PASS.
- [ ] **Step 5: Commit**: `git commit -m "Add scan and forbid directives to layer rules"`

---

### Task 2: Index scanned jars, including jars inside zips

**Files:**
- Create: `ScanJars.java`, `ScanJarIndex.java`
- Test: `ScanJarsTest.java` (extends `TestCase`, uses `TestFiles`)
- Modify: `TestFiles.java`: add `static void zip(Path root, String relative, Map<String, byte[]> entries)` and `static byte[] jarBytes(String... classEntries)`

**Interfaces:**
- Consumes: `LayerRules.UNLISTED_LAYER`, `CrossLayerImportCheck.JarLookup`, `CrossLayerImportCheck.JarHit`
- Produces:
  - `record ScanJars.ScannedJar(String displayPath, Path file, @Nullable String entry)` with `boolean onDisk()` (`entry == null`)
  - `static List<ScannedJar> ScanJars.resolve(Path repositoryRoot, List<String> patterns)`: a pattern matching a `.jar` on disk gives one on-disk jar. A pattern matching a `.zip` gives one nested jar per `*.jar` entry, with display path `zip!/entry`. Results are de-duplicated by display path and keep pattern order. Reuse `LayerJars.expand`'s walking logic: extract a private `walk(root, patterns, Predicate<String> fileFilter)` from it so both share it.
  - `final class ScanJarIndex implements JarLookup, Closeable`, `ScanJarIndex(List<ScannedJar>)`. Every hit has `layerIndex = UNLISTED_LAYER`. The static class-name cache is keyed `file|size|mtime|entry`. Nested jars are read with `ZipInputStream` over `zip.getInputStream(entry)`. Only one level of nesting is supported (document this in the Javadoc).

- [ ] **Step 1: Write failing tests**

```java
public void testResolvesDiskJarsAndJarsInsideZip() throws Exception {
    TestFiles.jar(repo, "lib/x.jar", "org/x/X.class");
    TestFiles.zip(repo, "dist/TP.zip", Map.of(
        "lib/commons-collections-3.2.jar", TestFiles.jarBytes("org/apache/commons/collections/CollectionUtils.class"),
        "readme.txt", new byte[]{1}));
    List<ScanJars.ScannedJar> jars = ScanJars.resolve(repo, List.of("lib/**", "dist/TP.zip"));
    assertEquals(List.of("lib/x.jar", "dist/TP.zip!/lib/commons-collections-3.2.jar"),
        jars.stream().map(ScanJars.ScannedJar::displayPath).toList());
    try (ScanJarIndex index = new ScanJarIndex(jars)) {
        List<CrossLayerImportCheck.JarHit> hits = index.classHits("org.apache.commons.collections.CollectionUtils");
        assertEquals("dist/TP.zip!/lib/commons-collections-3.2.jar", hits.get(0).jarPath());
        assertEquals(LayerRules.UNLISTED_LAYER, hits.get(0).layerIndex());
        assertEquals(1, index.packageHits("org.x").size());
    }
}

public void testCorruptZipAndNestedJarAreSkipped() throws Exception {
    TestFiles.write(repo, "dist/bad.zip", "not a zip");
    TestFiles.zip(repo, "dist/ok.zip", Map.of("bad.jar", new byte[]{1, 2, 3}));
    List<ScanJars.ScannedJar> jars = ScanJars.resolve(repo, List.of("dist/*.zip"));
    try (ScanJarIndex index = new ScanJarIndex(jars)) {
        assertTrue(index.classHits("a.B").isEmpty());
    }
}
```

- [ ] **Step 2: Run and check they fail**: `./gradlew test --tests '*ScanJarsTest'`. Expected: compile failure.
- [ ] **Step 3: Implement** as described in Interfaces.
- [ ] **Step 4: Run and check they pass**: `./gradlew test --tests '*ScanJarsTest' --tests '*LayerJarsTest'`. Expected: PASS.
- [ ] **Step 5: Commit**: `git commit -m "Index jars from scan locations, including jars inside zips"`

---

### Task 3: *Forbidden jar* diagnostic (import check over the selection)

**Files:**
- Modify: `CrossLayerImportCheck.java` (`analyze`, `earliestJar`, `run`, `selectTargets`)
- Test: `CrossLayerJarCheckTest.java`

**Interfaces:**
- Consumes: `ScanJars.resolve`, `ScanJarIndex`, `LayerRules.Layer.isForbidden`, `LayerRules.hasJarRules`
- Produces:
  - `static List<String> analyze(LayerRules, Set<String>, Map<String,String>, @Nullable JarLookup libs, @Nullable JarLookup scanned)`. The existing 4-arg overload delegates with `scanned = null`.
  - Message format (exact, parsed by Task 6): `[%s (%d:1)] Forbidden jar: '%s' file imports %s, available only from jars layer '%s' unchecked: %s. Check one in Layers or fix the import`
  - The jar list holds up to 3 display paths joined by `, `, then ` and N more`.

Decision order per import that is not an in-repo source import (replaces the current `violation == null` branch):
1. `libHits = allHits(libs, imp)` and `scanHits = allHits(scanned, imp)`. `allHits` is the longest-prefix logic now in `earliestJar`, returning all hits; `earliestJar` stays on top of it.
2. `candidates` = `libHits` with `layerIndex <= layer.index()` plus all `scanHits` whose display path is not a later-layer lib jar in `libHits`. De-duplicate by path.
3. If some candidate is **not** `layer.isForbidden(path)`, the import is allowed: continue.
4. If `candidates` is non-empty (all forbidden), emit *Forbidden jar* listing them.
5. Else, if `libHits` is non-empty (only later-layer lib jars), emit the existing later-layer message, unchanged.
6. Otherwise the import is unknown or JDK: ignore it. The IDE compile reports a truly missing class.

`run`: build the lib index when `rules.hasLibs()`, and the scan index when `!rules.scans().isEmpty()`. Use try-with-resources on both. `selectTargets`: a changed path ending in `.zip` also forces a full scan, like `.jar` today.

- [ ] **Step 1: Write failing tests** (add helper `analyzeScanned(file, imports...)`: like `analyze`, plus a `ScanJarIndex` over `ScanJars.resolve(repo, <rules.scans() sources>)`. The fixture rules gain `scan dist/TP.zip` and `scan lib/**`, plus `  forbid **/commons-collections-3*.jar` on `crmcore`. Fixture jars gain `dist/TP.zip!/lib/commons-collections-3.2.jar` (class `org/apache/commons/collections/CollectionUtils.class`) and `lib/dup-new.jar` (class `org/apache/commons/collections/Dup.class`); the zip's commons jar also contains `Dup.class`.)

```java
public void testImportOnlyFromUncheckedJarIsForbidden() {
    List<String> errors = analyzeScanned(CORE_FILE, "org.apache.commons.collections.CollectionUtils");
    assertEquals(errors.toString(), 1, errors.size());
    assertTrue(errors.get(0), errors.get(0).startsWith("[" + CORE_FILE + " (2:1)] Forbidden jar: 'crmcore' file imports org.apache.commons.collections.CollectionUtils, available only from jars layer 'crmcore' unchecked: dist/TP.zip!/lib/commons-collections-3.2.jar."));
    assertTrue(analyzeScanned(CRM_FILE, "org.apache.commons.collections.CollectionUtils").isEmpty()); // per layer
}

public void testAllowedDuplicateWins() {
    assertTrue(analyzeScanned(CORE_FILE, "org.apache.commons.collections.Dup").isEmpty());
}

public void testScannedJarsAreAllowedByDefault() {
    assertTrue(analyzeScanned(CORE_FILE, "org.x.X").isEmpty()); // lib/x.jar via scan only, not forbidden
}

public void testLaterLayerLibMessageUnchanged() {
    assertTrue(analyzeScanned(CORE_FILE, "org.crm.CrmUtil").get(0).contains("found only in jar lib/crm.jar of later layer 'crm'"));
}

public void testZipChangeForcesFullScan() {
    Set<String> head = Set.of(CORE_FILE, CRM_FILE);
    assertEquals(List.of(CORE_FILE), CrossLayerImportCheck.selectTargets(rules, head, List.of("dist/TP.zip"), false));
}
```

Note for the implementer: in `testLaterLayerLibMessageUnchanged`, `lib/crm.jar` is also matched by `scan lib/**`. Step 2 excludes later-layer lib jars from `candidates`, so the later-layer message still wins.

- [ ] **Step 2: Run and check they fail**: `./gradlew test --tests '*CrossLayerJarCheckTest'`
- [ ] **Step 3: Implement** the decision order above.
- [ ] **Step 4: Run and check they pass**: `./gradlew test --tests '*CrossLayer*'`. Expected: PASS, and the existing tests are unchanged.
- [ ] **Step 5: Commit**: `git commit -m "Block imports available only from unchecked jars"`

---

### Task 4: Pattern helpers and scan-location detector

**Files:**
- Create: `JarPatterns.java`, `ScanDetector.java`
- Test: `JarPatternsTest.java`, `ScanDetectorTest.java`

**Interfaces:**
- Produces:
  - `static String JarPatterns.anyVersion(String jarDisplayPath)`: strips the parent path and the `zip!/` prefix, then turns a trailing `-<digit>…` before `.jar` into `-*`, giving `**/<name>-*.jar`. Without a version it returns `**/<file name>`.
  - `static List<String> JarPatterns.defaultScans(Collection<String> ideJars)`: the distinct parent dirs of IntelliJ's repo-relative jars as `<dir>/*.jar`, sorted. A jar at the repo root gives `*.jar`.
  - `record ScanDetector.Suggestion(String pattern, String reason)`
  - `static List<Suggestion> ScanDetector.detect(Path repositoryRoot, Set<String> knownJars)`:
    - Walks to depth 8 and skips hidden dirs, `out`, `build`, `target` and `node_modules`. Caps at 200 suggestions. Sorted by pattern.
    - A `.zip` with ≥1 `.jar` entry gives `Suggestion("<rel>.zip", "zip with N jars")`.
    - A dir with ≥1 `.jar` not in `knownJars` gives `Suggestion("<rel>/**", "N jars not on IntelliJ's classpath")`. A dir is not suggested when an ancestor already is.

- [ ] **Step 1: Write failing tests**

```java
public void testAnyVersion() {
    assertEquals("**/commons-collections-*.jar", JarPatterns.anyVersion("dist/TP.zip!/lib/commons-collections-3.2.1.jar"));
    assertEquals("**/commons-collections4-*.jar", JarPatterns.anyVersion("lib/commons-collections4-4.4.jar"));
    assertEquals("**/tools.jar", JarPatterns.anyVersion("lib/tools.jar"));
}

public void testDefaultScans() {
    assertEquals(List.of("*.jar", "lib/*.jar", "lib/ext/*.jar"),
        JarPatterns.defaultScans(List.of("lib/a.jar", "lib/ext/b.jar", "lib/c.jar", "root.jar")));
}

public void testDetectsZipsWithJarsAndUnknownJarDirs() throws Exception {
    TestFiles.zip(repo, "dist/TP.zip", Map.of("a.jar", TestFiles.jarBytes("a/A.class")));
    TestFiles.jar(repo, "thirdparty/x/y.jar", "y/Y.class");
    TestFiles.jar(repo, "lib/known.jar", "k/K.class");
    TestFiles.jar(repo, "build/libs/out.jar", "o/O.class");
    List<String> patterns = ScanDetector.detect(repo, Set.of("lib/known.jar")).stream()
        .map(ScanDetector.Suggestion::pattern).toList();
    assertEquals(List.of("dist/TP.zip", "thirdparty/x/**"), patterns);
}
```

- [ ] **Step 2: Run and check they fail**: `./gradlew test --tests '*JarPatternsTest' --tests '*ScanDetectorTest'`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run and check they pass.**
- [ ] **Step 5: Commit**: `git commit -m "Add jar pattern helpers and scan location detector"`

---

### Task 5: Layers dialog: per-layer jar checkboxes, all checked by default

**Files:**
- Modify: `LayersEditorDialog.java`
- Test: `LayersEditorIdeModelTest.java` (new test methods; reuse its `field`/`click`/`listContents`/`indexOf` helpers)

**Interfaces:**
- Consumes: Tasks 1, 2, 4
- Produces: package-private seams, which the pickers call and the tests call directly:
  - `void addScanLocations(List<String> patterns)`, `void removeScanLocation(String pattern)`. Each change re-lists the universe (`ScanJars.resolve`, entry names only) under modal progress.
  - `@NotNull List<String> universe()`: display paths of all jars matched by the scan lines, in order.
  - `void setJarChecked(String displayPath, boolean checked)`: acts on the selected layer.
    - Unchecking adds `forbid <displayPath>`.
    - Checking removes every forbid line of that layer that matches the path. When the match came from a wildcard, it first confirms `This also re-allows other jars matching '<pattern>'. Continue?`.
  - `void uncheckAllVersions(String displayPath)`: adds `forbid JarPatterns.anyVersion(displayPath)` to the selected layer.
  - `void addFolderEntries(String kind, List<String> repoRelativeDirs)`: `kind` is `"include"` or `"exclude"`, and each dir becomes `<dir>/**`.
  - `@NotNull String getRulesText()` now passes the scans to the 4-arg `render`.

UI changes (labels exact, tests click them):
- **Default scans.** When the dialog opens with no `scan` lines, or after `From IntelliJ modules` / `From build.xml…`, the scans start as `JarPatterns.defaultScans(snapshot.jars())` plus every `ScanDetector.detect(...)` suggestion. Detection runs in the existing `Task.Backgroundable` in `CompilationCheckerPanel` before the dialog opens; pass the result in through a new constructor parameter `List<String> suggestedScans`. So every jar IntelliJ knows about, plus zips like `TP_COMPONENTS.zip`, is in the universe and checked with no user action.
- **"Jars for selected layer" panel** (replaces the `lib` rows in the entry list): a `CheckBoxList<String>` over `universe()`.
  - A row is checked when `!layer.isForbidden(path)`.
  - Nested jars render as `TP.zip › lib/x.jar`, with the full path as the tooltip.
  - There is a filter field, plus `Check all` / `Uncheck all` buttons that act on the filtered rows.
  - The right-click menu has `Uncheck all versions`.
  - Checkbox toggles call `setJarChecked`.
  - The panel title shows `Jars for '<layer>' — N of M allowed`.
- **"Scan locations" panel** (left side, under the module list):
  - A list of scan patterns.
  - `Add folder or zip…`: `FileChooserDescriptor(true, true, true, true, false, true)`, rooted at the repo. Files outside the repo are rejected with `Pick a folder, .zip or .jar inside the repository.`
  - `Detect…`: re-runs the detector under modal progress, then shows a checkbox list of suggestions not already present (all checked).
  - `Remove`.
- **Selected-layer entry list** keeps `include`/`exclude`/`lib`/`forbid` rows for review.
  - Buttons: `Add include folder…` and `Exclude folder…` (multi-dir choosers into `addFolderEntries`), and `Remove entry`.
  - The text-prompt buttons `Add jar pattern…` / `Add include…` are removed. `Edit pattern…` stays in the entry right-click menu as the only free-text path left.
- **Left module list:** `Add to layer →` keeps working for modules. It no longer adds jars; jar access is the checkbox panel.
- **Validation** (`roundTripProblem`) also compares `forbids` and scans.
- **Loading.** `Reload file` restores scans from `rules.scans()`.

- [ ] **Step 1: Write failing tests**

```java
public void testAllJarsCheckedByDefaultAndUncheckWritesForbid() throws Exception {
    TestFiles.zip(repo, "dist/TP.zip", Map.of("lib/old-1.0.jar", TestFiles.jarBytes("o/Old.class")));
    LayersEditorDialog dialog = new LayersEditorDialog(getProject(), repo.toString(), snapshot, List.of(),
        List.of("dist/TP.zip"));
    try {
        JComponent panel = centerPanel(dialog);
        click(panel, "From IntelliJ modules");
        assertTrue(dialog.universe().toString(), dialog.universe().containsAll(
            List.of("lib/core-lib.jar", "lib/api-lib.jar", "dist/TP.zip!/lib/old-1.0.jar")));
        JList<?> layers = field(dialog, "layerList");
        layers.setSelectedIndex(indexOf(layers, "mod1"));
        CheckBoxList<String> jars = field(dialog, "jarList");
        for (int i = 0; i < jars.getItemsCount(); i++) assertTrue(jars.isItemSelected(i));
        assertFalse(dialog.getRulesText(), dialog.getRulesText().contains("forbid"));

        dialog.setJarChecked("dist/TP.zip!/lib/old-1.0.jar", false);
        dialog.addFolderEntries("exclude", List.of("mod1/src/com/x/gen"));
        String text = dialog.getRulesText();
        assertTrue(text, text.contains("scan dist/TP.zip\n") && text.contains("scan lib/*.jar\n")
            && text.contains("  forbid dist/TP.zip!/lib/old-1.0.jar\n")
            && text.contains("  exclude mod1/src/com/x/gen/**\n"));
        assertTrue(LayerRulesParser.parse(text).ok());
        assertTrue(dialog.isOKActionEnabled());

        dialog.setJarChecked("dist/TP.zip!/lib/old-1.0.jar", true);
        assertFalse(dialog.getRulesText().contains("forbid"));
    } finally {
        Disposer.dispose(dialog.getDisposable());
    }
}

public void testNewJarDefaultsChecked() throws Exception {
    // save rules with scan lib/*.jar and one forbid, then add lib/new.jar on disk, Reload file:
    // jarList shows lib/new.jar checked; getRulesText() unchanged
}

public void testUncheckAllVersionsWritesPattern() throws Exception {
    // uncheckAllVersions("lib/core-lib.jar") → "  forbid **/core-lib.jar" (no version) on mod1
}
```

- [ ] **Step 2: Run and check they fail**: `./gradlew test --tests '*LayersEditorIdeModelTest'`
- [ ] **Step 3: Implement** the UI changes above. Update the dialog's one call site in `CompilationCheckerPanel.java:615`, and the existing test constructor calls, to pass `suggestedScans`.
- [ ] **Step 4: Run and check they pass**: `./gradlew test --tests '*LayersEditorIdeModelTest' --tests '*LayersWorstCaseHarnessTest'`. Expected: PASS. The worst-case harness covers long names and narrow widths, so check the new panels do not widen the dialog past its current cap.
- [ ] **Step 5: Commit**: `git commit -m "Layers dialog: all jars checked by default, uncheck to forbid"`

---

### Task 6: One-click fixes from the Compilation Checker tool window

**Files:**
- Create: `LayerDiagnostic.java`, `LayerRulesEdit.java`
- Modify: `CompilationCheckerPanel.java` (`maybeShowErrorMenu`, ~line 254)
- Test: `LayerDiagnosticTest.java`, `LayerRulesEditTest.java`

**Interfaces:**
- Consumes: the Task 3 message format and the existing later-layer jar format (`found only in jar %s of later layer '%s'`)
- Produces:
  - `record LayerDiagnostic(Kind kind, String layer, List<String> jars)`, with `enum Kind { FORBIDDEN_JAR, LATER_LAYER_JAR }`
  - `static @Nullable LayerDiagnostic LayerDiagnostic.parse(String diagnostic)`. A ` and N more` tail is dropped (only listed jars are offered).
  - `static String LayerRulesEdit.addLib(String rulesText, String layer, String jar)`
  - `static String LayerRulesEdit.allowJar(String rulesText, String layer, String jarDisplayPath)`: removes that layer's forbid lines matching the path.
  - All edits parse, mutate the matching `LayerDraft`, and re-render with header `Edited from the Compilation Checker.`. They throw `IllegalArgumentException` when the text does not parse or the layer is unknown.

Menu (shown only when `parse` returns non-null and `CrossLayerImportCheck.hasRulesFile(root)`):
- `FORBIDDEN_JAR`: `Check <jar> for '<layer>'` per listed jar (`allowJar`), and `Open Layers…`.
- `LATER_LAYER_JAR`: `Allow <jar> in '<layer>'` (`addLib`).

Each action reads the file, applies the edit and writes it with `LayerRulesFile.write`. When the original file had comment lines beyond the writer's own header, it first confirms with `Messages.showYesNoDialog`: `Comments in .prepush-layers will be removed. Continue?`.

- [ ] **Step 1: Write failing tests**

```java
public void testParsesForbiddenJar() {
    LayerDiagnostic d = LayerDiagnostic.parse("[source/A.java (3:1)] Forbidden jar: 'core' file imports a.B, available only from jars layer 'core' unchecked: lib/b.jar, dist/TP.zip!/lib/c.jar. Check one in Layers or fix the import");
    assertEquals(LayerDiagnostic.Kind.FORBIDDEN_JAR, d.kind());
    assertEquals("core", d.layer());
    assertEquals(List.of("lib/b.jar", "dist/TP.zip!/lib/c.jar"), d.jars());
    assertNull(LayerDiagnostic.parse("[x.java (1:1)] cannot find symbol"));
}

public void testAllowJarRemovesMatchingForbid() {
    String before = "root s\nscan lib/*.jar\nlayer a\n  include s/a/**\n  forbid lib/x.jar\nlayer b\n  include s/**\n";
    LayerRules rules = LayerRulesParser.parse(LayerRulesEdit.allowJar(before, "a", "lib/x.jar")).rules();
    assertTrue(rules.layers().get(0).forbids().isEmpty());
}

public void testEditKeepsLayersAndDropsComments() {
    String before = "# mine\nroot s\nlayer a\n  include s/a/**\nlayer b\n  include s/**\n";
    String after = LayerRulesEdit.addLib(before, "a", "lib/x.jar");
    LayerRules rules = LayerRulesParser.parse(after).rules();
    assertEquals("lib/x.jar", rules.layers().get(0).libs().get(0).source());
    assertEquals(2, rules.layers().size());
    assertFalse(after, after.contains("# mine"));
}
```

- [ ] **Step 2: Run and check they fail**: `./gradlew test --tests '*LayerDiagnosticTest' --tests '*LayerRulesEditTest'`
- [ ] **Step 3: Implement** parsing, edits and menu items. Any file read on the EDT is limited to the small rules file.
- [ ] **Step 4: Run and check they pass.**
- [ ] **Step 5: Commit**: `git commit -m "Quick fixes for forbidden and later-layer jar diagnostics"`

---

### Task 7: Docs, version, verification

**Files:**
- Modify: `README.md` (Cross-layer import check bullet: `scan`, `forbid`, jar checkboxes all checked by default, the quick fixes, and the FQN-reference limit), `CHANGELOG.md` (`[Unreleased]` → Added), `gradle.properties` (`pluginVersion=2.7.0`), and `build.gradle.kts` (`pluginVerification { ides { recommended() } }` if not already present at line ~55)

- [ ] **Step 1:** `./gradlew compileJava`. Expected: BUILD SUCCESSFUL, no new deprecation warnings.
- [ ] **Step 2:** `./gradlew test`. Expected: all green, including `LayersEditorIdeModelTest` and `LayersWorstCaseHarnessTest`.
- [ ] **Step 3:** `./gradlew verifyPlugin`. Expected: zero deprecated, scheduled-for-removal and internal API usages.
- [ ] **Step 4:** `./gradlew buildPlugin`. Expected: `build/distributions/pre-push-compiler-plugin-2.7.0.zip`.
- [ ] **Step 5:** Manual check in `runIde` against a sandbox repo with `dist/TP.zip` holding `commons-collections-3.2.jar`:
  1. Open Layers and confirm every jar is checked.
  2. Uncheck the old jar for a layer, then push a file in that layer that imports `org.apache.commons.collections.*` and confirm it is blocked with *Forbidden jar*.
  3. Use the **Check … for …** quick fix and confirm the push passes.
- [ ] **Step 6: Commit**: `git commit -m "Release 2.7.0: jar selection per layer with forbid/scan rules"`
