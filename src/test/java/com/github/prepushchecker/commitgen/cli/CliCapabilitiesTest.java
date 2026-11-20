package com.github.prepushchecker.commitgen.cli;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public class CliCapabilitiesTest extends TestCase {

    public void testParseFindsLongOptions() {
        Set<String> flags = CliCapabilities.parse(
            "Usage: claude [options]\n"
                + "  -p, --print               Print response\n"
                + "  --tools <tools...>        Tools\n"
                + "  --strict-mcp-config       Only use MCP servers from --mcp-config\n"
                + "  see https://x.dev/--not-a-flag and foo--bar\n");
        assertTrue(flags.contains("--print"));
        assertTrue(flags.contains("--tools"));
        assertTrue(flags.contains("--strict-mcp-config"));
        assertTrue(flags.contains("--mcp-config"));
        assertFalse(flags.contains("--bar"));
    }

    public void testProbeRunsHelpOnceAndCaches() throws Exception {
        CliCapabilities.clearCache();
        Path dir = Files.createTempDirectory("caps");
        Path counter = dir.resolve("count");
        Path cli = dir.resolve("fakecli");
        Files.writeString(cli, "#!/bin/sh\necho x >> '" + counter + "'\necho '  --tools <t>   --ephemeral'\n");
        assertTrue(cli.toFile().setExecutable(true));
        CliCapabilities caps = CliCapabilities.probe(cli.toString());
        assertTrue(caps.known());
        assertTrue(caps.supports("--tools"));
        assertTrue(caps.supports("--ephemeral"));
        assertFalse(caps.supports("--strict-mcp-config"));
        CliCapabilities.probe(cli.toString());
        assertEquals(1, Files.readAllLines(counter).size());
        CliCapabilities.clearCache();
    }

    public void testUnknownWhenMissing() {
        assertFalse(CliCapabilities.probe("/no/such/cli").known());
        assertFalse(CliCapabilities.probe(null).known());
    }
}
