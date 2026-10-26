package com.github.prepushchecker;

import junit.framework.TestCase;

import java.util.List;

public class LayerDiagnosticTest extends TestCase {
    public void testParsesForbiddenJar() {
        LayerDiagnostic d = LayerDiagnostic.parse("[source/A.java (3:1)] Forbidden jar: 'core' file imports a.B,"
            + " available only from jars layer 'core' unchecked: lib/b.jar, dist/TP.zip!/lib/c.jar."
            + " Check one in Layers or fix the import");
        assertNotNull(d);
        assertEquals(LayerDiagnostic.Kind.FORBIDDEN_JAR, d.kind());
        assertEquals("core", d.layer());
        assertEquals(List.of("lib/b.jar", "dist/TP.zip!/lib/c.jar"), d.jars());
    }

    public void testDropsTheMoreTail() {
        LayerDiagnostic d = LayerDiagnostic.parse("[A.java (1:1)] Forbidden jar: 'my layer' file imports a.B,"
            + " available only from jars layer 'my layer' unchecked: a.jar, b.jar, c.jar and 2 more."
            + " Check one in Layers or fix the import");
        assertEquals("my layer", d.layer());
        assertEquals(List.of("a.jar", "b.jar", "c.jar"), d.jars());
    }

    public void testParsesLaterLayerJar() {
        LayerDiagnostic d = LayerDiagnostic.parse("[source/A.java (2:1)] Layer violation: 'crmcore' file imports"
            + " org.crm.CrmUtil, found only in jar lib/crm.jar of later layer 'crm'; the 'crmcore' build step's"
            + " classpath does not include it");
        assertEquals(LayerDiagnostic.Kind.LATER_LAYER_JAR, d.kind());
        assertEquals("crmcore", d.layer());
        assertEquals(List.of("lib/crm.jar"), d.jars());
    }

    public void testOtherDiagnosticsAreNotLayerJarDiagnostics() {
        assertNull(LayerDiagnostic.parse("[x.java (1:1)] cannot find symbol"));
        assertNull(LayerDiagnostic.parse("[x.java (1:1)] Layer violation: 'a' file imports p.Q from later layer 'b'"
            + " (s/p/Q.java); the 'a' build step cannot see it"));
    }
}
