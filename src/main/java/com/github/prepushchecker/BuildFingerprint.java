package com.github.prepushchecker;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Content address of the compilable working tree of every Git root in a project.
 *
 * <p>The digest is built from the index blob ids ({@code git ls-files -s}) with
 * working-tree modifications and untracked files overridden by their own git-style
 * blob hashes, plus unsaved editor documents. It deliberately does <em>not</em>
 * include the HEAD commit: committing or amending without touching file contents
 * keeps the fingerprint, so a verdict computed before {@code git commit} is still
 * reused by the following push. Switching branches changes the index and therefore
 * the fingerprint.
 *
 * <p>All git calls use {@code --no-optional-locks}, so computing a fingerprint never
 * rewrites {@code .git/index} (which would otherwise trigger repository-change events
 * and feed back into another fingerprint).
 */
final class BuildFingerprint {
    private static final Logger LOG = Logger.getInstance(BuildFingerprint.class);
    private static final String PROTOCOL = "fp-v1";
    private static final int MAX_WORKTREE_OVERRIDES = 5_000;
    private static final long MAX_HASHED_FILE_BYTES = 16L * 1024 * 1024;
    private static final long GIT_TIMEOUT_SECONDS = 30L;

    private BuildFingerprint() {
    }

    /** Branch checked out in one Git root. Detached HEADs use {@code HEAD@<sha7>}. */
    record BranchRef(@NotNull String root, @NotNull String branch) {
        boolean isDetached() {
            return branch.startsWith(DETACHED_PREFIX);
        }
    }

    static final String DETACHED_PREFIX = "HEAD@";

    /**
     * @param fingerprint content digest of all roots
     * @param branches    branch per root, sorted by root
     * @param clean       {@code true} when no root has working-tree or unsaved changes
     */
    record Result(@NotNull String fingerprint, @NotNull List<BranchRef> branches, boolean clean) {
        @NotNull String branchKey() {
            return encodeBranchKey(branches);
        }

        @NotNull String branchLabel() {
            if (branches.isEmpty()) return "";
            if (branches.size() == 1) return branches.get(0).branch();
            StringBuilder label = new StringBuilder();
            for (BranchRef ref : branches) {
                if (label.length() > 0) label.append(", ");
                label.append(ref.branch());
            }
            return label.toString();
        }
    }

