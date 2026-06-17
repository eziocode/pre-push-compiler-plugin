package com.github.prepushchecker.commitgen;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Manages ChatGPT authentication for the Codex provider.
 *
 * <h3>Login strategy</h3>
 * <ol>
 *   <li><b>Codex CLI ({@code codex login})</b> — opens the browser-based
 *       ChatGPT OAuth flow via the native CLI. The CLI handles Cloudflare
 *       protection and stores the token in {@code ~/.codex/auth.json}.</li>
 *   <li><b>Manual API key</b> — fallback if the Codex CLI is not installed.</li>
 * </ol>
 *
 * <h3>Token resolution order</h3>
 * <ol>
 *   <li>PasswordSafe (manual API key entered in Settings).</li>
 *   <li>{@code ~/.codex/auth.json} access_token (written by the Codex CLI).</li>
 * </ol>
 */
public final class ChatGPTOAuthFlow {

    private static final String CRED_SERVICE = "PrePushChecker.ChatGPTManualKey";
    private static final String CRED_KEY     = "manual_api_key";

    private ChatGPTOAuthFlow() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the active access token, or {@code null} when not signed in.
     * Resolution order: manual PasswordSafe key → ~/.codex/auth.json.
     */
    @Nullable
    public static String getAccessToken() {
        String manual = loadManualKey();
        if (manual != null && !manual.isBlank()) return manual;
        return readCodexAuthJson();
    }

    /** Returns {@code true} when any credential is available. */
    public static boolean isAuthenticated() {
        return getAccessToken() != null;
    }

    /**
     * Returns a one-line human-readable status string (runs on background
     * thread — do NOT call from EDT).
     */
    @NotNull
    public static String getStatusText() {
        // 1. Try codex login status (fast, no blocking)
        String codexPath = CliPathResolver.resolve(null, "codex");
        if (codexPath != null) {
            try {
                ProcessBuilder pb = new ProcessBuilder(codexPath, "login", "status");
                pb.redirectErrorStream(true);
                CliPathResolver.injectAugmentedPath(pb);
                Process proc = pb.start();
                String out;
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    out = r.lines().collect(Collectors.joining(" ")).trim();
                }
                boolean done = proc.waitFor(8, TimeUnit.SECONDS);
                if (!done) proc.destroyForcibly();
                if (!out.isBlank()) return out; // e.g. "Logged in using ChatGPT"
            } catch (Exception ignored) {}
        }

        // 2. Check ~/.codex/auth.json
        if (readCodexAuthJson() != null) return "Signed in (token found in ~/.codex/auth.json)";

        // 3. Manual API key
        if (loadManualKey() != null) return "Manual API key configured";

