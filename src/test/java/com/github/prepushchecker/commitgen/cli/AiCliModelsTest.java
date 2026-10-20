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
        assertEquals(List.of("gpt-4o", "gpt-4o-mini", "anthropic/claude-3-5-sonnet-latest"),
            AiCliModels.parseLlmModels(out));
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
