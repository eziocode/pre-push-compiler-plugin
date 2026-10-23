package com.github.prepushchecker;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class CrossLayerJarCheckTest extends TestCase {
    private static final String RULES = String.join("\n",
        "root source",
        "layer crmcore",
        "  include source/com/zoho/crm/core/**",
        "  lib lib/core.jar",
        "layer crm",
        "  include source/**",
        "  lib lib/crm.jar",
        "  lib lib/both.jar",
        "");

    private static final String CORE_FILE = "source/com/zoho/crm/core/Core.java";
    private static final String CRM_FILE = "source/com/adventnet/crm/Crm.java";

    private Path repo;
    private LayerRules rules;

    @Override
    protected void setUp() throws Exception {
        repo = Files.createTempDirectory("layer-jar-check");
        rules = LayerRulesParser.parse(RULES).rules();
        TestFiles.jar(repo, "lib/core.jar", "org/core/CoreUtil.class", "org/both/Shared.class");
        TestFiles.jar(repo, "lib/crm.jar", "org/crm/CrmUtil.class", "org/crm/CrmUtil$Inner.class");
        TestFiles.jar(repo, "lib/both.jar", "org/both/Shared.class");
    }

    @Override
    protected void tearDown() throws Exception {
        TestFiles.delete(repo);
    }

    private List<String> analyze(String file, String... imports) {
        StringBuilder source = new StringBuilder("package p;\n");
        for (String imp : imports) source.append("import ").append(imp).append(";\n");
        source.append("class X {}\n");
        try (JarClassIndex jars = new JarClassIndex(LayerJars.resolve(repo, rules))) {
            return CrossLayerImportCheck.analyze(rules, Set.of(file), Map.of(file, source.toString()), jars);
        }
    }

    public void testCoreFileImportingClassOnlyInLaterLayerJarIsFlagged() {
        List<String> errors = analyze(CORE_FILE, "org.crm.CrmUtil");
        assertEquals(errors.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).startsWith("[" + CORE_FILE + " (2:1)] Layer violation: 'crmcore'"));
        assertTrue(errors.get(0), errors.get(0).contains("found only in jar lib/crm.jar of later layer 'crm'"));
        assertEquals(CORE_FILE, CompilationEntryRenderer.extractPath(errors.get(0)));
    }

    public void testEarlierLayerJarAndSharedJarsAreAllowed() {
        assertTrue(analyze(CORE_FILE, "org.core.CoreUtil").isEmpty());
        // Present in core.jar (layer 0) and both.jar (layer 1): earliest wins, so core may use it.
        assertTrue(analyze(CORE_FILE, "org.both.Shared").isEmpty());
        assertTrue(analyze(CRM_FILE, "org.core.CoreUtil", "org.crm.CrmUtil").isEmpty());
    }

    public void testStaticNestedAndWildcardImportsOfLaterJars() {
        List<String> errors = analyze(CORE_FILE,
            "static org.crm.CrmUtil.FOO", "org.crm.CrmUtil.Inner", "org.crm.*");
        assertEquals(errors.toString(), 3, errors.size());
        assertTrue(errors.get(2), errors.get(2).contains("(4:1)") && errors.get(2).contains("imports org.crm, found only"));
    }

    public void testUnknownAndJdkImportsAreIgnored() {
        assertTrue(analyze(CORE_FILE, "java.util.List", "org.unknown.Thing", "org.unknown.*").isEmpty());
    }

    public void testWithoutJarLookupNothingChanges() {
        String source = "package p;\nimport org.crm.CrmUtil;\nclass X {}\n";
        assertTrue(CrossLayerImportCheck.analyze(rules, Set.of(CORE_FILE), Map.of(CORE_FILE, source)).isEmpty());
    }

    public void testJarChangeTriggersFullScan() {
        Set<String> head = Set.of(CORE_FILE, CRM_FILE);
        assertEquals(List.of(CORE_FILE),
            CrossLayerImportCheck.selectTargets(rules, head, List.of("lib/crm.jar"), false));
        assertTrue(CrossLayerImportCheck.selectTargets(rules, head, List.of("README.md"), false).isEmpty());
    }

    public void testRunUsesJarsFromTheWorkingTree() throws Exception {
        TestFiles.write(repo, ".prepush-layers", RULES);
        TestFiles.write(repo, CORE_FILE, "package com.zoho.crm.core;\nimport org.crm.CrmUtil;\npublic class Core {}\n");
        git("init", "-q");
        git("add", "-A");
        git("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "init");

        List<String> errors = CrossLayerImportCheck.run(repo, List.of(CORE_FILE), false);
        assertEquals(errors.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("jar lib/crm.jar"));
        // The removed-jar scenario: the jar disappears and the push only touches the jar.
        assertEquals(1, CrossLayerImportCheck.run(repo, List.of("lib/crm.jar"), false).size());
    }

    private void git(String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue("git timed out", process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(output, 0, process.exitValue());
    }
}
