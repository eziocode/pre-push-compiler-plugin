package com.github.prepushchecker;

import junit.framework.TestCase;

import java.nio.file.Path;
import java.util.List;

public class AntLayerImporterTest extends TestCase {
    private static final Path REPO = Path.of("/repo");

    private static final String BUILD_XML = String.join("\n",
        "<?xml version=\"1.0\"?>",
        "<project name=\"crm\" basedir=\".\" default=\"compile\">",
        "  <property name=\"src.dir\" value=\"source\"/>",
        "  <property name=\"src.dir\" value=\"ignored-second-definition\"/>",
        "  <patternset id=\"core.files\">",
        "    <include name=\"com/zoho/crm/core/**\"/>",
        "    <exclude name=\"com/zoho/crm/core/ui/**\"/>",
        "  </patternset>",
        "  <target name=\"compilesrc_crm\" depends=\"compilesrc_crmcore\">",
        "    <javac srcdir=\"${src.dir}\" destdir=\"build/crm\"/>",
        "  </target>",
        "  <target name=\"compilesrc_crmcore\">",
        "    <javac destdir=\"build/core\" includes=\"com/adventnet/crm/common/util/**\">",
        "      <src path=\"${src.dir}\"/>",
        "      <patternset refid=\"core.files\"/>",
        "    </javac>",
        "  </target>",
        "  <target name=\"docs\"><echo message=\"no javac\"/></target>",
        "</project>");

    public void testOneLayerPerJavacTargetInDependsOrder() throws Exception {
        AntLayerImporter.Result result = AntLayerImporter.importXml(BUILD_XML, REPO, REPO);
        List<AntLayerImporter.JavacTarget> targets = result.targets();
        assertEquals(2, targets.size());

        AntLayerImporter.JavacTarget core = targets.get(0);
        assertEquals("compilesrc_crmcore", core.name());
        assertEquals(List.of("source"), core.srcDirs());
        assertEquals(List.of("com/adventnet/crm/common/util/**", "com/zoho/crm/core/**"), core.includes());
        assertEquals(List.of("com/zoho/crm/core/ui/**"), core.excludes());

        AntLayerImporter.JavacTarget crm = targets.get(1);
        assertEquals("compilesrc_crm", crm.name());
        assertEquals(List.of("source"), crm.srcDirs());
        assertTrue(crm.includes().isEmpty());
        assertTrue(result.todos().toString(), result.todos().isEmpty());
    }

    public void testRenderedRulesParseAndAssignLayers() throws Exception {
        String text = AntLayerImporter.render(AntLayerImporter.importXml(BUILD_XML, REPO, REPO));
        LayerRulesParser.Result parsed = LayerRulesParser.parse(text);
        assertTrue(parsed.problems() + "\n" + text, parsed.ok());
        LayerRules rules = parsed.rules();
        assertEquals("compilesrc_crmcore",
            rules.layerOf("source/com/adventnet/crm/common/util/CrmConstants.java").name());
        assertEquals("compilesrc_crm",
            rules.layerOf("source/com/adventnet/crm/authorization/territory/util/CrmTerritoryConstants.java").name());
        assertEquals("compilesrc_crm", rules.layerOf("source/com/zoho/crm/core/ui/Panel.java").name());
    }

    public void testUnresolvedThingsBecomeTodos() throws Exception {
        String xml = String.join("\n",
            "<project basedir=\".\">",
            "  <import file=\"common-build.xml\"/>",
            "  <target name=\"a\"><javac srcdir=\"${missing.dir}\"/></target>",
            "  <target name=\"b\" depends=\"nope\"><javac srcdir=\"../outside\"/></target>",
            "</project>");
        AntLayerImporter.Result result = AntLayerImporter.importXml(xml, REPO, REPO);
        String todos = result.todos().toString();
        assertTrue(todos, todos.contains("<import file=\"common-build.xml\"> not followed"));
        assertTrue(todos, todos.contains("property ${missing.dir} unresolved"));
        assertTrue(todos, todos.contains("depends on unknown target 'nope'"));
        assertTrue(todos, todos.contains("is outside the repository"));
        assertTrue(AntLayerImporter.render(result).contains("# TODO "));
    }

