package com.github.prepushchecker;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catches imports that compile in the IDE but break a layered Ant build: a file in an
 * earlier-compiled layer (e.g. {@code crmcore}) importing in-repo source that only a later
 * layer compiles. IntelliJ sees one module, so {@code make} never notices. Layers come from
 * {@link LayerRules#FILE_NAME}; file contents are read from HEAD, i.e. the pushed snapshot.
 * Packages with no source in the repository (jars) are ignored.
 *
 * <p>Limitation: fully-qualified references without an import are not detected.
 */
final class CrossLayerImportCheck {
    private static final Logger LOG = Logger.getInstance(CrossLayerImportCheck.class);
    private static final long GIT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60);
    static final int MAX_MESSAGES = 100;

    private static final Pattern IMPORT = Pattern.compile(
        "^\\s*import\\s+(static\\s+)?([\\w$]+(?:\\s*\\.\\s*[\\w$]+)*)(\\s*\\.\\s*\\*)?\\s*;");
    private static final Pattern TYPE_START = Pattern.compile(
        "^\\s*(?:@\\w[\\w.]*(?:\\([^)]*\\))?\\s*)*"
            + "(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\\s+)*"
            + "(?:class|interface|enum|record|@interface)\\b");

    /** A known jar containing an imported class or package, and the layer that first lists it. */
    record JarHit(@NotNull String jarPath, int layerIndex) {
    }

    interface JarLookup {
        @NotNull List<JarHit> classHits(@NotNull String fqn);

        @NotNull List<JarHit> packageHits(@NotNull String pkg);
    }

    private CrossLayerImportCheck() {
    }

    /** Entry point for the push path. {@code changedPaths} are absolute. Empty when no rules file. */
    static @NotNull List<String> detect(
        @NotNull Project project,
        @NotNull String repositoryRoot,
        @NotNull Collection<String> changedPaths
    ) {
        if (repositoryRoot.isBlank()) return List.of();
        Path root = Path.of(repositoryRoot);
        List<String> relative = new ArrayList<>();
        for (String path : changedPaths) {
            String rel = DiagnosticPathMatcher.relativeToRoot(path, repositoryRoot);
            if (rel != null) relative.add(rel);
        }
        return toProjectPaths(project, root, run(root, relative, false));
    }

    /** Scans every layered file at HEAD (tool window action). */
    static @NotNull List<String> scanAll(@NotNull Project project, @NotNull String repositoryRoot) {
        Path root = Path.of(repositoryRoot);
        return toProjectPaths(project, root, run(root, List.of(), true));
    }

    static boolean hasRulesFile(@NotNull String repositoryRoot) {
        return !repositoryRoot.isBlank()
            && Files.isRegularFile(Path.of(repositoryRoot, LayerRules.FILE_NAME));
    }

    /** Repository-relative diagnostics. */
    static @NotNull List<String> run(
        @NotNull Path repositoryRoot,
        @NotNull Collection<String> changedRelativePaths,
        boolean forceFullScan
    ) {
        Path rulesFile = repositoryRoot.resolve(LayerRules.FILE_NAME);
        if (!Files.isRegularFile(rulesFile)) return List.of();
        String text;
        try {
            text = Files.readString(rulesFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of("[" + LayerRules.FILE_NAME + "] Layer rules unreadable: " + e.getMessage());
        }
        LayerRulesParser.Result parsed = LayerRulesParser.parse(text);
        if (!parsed.ok()) {
            List<String> problems = new ArrayList<>();
            for (LayerRulesParser.Problem problem : parsed.problems()) {
                problems.add("[" + LayerRules.FILE_NAME + " (" + problem.line() + ":1)] Layer rules error: "
                    + problem.message());
            }
            return problems;
        }
        LayerRules rules = parsed.rules();

        Set<String> headFiles = listHeadJavaFiles(repositoryRoot, rules.roots());
        if (headFiles.isEmpty()) return List.of();

        List<String> targets = selectTargets(rules, headFiles, changedRelativePaths, forceFullScan);
        if (targets.isEmpty()) return List.of();
        Map<String, String> contents = readHeadContents(repositoryRoot, targets);
        if (!rules.hasJarRules()) return analyze(rules, headFiles, contents);
        List<String> scans = new ArrayList<>();
        rules.scans().forEach(p -> scans.add(p.source()));
        try (JarClassIndex jars = new JarClassIndex(LayerJars.resolve(repositoryRoot, rules));
             ScanJarIndex scanned = new ScanJarIndex(ScanJars.resolve(repositoryRoot, scans))) {
            return analyze(rules, headFiles, contents, jars, scans.isEmpty() ? null : scanned);
        }
    }

    /**
     * Changed layered files with a later layer, or every such file when the rules file changed
     * or a Java file disappeared from HEAD (deleted or moved: untouched importers may break).
     */
    static @NotNull List<String> selectTargets(
        @NotNull LayerRules rules,
        @NotNull Set<String> headFiles,
        @NotNull Collection<String> changedRelativePaths,
        boolean forceFullScan
    ) {
        boolean full = forceFullScan;
        for (String path : changedRelativePaths) {
            if (LayerRules.isRulesFile(path) || path.endsWith(".jar") || ScanJars.isZip(path)) full = true;
            else if (path.endsWith(".java") && !headFiles.contains(path)) full = true;
        }
        Collection<String> candidates = full ? headFiles : changedRelativePaths;
        List<String> targets = new ArrayList<>();
        for (String path : candidates) {
            if (!path.endsWith(".java") || !headFiles.contains(path)) continue;
            LayerRules.Layer layer = rules.layerOf(path);
            if (layer != null && (rules.hasLaterLayer(layer) || !layer.forbids().isEmpty())) targets.add(path);
        }
        return targets;
    }

    /** Pure analysis: {@code headFiles} are repo-relative {@code .java} paths at HEAD. */
    static @NotNull List<String> analyze(
        @NotNull LayerRules rules,
        @NotNull Set<String> headFiles,
        @NotNull Map<String, String> contentsByPath
    ) {
        return analyze(rules, headFiles, contentsByPath, null);
    }

    /** As above; with {@code jars} also flags jar classes that only a later layer's classpath has. */
    static @NotNull List<String> analyze(
        @NotNull LayerRules rules,
        @NotNull Set<String> headFiles,
        @NotNull Map<String, String> contentsByPath,
        @Nullable JarLookup jars
    ) {
        return analyze(rules, headFiles, contentsByPath, jars, null);
    }

    /**
     * As above; with {@code scanned} (jars from {@code scan} lines) also flags imports that every
     * jar holding them is forbidden for the importing layer. A class in at least one allowed jar passes.
     */
    static @NotNull List<String> analyze(
        @NotNull LayerRules rules,
        @NotNull Set<String> headFiles,
        @NotNull Map<String, String> contentsByPath,
        @Nullable JarLookup jars,
        @Nullable JarLookup scanned
    ) {
        PackageIndex index = PackageIndex.build(rules, headFiles);
        List<String> messages = new ArrayList<>();
        for (Map.Entry<String, String> entry : contentsByPath.entrySet()) {
            String path = entry.getKey();
            LayerRules.Layer layer = rules.layerOf(path);
            if (layer == null) continue;
            for (ImportLine imp : parseImports(entry.getValue())) {
                Violation violation = index.resolve(rules, imp);
                if (violation == null) {
                    String message = jarMessage(rules, layer, path, imp, jars, scanned);
                    if (message == null) continue;
                    messages.add(message);
                    if (messages.size() >= MAX_MESSAGES) return List.copyOf(messages);
                    continue;
                }
                if (violation.layer().index() <= layer.index()) continue;
                messages.add(String.format(
                    "[%s (%d:1)] Layer violation: '%s' file imports %s from later layer '%s' (%s);"
                        + " the '%s' build step cannot see it",
                    path, imp.line(), layer.name(), imp.name(), violation.layer().name(),
                    violation.sourcePath(), layer.name()));
                if (messages.size() >= MAX_MESSAGES) return List.copyOf(messages);
            }
        }
        return List.copyOf(messages);
    }

    /**
     * The jar diagnostic for an import with no in-repo source, or {@code null} when allowed or unknown.
     * Allowed: some jar the layer can see holds it and is not forbidden for the layer. Visible jars are
     * earlier-or-own {@code lib} jars, plus scanned jars when no later layer's {@code lib} holds the class.
     */
    private static @Nullable String jarMessage(
        @NotNull LayerRules rules,
        @NotNull LayerRules.Layer layer,
        @NotNull String path,
        @NotNull ImportLine imp,
        @Nullable JarLookup jars,
        @Nullable JarLookup scanned
    ) {
        List<JarHit> libHits = jars == null ? List.of() : allHits(jars, imp);
        Set<String> laterLibs = new LinkedHashSet<>();
        Set<String> candidates = new LinkedHashSet<>();
        for (JarHit hit : libHits) {
            if (hit.layerIndex() <= layer.index()) candidates.add(hit.jarPath());
            else laterLibs.add(hit.jarPath());
        }
        // A later layer's lib owning the class keeps the later-layer verdict: a stray scanned copy
        // elsewhere must not quietly turn that check off.
        if (scanned != null && laterLibs.isEmpty()) {
            for (JarHit hit : allHits(scanned, imp)) candidates.add(hit.jarPath());
        }
        for (String candidate : candidates) {
            if (!layer.isForbidden(candidate)) return null;
        }
        if (!candidates.isEmpty()) {
            return String.format(
                "[%s (%d:1)] Forbidden jar: '%s' file imports %s, available only from jars layer '%s' unchecked: %s."
                    + " Check one in Layers or fix the import",
                path, imp.line(), layer.name(), imp.name(), layer.name(), listJars(candidates));
        }
        JarHit jar = earliest(libHits);
        if (jar == null) return null;
        return String.format(
            "[%s (%d:1)] Layer violation: '%s' file imports %s, found only in jar %s of later"
                + " layer '%s'; the '%s' build step's classpath does not include it",
            path, imp.line(), layer.name(), imp.name(), jar.jarPath(),
            rules.layers().get(jar.layerIndex()).name(), layer.name());
    }

    /** Up to three jar paths, then {@code and N more}. */
    private static String listJars(Collection<String> jars) {
        List<String> shown = new ArrayList<>();
        for (String jar : jars) {
            if (shown.size() == 3) break;
            shown.add(jar);
        }
        String text = String.join(", ", shown);
        return jars.size() > shown.size() ? text + " and " + (jars.size() - shown.size()) + " more" : text;
    }

    /** Jars holding the import: longest class prefix first, then the wildcard package. */
    private static @NotNull List<JarHit> allHits(@NotNull JarLookup jars, @NotNull ImportLine imp) {
        String[] parts = imp.name().split("\\.");
        List<JarHit> hits = List.of();
        for (int end = parts.length; end >= 1 && hits.isEmpty(); end--) {
            hits = jars.classHits(String.join(".", java.util.Arrays.copyOfRange(parts, 0, end)));
        }
        if (hits.isEmpty() && imp.wildcard()) hits = jars.packageHits(imp.name());
        return hits;
    }

    private static @Nullable JarHit earliest(@NotNull List<JarHit> hits) {
        JarHit best = null;
        for (JarHit hit : hits) {
            if (best == null || hit.layerIndex() < best.layerIndex()) best = hit;
        }
        return best;
    }

    record ImportLine(int line, @NotNull String name, boolean isStatic, boolean wildcard) {
    }

    /** Imports before the first type declaration, skipping comments. */
    static @NotNull List<ImportLine> parseImports(@NotNull String content) {
        List<ImportLine> imports = new ArrayList<>();
        String[] lines = content.split("\r?\n", -1);
        boolean inBlockComment = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (inBlockComment) {
                int end = line.indexOf("*/");
                if (end < 0) continue;
                line = line.substring(end + 2);
                inBlockComment = false;
            }
            int start = line.indexOf("/*");
            while (start >= 0) {
                int end = line.indexOf("*/", start + 2);
                if (end < 0) {
                    line = line.substring(0, start);
                    inBlockComment = true;
                    break;
                }
                line = line.substring(0, start) + " " + line.substring(end + 2);
                start = line.indexOf("/*");
            }
            int lineComment = line.indexOf("//");
            if (lineComment >= 0) line = line.substring(0, lineComment);
            if (line.isBlank()) continue;
            if (TYPE_START.matcher(line).find()) break;
            Matcher m = IMPORT.matcher(line);
            if (m.find()) {
                imports.add(new ImportLine(i + 1, m.group(2).replaceAll("\\s+", ""),
                    m.group(1) != null, m.group(3) != null));
            }
        }
        return imports;
    }

    private record Violation(@NotNull LayerRules.Layer layer, @NotNull String sourcePath) {
    }

    /** Maps package name → repo-relative {@code .java} files under the declared roots. */
    private static final class PackageIndex {
        private final Map<String, List<String>> filesByPackage = new HashMap<>();
        private final Map<String, String> fileByQualifiedName = new HashMap<>();

        static PackageIndex build(LayerRules rules, Set<String> headFiles) {
            PackageIndex index = new PackageIndex();
            for (String path : headFiles) {
                String root = rules.rootOf(path);
                if (root == null) continue;
                String inRoot = root.isEmpty() ? path : path.substring(root.length() + 1);
                int slash = inRoot.lastIndexOf('/');
                String pkg = slash < 0 ? "" : inRoot.substring(0, slash).replace('/', '.');
                String simple = inRoot.substring(slash + 1, inRoot.length() - ".java".length());
                index.filesByPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(path);
                // Duplicate FQN across roots: the earliest-layer copy is what that build step sees.
                index.fileByQualifiedName.merge(pkg.isEmpty() ? simple : pkg + "." + simple, path,
                    (kept, other) -> layerRank(rules, other) < layerRank(rules, kept) ? other : kept);
            }
            return index;
        }

        /** Unassigned files rank first: they never produce a violation. */
        private static int layerRank(LayerRules rules, String path) {
            LayerRules.Layer layer = rules.layerOf(path);
            return layer == null ? -1 : layer.index();
        }

        /** Later-layer source the import needs, or {@code null} when allowed/unknown/jar. */
        @Nullable Violation resolve(LayerRules rules, ImportLine imp) {
            String[] parts = imp.name().split("\\.");
            // Longest prefix naming a top-level class file wins (handles nested classes
            // and static members); otherwise the import names a package (wildcard).
            for (int end = parts.length; end >= 1; end--) {
                String candidate = String.join(".", java.util.Arrays.copyOfRange(parts, 0, end));
                String file = fileByQualifiedName.get(candidate);
                if (file != null) {
                    LayerRules.Layer layer = rules.layerOf(file);
                    return layer == null ? null : new Violation(layer, file);
                }
            }
            if (!imp.wildcard()) return null;
            List<String> files = filesByPackage.get(imp.name());
            if (files == null || files.isEmpty()) return null;
            // Wildcard: only flag when no file of the package is reachable from an earlier layer.
            LayerRules.Layer earliest = null;
            String earliestFile = null;
            for (String file : files) {
                LayerRules.Layer layer = rules.layerOf(file);
                if (layer == null) return null;
                if (earliest == null || layer.index() < earliest.index()) {
                    earliest = layer;
                    earliestFile = file;
                }
            }
            return new Violation(earliest, earliestFile);
        }
    }

    private static @NotNull List<String> toProjectPaths(
        @NotNull Project project, @NotNull Path repositoryRoot, @NotNull List<String> diagnostics
    ) {
        String base = project.getBasePath();
        if (base == null || diagnostics.isEmpty()) return diagnostics;
        List<String> result = new ArrayList<>(diagnostics.size());
        for (String diagnostic : diagnostics) {
            if (!diagnostic.startsWith("[")) {
                result.add(diagnostic);
                continue;
            }
            int end = diagnostic.indexOf(']');
            String inner = diagnostic.substring(1, end);
            int paren = inner.lastIndexOf(" (");
            String rel = paren >= 0 ? inner.substring(0, paren) : inner;
            String suffix = paren >= 0 ? inner.substring(paren) : "";
            String absolute = repositoryRoot.resolve(rel).normalize().toString();
            String projectRelative = DiagnosticPathMatcher.relativeToRoot(absolute, base);
            String display = projectRelative != null ? projectRelative : absolute.replace('\\', '/');
            result.add("[" + display + suffix + "]" + diagnostic.substring(end + 1));
        }
        return List.copyOf(result);
    }

    /** Streams the full listing: ProcessExecution caps output, which would silently drop files. */
    private static @NotNull Set<String> listHeadJavaFiles(@NotNull Path repositoryRoot, @NotNull List<String> roots) {
        List<String> command = new ArrayList<>(List.of("git", "ls-tree", "-r", "-z", "--name-only", "HEAD", "--"));
        for (String root : roots) command.add(root.isEmpty() ? "." : root);
        Set<String> files = new LinkedHashSet<>();
        Process process = null;
        CompletableFuture<Void> watchdog = null;
        try {
            process = new ProcessBuilder(command)
                .directory(repositoryRoot.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            process.getOutputStream().close();
            watchdog = killAfterTimeout(process);
            byte[] out = process.getInputStream().readAllBytes();
            if (!process.waitFor(GIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                return Set.of();
            }
            for (String path : new String(out, StandardCharsets.UTF_8).split("\0")) {
                if (path.endsWith(".java")) files.add(path);
            }
        } catch (IOException e) {
            LOG.debug("git ls-tree failed", e);
            return Set.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Set.of();
        } finally {
            if (watchdog != null) watchdog.cancel(false);
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
        return files;
    }

    /** Kills {@code process} once {@link #GIT_TIMEOUT_MILLIS} passes, unblocking any pending read. */
    private static @NotNull CompletableFuture<Void> killAfterTimeout(@NotNull Process process) {
        return CompletableFuture.runAsync(process::destroyForcibly,
            CompletableFuture.delayedExecutor(GIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
    }

    /** One {@code git cat-file --batch} process for all paths. */
    private static @NotNull Map<String, String> readHeadContents(
        @NotNull Path repositoryRoot, @NotNull List<String> paths
    ) {
        Map<String, String> contents = new LinkedHashMap<>();
        Process process = null;
        CompletableFuture<Void> watchdog = null;
        try {
            process = new ProcessBuilder("git", "cat-file", "--batch")
                .directory(repositoryRoot.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            watchdog = killAfterTimeout(process);
            Process running = process;
            Thread writer = new Thread(() -> {
                try (OutputStream stdin = running.getOutputStream()) {
                    for (String path : paths) {
                        stdin.write(("HEAD:" + path + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) {
                    // Reader side reports the failure.
                }
            }, "PrePushChecker-LayerCheck-CatFile");
            writer.setDaemon(true);
            writer.start();

            InputStream stdout = process.getInputStream();
            for (String path : paths) {
                String header = readLine(stdout);
                if (header == null) break;
                if (header.endsWith(" missing")) continue;
                String[] fields = header.split(" ");
                if (fields.length < 3) break;
                int size = Integer.parseInt(fields[2]);
                byte[] body = stdout.readNBytes(size);
                stdout.read(); // trailing LF
                if ("blob".equals(fields[1])) {
                    contents.put(path, new String(body, StandardCharsets.UTF_8));
                }
            }
            if (!process.waitFor(GIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (IOException | NumberFormatException e) {
            LOG.debug("git cat-file failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (watchdog != null) watchdog.cancel(false);
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
        return contents;
    }

    private static @Nullable String readLine(@NotNull InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(96);
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') return buffer.toString(StandardCharsets.UTF_8);
            buffer.write(b);
        }
        return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.UTF_8);
    }
}
