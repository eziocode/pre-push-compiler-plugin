package com.github.prepushchecker.stats;

import junit.framework.TestCase;

public class StatsUploaderVersionTest extends TestCase {

    /** processResources must stamp the real version; an unexpanded placeholder or empty string means it did not. */
    public void testPluginVersionComesFromBuild() {
        String v = StatsUploader.pluginVersion();
        assertTrue("version: '" + v + "'", v.matches("\\d+\\.\\d+\\.\\d+.*"));
    }
}
