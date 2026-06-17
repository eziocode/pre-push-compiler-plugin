package com.github.prepushchecker.commitgen;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Implements the <b>OAuth 2.0 Device Authorization Grant</b> (RFC 8628)
 * for ChatGPT accounts, using the credentials extracted from the Codex CLI.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>POST {@code /deviceauth/usercode} → get {@code device_code} + {@code user_code}.</li>
 *   <li>Show a dialog with the one-time code and an "Open Browser" button.</li>
 *   <li>Poll {@code /deviceauth/token} every 5 s until the user approves or it expires.</li>
 *   <li>Store {@code access_token} and {@code refresh_token} in PasswordSafe.</li>
 * </ol>
 */
public final class ChatGPTOAuthFlow {

    // Codex CLI OAuth constants extracted from the native binary
    private static final String CLIENT_ID        = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String AUTH_BASE        = "https://auth.openai.com";
    private static final String DEVICE_CODE_URL  = AUTH_BASE + "/deviceauth/usercode";
    private static final String DEVICE_TOKEN_URL = AUTH_BASE + "/deviceauth/token";
    private static final String DEVICE_GRANT     = "urn:ietf:params:oauth:grant-type:device_code";
    private static final String REFRESH_GRANT    = "refresh_token";
    private static final String WHOAMI_URL       = AUTH_BASE + "/api/accounts/v1/user-auth-credential/whoami";

    private static final String CRED_SERVICE = "PrePushChecker.ChatGPTOAuth";
    private static final String KEY_ACCESS   = "access_token";
    private static final String KEY_REFRESH  = "refresh_token";

