package com.github.prepushchecker.commitgen;

import com.github.prepushchecker.commitgen.providers.OllamaProvider;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public class OllamaProviderCompletionTest extends BasePlatformTestCase {

    private HttpServer server;
    private String savedBaseUrl;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        savedBaseUrl = CommitMessageSettings.getInstance().settings().ollamaBaseUrl;
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            CommitMessageSettings.getInstance().settings().ollamaBaseUrl = savedBaseUrl;
            if (server != null) server.stop(0);
        } finally {
            super.tearDown();
        }
    }

    public void testCompletionUsesRequestModel() throws Exception {
        AtomicReference<String> recorded = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", ex -> {
            recorded.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"message\":{\"content\":\"x = 1\"}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        CommitMessageSettings.getInstance().settings().ollamaBaseUrl =
            "http://127.0.0.1:" + server.getAddress().getPort();

        String result = new OllamaProvider().generate(
            AiRequest.completion("s", "u", "qwen-test", AiRequest.COMPLETION_FAST_TIMEOUT));

        assertEquals("x = 1", result);
        assertTrue(recorded.get(), recorded.get().contains("\"model\":\"qwen-test\""));
    }
}
