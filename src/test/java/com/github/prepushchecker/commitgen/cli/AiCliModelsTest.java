package com.github.prepushchecker.commitgen.cli;

import junit.framework.TestCase;

import java.util.List;

public class AiCliModelsTest extends TestCase {

    public void testParseLlmModels() {
        String out = "OpenAI Chat: gpt-4o (aliases: 4o)\n"
            + "OpenAI Chat: gpt-4o-mini\n"
            + "Anthropic Messages: anthropic/claude-3-5-sonnet-latest (aliases: claude-3.5-sonnet)\n"
            + "Default: gpt-4o-mini\n"
            + "garbage line\n";
        assertEquals(List.of("gpt-4o-mini", "gpt-4o", "anthropic/claude-3-5-sonnet-latest"),
            AiCliModels.parseLlmModels(out));
    }

    public void testParseLlmModelsPutsDefaultFirst() {
        String out = "OpenAI Chat: gpt-4o\nOpenAI Chat: gpt-4o-mini\nDefault: gpt-4o-mini\n";
        assertEquals(List.of("gpt-4o-mini", "gpt-4o"), AiCliModels.parseLlmModels(out));
    }

    public void testCodexCatalogueDropsHiddenUnsupportedAndDeprecated() {
        // Shape of `codex debug models` (trimmed); hide entries are real, the rest are synthetic.
        String json = "{\"models\":["
            + "{\"slug\":\"gpt-6-astra\",\"visibility\":\"list\",\"supported_in_api\":true,\"priority\":3,\"deprecation\":null},"
            + "{\"slug\":\"gpt-6.1-sol\",\"visibility\":\"list\",\"supported_in_api\":true,\"priority\":1},"
            + "{\"slug\":\"gpt-reserve\",\"visibility\":\"hide\",\"supported_in_api\":true,\"priority\":5},"
            + "{\"slug\":\"codex-auto-review\",\"visibility\":\"hide\",\"priority\":10},"
            + "{\"slug\":\"no-api\",\"visibility\":\"list\",\"supported_in_api\":false,\"priority\":4},"
            + "{\"slug\":\"old\",\"visibility\":\"list\",\"priority\":2,\"deprecation\":{\"date\":\"2026-01-01\"}}"
            + "]}";
        assertEquals(List.of("gpt-6.1-sol", "gpt-6-astra"), AiCliModels.parseCodexCatalogue(json));
    }

    public void testCodexCatalogueGarbageIsEmpty() {
        assertTrue(AiCliModels.parseCodexCatalogue("not json").isEmpty());
        assertTrue(AiCliModels.parseCodexCatalogue("{}").isEmpty());
    }

    public void testCodexStaticDropsRetiredIds() {
        assertFalse(AiCliModels.CODEX_STATIC.contains("gpt-5-codex"));
        assertFalse(AiCliModels.CODEX_STATIC.contains("o3"));
    }

    public void testCopilotModelsFilterDisabledAndNonChat() {
        String json = "{\"data\":["
            + "{\"id\":\"gpt-4.1\",\"model_picker_enabled\":true,\"policy\":{\"state\":\"enabled\"},\"capabilities\":{\"type\":\"chat\"}},"
            + "{\"id\":\"claude-x\",\"model_picker_enabled\":true,\"policy\":{\"state\":\"disabled\"},\"capabilities\":{\"type\":\"chat\"}},"
            + "{\"id\":\"text-embedding-3\",\"model_picker_enabled\":false,\"capabilities\":{\"type\":\"embeddings\"}},"
            + "{\"id\":\"hidden\",\"model_picker_enabled\":false,\"capabilities\":{\"type\":\"chat\"}},"
            + "{\"id\":\"gpt-5\",\"capabilities\":{\"type\":\"chat\"}}]}";
        assertEquals(List.of("gpt-4.1", "gpt-5"), AiCliModels.parseCopilotModels(json));
    }

    public void testParseTomlModels() {
        String toml = "model = \"gpt-5-codex\"\n[profiles.fast]\n  model = 'o4-mini'\nmodel_provider = \"x\"\n";
        assertEquals(List.of("gpt-5-codex", "o4-mini"), AiCliModels.parseTomlModels(toml));
    }

    public void testParseJsonIds() {
        String json = "{\"data\":[{\"id\":\"claude-opus-5-5\",\"type\":\"model\"},{\"id\":\"claude-haiku-4-5\"}]}";
        assertEquals(List.of("claude-opus-5-5", "claude-haiku-4-5"), AiCliModels.parseJsonIds(json));
    }

    public void testParseLines() {
        assertEquals(List.of("alpha", "beta"),
            AiCliModels.parseLines("# models\nalpha  default\n\n beta\nalpha\n"));
    }
}