    private ChatGPTOAuthFlow() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts the Device Code flow on a background thread. Shows a non-blocking
     * dialog with the one-time code, polls for approval, then stores the tokens.
     *
     * @param onDone called on the EDT when sign-in completes (success or failure)
     */
    public static void startSignIn(@NotNull Runnable onDone) {
        new Thread(() -> {
            try {
                DeviceCodeResponse dcr = requestDeviceCode();
                // Show dialog on EDT while background thread polls
                ApplicationManager.getApplication().invokeLater(
                    () -> showCodeDialog(dcr, onDone));
            } catch (Exception e) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    Messages.showErrorDialog(
                        "Failed to start ChatGPT sign-in:\n" + e.getMessage(),
                        "ChatGPT Login Error");
                    onDone.run();
                });
            }
        }, "ChatGPT-OAuth-DeviceCode").start();
    }

    /** Returns the stored access token, or {@code null}. */
    @Nullable
    public static String getAccessToken() {
        // 1. PasswordSafe (from browser Device Code login)
        String saved = loadFromSafe(KEY_ACCESS);
        if (saved != null && !saved.isBlank()) return saved;
        // 2. ~/.codex/auth.json (from Codex desktop app — no circular call)
        return readCodexAuthJson();
    }

    /** Returns {@code true} when a token is available. */
    public static boolean isAuthenticated() {
        return getAccessToken() != null;
    }

    /** Clears stored tokens from PasswordSafe. */
    public static void signOut() {
        saveToSafe(KEY_ACCESS,  null);
        saveToSafe(KEY_REFRESH, null);
    }

    /** Reads the access token from {@code ~/.codex/auth.json} without circular dependency. */
    @Nullable
    private static String readCodexAuthJson() {
        try {
            java.nio.file.Path authFile = java.nio.file.Path.of(
                System.getProperty("user.home"), ".codex", "auth.json");
            if (!java.nio.file.Files.isRegularFile(authFile)) return null;
            String json = java.nio.file.Files.readString(authFile, StandardCharsets.UTF_8);
            String token = JsonUtil.extractString(json, "access_token");
            if (token != null && !token.isBlank()) return token;
            return JsonUtil.extractString(json, "OPENAI_API_KEY");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Tries to refresh the access token using the stored refresh token.
     * Returns the new access token, or {@code null} on failure.
     */
    @Nullable
    public static String refreshAccessToken() {
        String refreshToken = loadFromSafe(KEY_REFRESH);
        if (refreshToken == null || refreshToken.isBlank()) return null;
        try {
            String body = "grant_type=" + urlEnc(REFRESH_GRANT)
                + "&client_id=" + urlEnc(CLIENT_ID)
                + "&refresh_token=" + urlEnc(refreshToken);
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15)).build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(AUTH_BASE + "/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String newAccess  = JsonUtil.extractString(resp.body(), "access_token");
                String newRefresh = JsonUtil.extractString(resp.body(), "refresh_token");
                if (newAccess != null) {
                    saveToSafe(KEY_ACCESS, newAccess);
                    if (newRefresh != null) saveToSafe(KEY_REFRESH, newRefresh);
                    return newAccess;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Device code flow ──────────────────────────────────────────────────────

    private record DeviceCodeResponse(
            @NotNull String deviceCode,
            @NotNull String userCode,
            @NotNull String verificationUri,
            int expiresIn,
            int interval) {}

    private static @NotNull DeviceCodeResponse requestDeviceCode() throws Exception {
        String body = "client_id=" + urlEnc(CLIENT_ID);
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(DEVICE_CODE_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Device auth failed (" + resp.statusCode() + "): " + resp.body());
        }
        String deviceCode      = JsonUtil.extractString(resp.body(), "device_code");
        String userCode        = JsonUtil.extractString(resp.body(), "user_code");
        String verificationUri = JsonUtil.extractString(resp.body(), "verification_uri");
        String expiresInStr    = JsonUtil.extractString(resp.body(), "expires_in");
        String intervalStr     = JsonUtil.extractString(resp.body(), "interval");

        if (deviceCode == null || userCode == null) {
            throw new RuntimeException("Unexpected device auth response: " + resp.body());
        }
        int expiresIn = expiresInStr != null ? parseIntSafe(expiresInStr, 900) : 900;
        int interval  = intervalStr  != null ? parseIntSafe(intervalStr, 5)    : 5;
        if (verificationUri == null) verificationUri = "https://chatgpt.com/apps/";
        return new DeviceCodeResponse(deviceCode, userCode, verificationUri, expiresIn, interval);
    }

    private static void showCodeDialog(@NotNull DeviceCodeResponse dcr, @NotNull Runnable onDone) {
        DeviceCodeDialog dialog = new DeviceCodeDialog(dcr);
        // Start polling thread before showing dialog
        String deviceCode = dcr.deviceCode();
        int intervalMs    = Math.max(dcr.interval(), 5) * 1000;
        int expiresInMs   = dcr.expiresIn() * 1000;

        Thread poller = new Thread(() -> {
            long deadline = System.currentTimeMillis() + expiresInMs;
            while (System.currentTimeMillis() < deadline && dialog.isShowing()) {
                try { Thread.sleep(intervalMs); } catch (InterruptedException e) { break; }
                if (!dialog.isShowing()) break;
                try {
                    String result = pollForToken(deviceCode, dcr.deviceCode());
                    if (result != null) {
                        dialog.setApproved();
                        ApplicationManager.getApplication().invokeLater(() -> {
                            dialog.close(DialogWrapper.OK_EXIT_CODE);
                            Messages.showInfoMessage(
                                "Signed in to ChatGPT successfully!\n"
                                    + "You can now use the Codex (ChatGPT Account) provider.",
                                "ChatGPT Sign-In Complete");
                            onDone.run();
                        });
                        return;
                    }
                } catch (PendingException ignored) {
                    // still waiting
                } catch (Exception e) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        dialog.close(DialogWrapper.CANCEL_EXIT_CODE);
                        Messages.showErrorDialog("Sign-in failed: " + e.getMessage(), "ChatGPT Login Error");
                        onDone.run();
                    });
                    return;
                }
            }
            // Timeout or dialog closed
            ApplicationManager.getApplication().invokeLater(() -> {
                if (dialog.isShowing()) dialog.close(DialogWrapper.CANCEL_EXIT_CODE);
                onDone.run();
            });
        }, "ChatGPT-OAuth-Poller");
        poller.setDaemon(true);
        poller.start();

        dialog.show();
        // Ensure onDone is called if dialog dismissed without completion
        if (!dialog.isApproved()) {
            onDone.run();
        }
    }

    /** Returns the access token if approved, throws {@link PendingException} if still pending. */
    @Nullable
    private static String pollForToken(@NotNull String deviceCode, @NotNull String unused)
            throws Exception {
        String body = "grant_type=" + urlEnc(DEVICE_GRANT)
            + "&device_code=" + urlEnc(deviceCode)
            + "&client_id=" + urlEnc(CLIENT_ID);
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(DEVICE_TOKEN_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        String error = JsonUtil.extractString(resp.body(), "error");
        if ("authorization_pending".equals(error) || "slow_down".equals(error)) {
            throw new PendingException();
        }
        if (error != null) {
            String desc = JsonUtil.extractString(resp.body(), "error_description");
            throw new RuntimeException(desc != null ? desc : error);
        }
        String accessToken  = JsonUtil.extractString(resp.body(), "access_token");
        String refreshToken = JsonUtil.extractString(resp.body(), "refresh_token");
        if (accessToken != null) {
            saveToSafe(KEY_ACCESS, accessToken);
            if (refreshToken != null) saveToSafe(KEY_REFRESH, refreshToken);
            return accessToken;
        }
        throw new PendingException();
    }

    private static final class PendingException extends Exception {
        PendingException() { super(null, null, true, false); }
    }

    // ── PasswordSafe helpers ──────────────────────────────────────────────────

    private static CredentialAttributes credAttrs(@NotNull String key) {
        return new CredentialAttributes(CRED_SERVICE, key);
    }

    private static void saveToSafe(@NotNull String key, @Nullable String value) {
        PasswordSafe.getInstance().set(
            credAttrs(key),
            value == null || value.isBlank() ? null : new Credentials(key, value));
    }

    @Nullable
    private static String loadFromSafe(@NotNull String key) {
        Credentials c = PasswordSafe.getInstance().get(credAttrs(key));
        return c == null ? null : c.getPasswordAsString();
    }

    // ── Dialog ────────────────────────────────────────────────────────────────

    private static final class DeviceCodeDialog extends DialogWrapper {
        private final DeviceCodeResponse dcr;
        private volatile boolean approved = false;
        private JBLabel statusLabel;

        DeviceCodeDialog(@NotNull DeviceCodeResponse dcr) {
            super(true);
            this.dcr = dcr;
            setTitle("Sign in to ChatGPT");
            setModal(false);
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setPreferredSize(new Dimension(420, 220));
            GridBagConstraints g = new GridBagConstraints();
            g.insets = new Insets(6, 6, 6, 6);
            g.anchor = GridBagConstraints.WEST;
            g.fill = GridBagConstraints.HORIZONTAL;
            g.weightx = 1;
            g.gridwidth = 2;

            g.gridy = 0;
            panel.add(new JBLabel(
                "<html><b>Sign in with your ChatGPT account</b><br>"
                    + "Open the link below and enter the one-time code:</html>"), g);

            // User code — big, easy to read
            g.gridy = 1;
            JBLabel codeLabel = new JBLabel(
                "<html><span style='font-size:18pt;font-weight:bold;letter-spacing:4px'>"
                    + dcr.userCode() + "</span></html>");
            panel.add(codeLabel, g);

            // Buttons row
            JButton openBrowserBtn = new JButton("Open Browser");
            openBrowserBtn.addActionListener(ev -> BrowserUtil.browse(dcr.verificationUri()));
            JButton copyBtn = new JButton("Copy Code");
            copyBtn.addActionListener(ev -> {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(dcr.userCode()), null);
                copyBtn.setText("Copied!");
            });

            g.gridy = 2; g.gridwidth = 1; g.weightx = 0;
            panel.add(openBrowserBtn, g);
            g.gridx = 1; g.weightx = 0;
            panel.add(copyBtn, g);
            g.gridx = 0; g.gridwidth = 2; g.weightx = 1;

            g.gridy = 3;
            JBLabel urlLabel = new JBLabel(
                "<html><i>Verification URL: <a href='" + dcr.verificationUri() + "'>"
                    + dcr.verificationUri() + "</a></i></html>");
            panel.add(urlLabel, g);

            // Status
            statusLabel = new JBLabel(
                "<html><i>⏳ Waiting for you to approve in the browser…</i></html>");
            g.gridy = 4;
            panel.add(statusLabel, g);

            // Auto-open browser
            ApplicationManager.getApplication().invokeLater(
                () -> BrowserUtil.browse(dcr.verificationUri()));

            return panel;
        }

        @Override
        protected Action @NotNull [] createActions() {
            return new Action[]{getCancelAction()};
        }

        void setApproved() {
            approved = true;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (statusLabel != null)
                    statusLabel.setText("<html><b style='color:green'>✓ Signed in successfully!</b></html>");
            });
        }

        boolean isApproved() { return approved; }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static @NotNull String urlEnc(@NotNull String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static int parseIntSafe(@NotNull String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}
