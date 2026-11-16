package com.github.prepushchecker.stats;

import com.github.prepushchecker.commitgen.JsonUtil;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Sends pending anonymous counts. Background thread only; failures are silent and retried later. */
final class StatsUploader {
    private static final Logger LOG = Logger.getInstance(StatsUploader.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String PLUGIN_ID = "com.github.prepushchecker";

    private StatsUploader() {
    }

    static void uploadPending(@NotNull SharedStats stats) {
        if (stats.getConsent() != SharedStats.Consent.YES || !StatsEndpoint.isConfigured()) return;
        SharedStats.Delta delta = stats.pendingDelta();
        if (delta.isEmpty()) return;
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(StatsEndpoint.reportUrl()))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload(stats.installId(), delta)))
                .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            if (code / 100 == 2 || code == 400 || code == 413) {
                // 2xx: counted. 400/413: the server will never accept this body; drop it
                // rather than resend it forever.
                stats.markUploaded(delta);
                if (code != 200) LOG.debug("Stats upload dropped with HTTP " + code);
            } else {
                // 429 (one call per minute per install) and 5xx: keep and retry next round.
                LOG.debug("Stats upload deferred with HTTP " + code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.debug("Stats upload failed; will retry later", e);
        }
    }

    static @NotNull String payload(@NotNull String installId, @NotNull SharedStats.Delta delta) {
        return "{"
            + "\"installId\":" + JsonUtil.quoted(installId)
            + ",\"pluginVersion\":" + JsonUtil.quoted(pluginVersion())
            + ",\"ideBuild\":" + JsonUtil.quoted(ideBuild())
            + ",\"ok\":" + delta.ok()
            + ",\"blocked\":" + delta.blocked()
            + ",\"issues\":" + delta.issues()
            + ",\"timeouts\":" + delta.timeouts()
            + "}";
    }

    private static String pluginVersion() {
        IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
        return descriptor == null ? "" : descriptor.getVersion();
    }

    private static String ideBuild() {
        try {
            return ApplicationInfo.getInstance().getBuild().asStringWithoutProductCode();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