    /**
     * Computes the fingerprint, or returns {@code null} when it cannot be determined
     * reliably (no Git roots, git failure, merge conflict, too many changed files).
     * Must not be called on the EDT.
     */
    static @Nullable Result compute(@NotNull Project project) {
        if (project.isDisposed()) return null;
        List<GitRepository> repositories =
            new ArrayList<>(GitRepositoryManager.getInstance(project).getRepositories());
        if (repositories.isEmpty()) return null;
        repositories.sort(Comparator.comparing(repo -> repo.getRoot().getPath()));

        Map<String, String> unsaved = unsavedDocumentHashes();
        MessageDigest total = sha256();
        total.update((PROTOCOL + "\n").getBytes(StandardCharsets.UTF_8));
        total.update(configurationSalt(project).getBytes(StandardCharsets.UTF_8));
        List<BranchRef> branches = new ArrayList<>(repositories.size());
        boolean clean = true;
        for (GitRepository repository : repositories) {
            String root = repository.getRoot().getPath();
            String revision = repository.getCurrentRevision();
            if (revision == null) return null; // no commits yet
            String branch = repository.getCurrentBranchName();
            branches.add(new BranchRef(root, branch != null
                ? branch
                : DETACHED_PREFIX + revision.substring(0, Math.min(7, revision.length()))));

            RootDigest digest = digestRoot(root, unsaved);
            if (digest == null) return null;
            clean &= digest.clean();
            total.update(("root=" + root + "\n" + digest.hex() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return new Result(HexFormat.of().formatHex(total.digest()), List.copyOf(branches), clean);
    }

    record RootDigest(@NotNull String hex, boolean clean) {
    }

    static @Nullable RootDigest digestRoot(@NotNull String root, @NotNull Map<String, String> unsaved) {
        String status = runGitToString(root,
            "status", "--porcelain=v1", "-z", "--untracked-files=all", "--no-renames",
            "--ignore-submodules=none");
        if (status == null) return null;
        List<StatusEntry> entries = parsePorcelainZ(status);
        if (entries == null || entries.size() > MAX_WORKTREE_OVERRIDES) return null;

        // Paths whose working-tree content differs from the index (or that are untracked).
        TreeMap<String, String> overrides = new TreeMap<>();
        for (StatusEntry entry : entries) {
            if (entry.isConflict()) return null;
            if (!entry.worktreeDiffers()) continue;
            overrides.put(entry.path(), blobIdOf(Path.of(root, entry.path())));
        }
        String prefix = root.endsWith("/") ? root : root + "/";
        for (Map.Entry<String, String> doc : unsaved.entrySet()) {
            if (doc.getKey().startsWith(prefix)) {
                overrides.put(doc.getKey().substring(prefix.length()), doc.getValue());
            }
        }

        MessageDigest digest = sha256();
        TreeMap<String, String> pending = new TreeMap<>(overrides);
        boolean ok = streamGit(root, line -> {
            // "<mode> <sha> <stage>\t<path>"
            int tab = line.indexOf('\t');
            if (tab < 0) return;
            String path = line.substring(tab + 1);
            String[] meta = line.substring(0, tab).split(" ");
            if (meta.length < 3) return;
            String override = pending.remove(path);
            String blob = override != null ? override : meta[1];
            if ("-".equals(blob)) return; // deleted in working tree
            digest.update((path + "\u0000" + blob + "\n").getBytes(StandardCharsets.UTF_8));
        }, "ls-files", "-s", "-z");
        if (!ok) return null;
        // Remaining overrides are untracked files (not in the index).
        for (Map.Entry<String, String> extra : pending.entrySet()) {
            if ("-".equals(extra.getValue())) continue;
            digest.update(("+" + extra.getKey() + "\u0000" + extra.getValue() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        }
        return new RootDigest(HexFormat.of().formatHex(digest.digest()), overrides.isEmpty());
    }

    /** One {@code git status --porcelain=v1 -z} record. */
    record StatusEntry(char index, char worktree, @NotNull String path) {
        boolean isUntracked() {
            return index == '?' && worktree == '?';
        }

        boolean isConflict() {
            return index == 'U' || worktree == 'U'
                || (index == 'A' && worktree == 'A')
                || (index == 'D' && worktree == 'D');
        }

        /** Working-tree content differs from the index entry (or there is no index entry). */
        boolean worktreeDiffers() {
            return isUntracked() || worktree != ' ';
        }
    }

    /** Parses NUL-separated porcelain v1 output; returns {@code null} on malformed input. */
    static @Nullable List<StatusEntry> parsePorcelainZ(@NotNull String output) {
        List<StatusEntry> entries = new ArrayList<>();
        int start = 0;
        while (start < output.length()) {
            int end = output.indexOf('\u0000', start);
            if (end < 0) end = output.length();
            String record = output.substring(start, end);
            start = end + 1;
            if (record.isEmpty()) continue;
            if (record.length() < 4 || record.charAt(2) != ' ') return null;
            char x = record.charAt(0);
            char y = record.charAt(1);
            entries.add(new StatusEntry(x, y, record.substring(3)));
            if (x == 'R' || x == 'C') {
                // Rename/copy records carry the source path as a second NUL-terminated field.
                int skip = output.indexOf('\u0000', start);
                start = skip < 0 ? output.length() : skip + 1;
            }
        }
        return entries;
    }

    /** Git blob id of a working-tree file, {@code "-"} if it no longer exists. */
    static @NotNull String blobIdOf(@NotNull Path file) {
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return "-";
            if (Files.isDirectory(file)) {
                return "dir:" + Files.getLastModifiedTime(file).toMillis();
            }
            if (Files.isSymbolicLink(file)) {
                return gitBlobId(Files.readSymbolicLink(file).toString().getBytes(StandardCharsets.UTF_8));
            }
            long size = Files.size(file);
            if (size > MAX_HASHED_FILE_BYTES) {
                return "big:" + size + ":" + Files.getLastModifiedTime(file).toMillis();
            }
            return gitBlobId(Files.readAllBytes(file));
        } catch (IOException | RuntimeException e) {
            return "unreadable:" + System.nanoTime(); // never matches a stored fingerprint
        }
    }

    /** Same id {@code git hash-object} prints for {@code content}. */
    static @NotNull String gitBlobId(byte @NotNull [] content) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(("blob " + content.length + "\u0000").getBytes(StandardCharsets.US_ASCII));
            sha1.update(content);
            return HexFormat.of().formatHex(sha1.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Compiler-relevant project model: module names, SDKs and dependency names (library
     * names carry versions, e.g. {@code Maven: g:a:1.2}). A dependency bump or SDK switch
     * therefore yields a new fingerprint even when no tracked file changed.
     */
    private static @NotNull String configurationSalt(@NotNull Project project) {
        return ReadAction.compute(() -> {
            if (project.isDisposed()) return "";
            StringBuilder salt = new StringBuilder("sdk=")
                .append(ProjectRootManager.getInstance(project).getProjectSdkName()).append('\n');
            Module[] modules = ModuleManager.getInstance(project).getModules().clone();
            java.util.Arrays.sort(modules, Comparator.comparing(Module::getName));
            for (Module module : modules) {
                salt.append("module=").append(module.getName()).append('\n');
                for (OrderEntry entry : ModuleRootManager.getInstance(module).getOrderEntries()) {
                    salt.append(' ').append(entry.getPresentableName()).append('\n');
                }
            }
            return HexFormat.of().formatHex(sha256().digest(salt.toString().getBytes(StandardCharsets.UTF_8)))
                + "\n";
        });
    }

    private static @NotNull Map<String, String> unsavedDocumentHashes() {
        return ReadAction.compute(() -> {
            Map<String, String> hashes = new TreeMap<>();
            FileDocumentManager manager = FileDocumentManager.getInstance();
            for (Document document : manager.getUnsavedDocuments()) {
                VirtualFile file = manager.getFile(document);
                if (file == null || !file.isInLocalFileSystem()) continue;
                hashes.put(file.getPath(),
                    gitBlobId(document.getText().getBytes(file.getCharset())));
            }
            return hashes;
        });
    }

    private static @Nullable String runGitToString(@NotNull String root, @NotNull String... args) {
        StringBuilder out = new StringBuilder();
        boolean ok = streamGit(root, record -> out.append(record).append('\u0000'), args);
        return ok ? out.toString() : null;
    }

    @FunctionalInterface
    private interface RecordSink {
        void accept(@NotNull String record);
    }

    /** Runs git and streams NUL-separated stdout records into {@code sink} without buffering it all. */
    private static boolean streamGit(@NotNull String root, @NotNull RecordSink sink, @NotNull String... args) {
        List<String> command = new ArrayList<>(args.length + 3);
        command.add("git");
        command.add("--no-optional-locks");
        command.add("-c");
        command.add("core.quotepath=off");
        command.addAll(List.of(args));
        Process process = null;
        ScheduledFuture<?> watchdog = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                .directory(Path.of(root).toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
            process = builder.start();
            Process started = process;
            watchdog = AppExecutorUtil.getAppScheduledExecutorService()
                .schedule(started::destroyForcibly, GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            process.getOutputStream().close();
            try (InputStream in = process.getInputStream()) {
                ByteArrayOutputStream record = new ByteArrayOutputStream(256);
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    int from = 0;
                    for (int i = 0; i < read; i++) {
                        if (buffer[i] != 0) continue;
                        record.write(buffer, from, i - from);
                        sink.accept(record.toString(StandardCharsets.UTF_8));
                        record.reset();
                        from = i + 1;
                    }
                    record.write(buffer, from, read - from);
                }
                if (record.size() > 0) sink.accept(record.toString(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            LOG.debug("git " + String.join(" ", args) + " failed in " + root, e);
            return false;
        } finally {
            if (watchdog != null) watchdog.cancel(false);
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static @NotNull MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static @NotNull String encodeBranchKey(@NotNull List<BranchRef> branches) {
        StringBuilder key = new StringBuilder();
        for (BranchRef ref : branches) {
            if (key.length() > 0) key.append('\n');
            key.append(ref.root()).append('\t').append(ref.branch());
        }
        return key.toString();
    }

    static @NotNull List<BranchRef> decodeBranchKey(@Nullable String key) {
        List<BranchRef> refs = new ArrayList<>();
        if (key == null || key.isEmpty()) return refs;
        for (String line : key.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            refs.add(new BranchRef(line.substring(0, tab), line.substring(tab + 1)));
        }
        return refs;
    }
}