    public void testBuildFileInSubdirectoryYieldsRepoRelativeRoots() throws Exception {
        String xml = "<project basedir=\"..\"><target name=\"t\"><javac srcdir=\"crm/source\"/></target></project>";
        AntLayerImporter.Result result = AntLayerImporter.importXml(xml, REPO.resolve("build"), REPO);
        assertEquals(List.of("crm/source"), result.targets().get(0).srcDirs());
    }

    public void testDoctypeEntitiesDoNotLoadExternalFiles() throws Exception {
        String xml = "<?xml version=\"1.0\"?>\n"
            + "<!DOCTYPE project [<!ENTITY ext SYSTEM \"file:///etc/passwd\">]>\n"
            + "<project basedir=\".\"><target name=\"t\"><javac srcdir=\"source\"/></target>&ext;</project>";
        AntLayerImporter.Result result = AntLayerImporter.importXml(xml, REPO, REPO);
        assertEquals(1, result.targets().size());
    }

    private static final String CLASSPATH_XML = String.join("\n",
        "<project basedir=\".\">",
        "  <path id=\"core.cp\"><fileset dir=\"lib/core\" includes=\"*.jar\"/></path>",
        "  <target name=\"core\"><javac srcdir=\"source\" destdir=\"build/core\">",
        "    <classpath refid=\"core.cp\"/></javac></target>",
        "  <target name=\"crm\" depends=\"core\"><javac srcdir=\"source\" destdir=\"build/crm\">",
        "    <classpath>",
        "      <path refid=\"core.cp\"/>",
        "      <pathelement location=\"lib/crm/c.jar\"/>",
        "      <pathelement location=\"build/core\"/>",
        "    </classpath></javac></target>",
        "</project>");

    public void testClasspathJarsBelongToFirstLayerListingThem() throws Exception {
        AntLayerImporter.Result result = AntLayerImporter.importXml(CLASSPATH_XML, REPO, REPO);
        assertEquals(List.of("lib/core/*.jar"), result.targets().get(0).libs());
        // core.cp is inherited from layer 0; the class directory build/core is not a jar.
        assertEquals(List.of("lib/crm/c.jar"), result.targets().get(1).libs());
        assertTrue(result.todos().toString(), result.todos().isEmpty());

        LayerRules rules = LayerRulesParser.parse(AntLayerImporter.render(result)).rules();
        assertTrue(rules.hasLibs());
        assertTrue(rules.layers().get(0).libs().get(0).matches("lib/core/a.jar"));
    }

    public void testClasspathAttributeAndBareFilesetDefaultToJars() throws Exception {
        String xml = String.join("\n",
            "<project basedir=\".\">",
            "  <target name=\"t\"><javac srcdir=\"source\" classpath=\"lib/a.jar:lib/b.jar:build/classes\">",
            "    <classpath><fileset dir=\"ext\"/></classpath></javac></target>",
            "</project>");
        List<String> libs = AntLayerImporter.importXml(xml, REPO, REPO).targets().get(0).libs();
        assertEquals(List.of("lib/a.jar", "lib/b.jar", "ext/**/*.jar"), libs);
    }

    public void testJarDiffBetweenIdeAndAnt() throws Exception {
        java.nio.file.Path repo = java.nio.file.Files.createTempDirectory("ant-diff");
        try {
            TestFiles.jar(repo, "lib/core/a.jar", "a/A.class");
            TestFiles.jar(repo, "lib/core/b.jar", "b/B.class");
            TestFiles.jar(repo, "lib/crm/c.jar", "c/C.class");
            AntLayerImporter.Result ant = AntLayerImporter.importXml(CLASSPATH_XML, repo, repo);
            AntLayerImporter.JarDiff diff = AntLayerImporter.diffJars(repo,
                java.util.Set.of("lib/core/a.jar", "lib/crm/c.jar", "lib/extra.jar"), ant);
            assertEquals(java.util.Set.of("lib/extra.jar"), diff.ideOnly());
            assertEquals(java.util.Set.of("lib/core/b.jar"), diff.antOnly());
        } finally {
            TestFiles.delete(repo);
        }
    }
}