        return null; // not signed in
    }

    /**
     * Opens the ChatGPT login flow via {@code codex login} in an OS terminal.
     * Calls {@code onDone} on the EDT when the terminal is launched (user still
     * needs to complete sign-in in the terminal window).
     */
    public static void startSignIn(@NotNull Runnable onDone) {
        new Thread(() -> {
            String codexPath = CliPathResolver.resolve(null, "codex");

            if (codexPath == null || !Files.isExecutable(Path.of(codexPath))) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    showNoCodexDialog();
                    onDone.run();
                });
                return;
            }

            // Launch `codex login` in a new terminal window
            boolean launched = launchInTerminal(codexPath + " login");
            ApplicationManager.getApplication().invokeLater(() -> {
                if (launched) {
                    Messages.showInfoMessage(
                        "A terminal window has opened running 'codex login'.\n\n"
                            + "• Complete the sign-in in your browser\n"
                            + "• Return here and click 'Refresh Status'",
                        "ChatGPT Sign-In");
                } else {
                    // Terminal launch failed — show manual instructions
                    showManualLoginDialog(codexPath);
                }
                onDone.run();
            });
        }, "ChatGPT-Login-Launch").start();
    }

    /** Clears the manually stored API key from PasswordSafe. */
    public static void signOut() {
        PasswordSafe.getInstance().set(
            new CredentialAttributes(CRED_SERVICE, CRED_KEY), null);

        // Also attempt `codex logout` non-interactively
        new Thread(() -> {
            String codexPath = CliPathResolver.resolve(null, "codex");
            if (codexPath == null) return;
            try {
                ProcessBuilder pb = new ProcessBuilder(codexPath, "logout");
                pb.redirectErrorStream(true);
                CliPathResolver.injectAugmentedPath(pb);
                Process proc = pb.start();
                proc.waitFor(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }, "ChatGPT-Logout").start();
    }

    /** Stores a manual API key in PasswordSafe. */
    public static void saveManualKey(@NotNull String key) {
        PasswordSafe.getInstance().set(
            new CredentialAttributes(CRED_SERVICE, CRED_KEY),
            key.isBlank() ? null : new Credentials(CRED_KEY, key));
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    @Nullable
    static String readCodexAuthJson() {
        try {
            Path authFile = Path.of(System.getProperty("user.home"), ".codex", "auth.json");
            if (!Files.isRegularFile(authFile)) return null;
            String json = Files.readString(authFile, StandardCharsets.UTF_8);
            String token = JsonUtil.extractString(json, "access_token");
            if (token != null && !token.isBlank()) return token;
            return JsonUtil.extractString(json, "OPENAI_API_KEY");
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String loadManualKey() {
        Credentials c = PasswordSafe.getInstance().get(
            new CredentialAttributes(CRED_SERVICE, CRED_KEY));
        return c == null ? null : c.getPasswordAsString();
    }

    /**
     * Launches {@code command} in a new OS terminal window.
     * Returns {@code true} if the terminal was opened successfully.
     */
    private static boolean launchInTerminal(@NotNull String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("mac")) {
                // macOS: open a new Terminal.app tab
                String[] script = {
                    "osascript", "-e",
                    "tell application \"Terminal\" to do script \"" + command + "\""
                };
                Runtime.getRuntime().exec(script);
                return true;
            } else if (os.contains("linux")) {
                // Try common Linux terminal emulators
                for (String term : new String[]{"gnome-terminal", "xterm", "konsole", "xfce4-terminal"}) {
                    try {
                        Runtime.getRuntime().exec(new String[]{term, "--", "sh", "-c", command});
                        return true;
                    } catch (Exception ignored) {}
                }
            } else if (os.contains("win")) {
                Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", "cmd", "/k", command});
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void showNoCodexDialog() {
        int choice = Messages.showYesNoCancelDialog(
            "The Codex CLI is not installed.\n\n"
                + "To sign in with your ChatGPT account:\n"
                + "  npm install -g @openai/codex\n"
                + "  codex login\n\n"
                + "Alternatively, get an OpenAI API key from platform.openai.com\n"
                + "and enter it in the 'API key (fallback)' field.\n\n"
                + "Open API keys page in browser?",
            "Codex CLI Not Found",
            "Open API Keys Page", "Cancel", "Copy Install Command",
            Messages.getWarningIcon());
        if (choice == Messages.YES) {
            openBrowserSafely("https://platform.openai.com/api-keys");
        } else if (choice == Messages.CANCEL) {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection("npm install -g @openai/codex && codex login"), null);
        }
    }

    private static void showManualLoginDialog(@NotNull String codexPath) {
        String msg = "Could not open a terminal automatically.\n\n"
            + "Please run this command in a terminal:\n\n"
            + "    " + codexPath + " login\n\n"
            + "Then return here and click 'Refresh Status'.";
        String[] options = {"Copy Command", "OK"};
        int choice = Messages.showDialog(msg, "ChatGPT Sign-In",
            options, 1, Messages.getInformationIcon());
        if (choice == 0) {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(codexPath + " login"), null);
        }
    }

    // ── Browser utility ───────────────────────────────────────────────────────

    static void openBrowserSafely(@NotNull String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(new URI(url));
                    return;
                }
            }
        } catch (Exception ignored) {}
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("mac"))   { Runtime.getRuntime().exec(new String[]{"open", url}); return; }
            if (os.contains("linux")) { Runtime.getRuntime().exec(new String[]{"xdg-open", url}); return; }
            if (os.contains("win"))   { Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", url}); }
        } catch (Exception ignored) {}
    }
}
