package com.github.prepushchecker;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class CrossLayerScanCheckTest extends TestCase {
    private static final String RULES = String.join("\n",
        "root source",
        "scan dist/TP.zip",
        "scan lib/**",
        "layer crmcore",
        "  include source/com/zoho/crm/core/**",
        "  lib lib/core.jar",
        "  forbid **/commons-collections-3*.jar",
        "layer crm",
        "  include source/**",
        "  lib lib/crm.jar",
        "");

    private static final String CORE_FILE = "source/com/zoho/crm/core/Core.java";
    private static final String CRM_FILE = "source/com/adventnet/crm/Crm.java";
    private static final String OLD_JAR = "dist/TP.zip!/lib/commons-collections-3.2.jar";

    private Path repo;
    private LayerRules rules;

    @Override
    protected void setUp() throws Exception {
        repo = Files.createTempDirectory("layer-scan-check");
        rules = LayerRulesParser.parse(RULES).rules();
        TestFiles.jar(repo, "lib/core.jar", "org/core/CoreUtil.class");
        TestFiles.jar(repo, "lib/crm.jar", "org/crm/CrmUtil.class");
        TestFiles.jar(repo, "lib/x.jar", "org/x/X.class");
        TestFiles.jar(repo, "lib/dup-new.jar", "org/apache/commons/collections/Dup.class");
        TestFiles.zip(repo, "dist/TP.zip", Map.of("lib/commons-collections-3.2.jar", TestFiles.jarBytes(
            "org/apache/commons/collections/CollectionUtils.class", "org/apache/commons/collections/Dup.class")));
    }

    @Override
    protected void tearDown() throws Exception {
        TestFiles.delete(repo);
    }

    private List<String> analyzeScanned(LayerRules layerRules, String file, String... imports) {
        StringBuilder source = new StringBuilder("package p;\n");
        for (String imp : imports) source.append("import ").append(imp).append(";\n");
        source.append("class X {}\n");
        List<String> scans = layerRules.scans().stream().map(AntPathPattern::source).toList();
        try (JarClassIndex libs = new JarClassIndex(LayerJars.resolve(repo, layerRules));
             ScanJarIndex scanned = new ScanJarIndex(ScanJars.resolve(repo, scans))) {
            return CrossLayerImportCheck.analyze(layerRules, Set.of(file), Map.of(file, source.toString()), libs, scanned);
        }
    }

    private List<String> analyzeScanned(String file, String... imports) {
        return analyzeScanned(rules, file, imports);
    }

    public void testImportOnlyFromUncheckedJarIsForbidden() {
        List<String> errors = analyzeScanned(CORE_FILE, "org.apache.commons.collections.CollectionUtils");
        assertEquals(errors.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).startsWith("[" + CORE_FILE + " (2:1)] Forbidden jar: 'crmcore' file"
            + " imports org.apache.commons.collections.CollectionUtils, available only from jars layer 'crmcore'"
            + " unchecked: " + OLD_JAR + ". Check one in Layers or fix the import"));
        assertEquals(CORE_FILE, CompilationEntryRenderer.extractPath(errors.get(0)));
    }

    public void testForbidIsPerLayer() {
        assertTrue(analyzeScanned(CRM_FILE, "org.apache.commons.collections.CollectionUtils").isEmpty());
    }

    public void testAllowedDuplicateWins() {
        assertTrue(analyzeScanned(CORE_FILE, "org.apache.commons.collections.Dup").isEmpty());
        assertTrue(analyzeScanned(CORE_FILE, "org.apache.commons.collections.*").isEmpty());
    }

    public void testScannedJarsAreAllowedByDefault() {
        assertTrue(analyzeScanned(CORE_FILE, "org.x.X").isEmpty());
    }

    public void testForbiddenLibJarIsFlaggedEvenWhenOnClasspath() {
        LayerRules forbidCore = LayerRulesParser.parse(RULES.replace(
            "  forbid **/commons-collections-3*.jar", "  forbid lib/core.jar")).rules();
        List<String> errors = analyzeScanned(forbidCore, CORE_FILE, "org.core.CoreUtil");
        assertEquals(errors.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("unchecked: lib/core.jar."));
    }

    public void testForbidWorksWithoutLibs() {
        LayerRules noLibs = LayerRulesParser.parse(RULES.replace("  lib lib/core.jar\n", "")
            .replace("  lib lib/crm.jar\n", "")).rules();
        assertFalse(noLibs.hasLibs());
        assertEquals(1, analyzeScanned(noLibs, CORE_FILE, "org.apache.commons.collections.CollectionUtils").size());
    }

    public void testLaterLayerLibMessageUnchanged() {
        List<String> errors = analyzeScanned(CORE_FILE, "org.crm.CrmUtil");
        assertEquals(errors.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("found only in jar lib/crm.jar of later layer 'crm'"));
    }

    public void testScannedCopyDoesNotHideLaterLayerJar() throws Exception {
        TestFiles.jar(repo, "other/crm-copy.jar", "org/crm/CrmUtil.class");
        LayerRules withCopy = LayerRulesParser.parse(RULES.replace("scan lib/**", "scan lib/**\nscan other/*.jar")).rules();
        List<String> errors = analyzeScanned(withCopy, CORE_FILE, "org.crm.CrmUtil");
        assertEquals(errors.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("found only in jar lib/crm.jar of later layer 'crm'"));
    }

    public void testZipChangeForcesFullScan() {
        Set<String> head = Set.of(CORE_FILE, CRM_FILE);
        assertEquals(List.of(CORE_FILE),
            CrossLayerImportCheck.selectTargets(rules, head, List.of("dist/TP.zip"), false));
    }

    public void testLastLayerForbidIsChecked() {
        LayerRules lastForbids = LayerRulesParser.parse(RULES.replace(
            "  lib lib/crm.jar\n", "  lib lib/crm.jar\n  forbid lib/x.jar\n")).rules();
        Set<String> head = Set.of(CORE_FILE, CRM_FILE);
        assertEquals(Set.of(CORE_FILE, CRM_FILE),
            Set.copyOf(CrossLayerImportCheck.selectTargets(lastForbids, head, List.of(CRM_FILE, CORE_FILE), false)));
        assertEquals(1, analyzeScanned(lastForbids, CRM_FILE, "org.x.X").size());
    }

    public void testRunHonorsForbidFromRulesFile() throws Exception {
        TestFiles.write(repo, ".prepush-layers", RULES);
        TestFiles.write(repo, CORE_FILE, "package com.zoho.crm.core;\n"
            + "import org.apache.commons.collections.CollectionUtils;\npublic class Core {}\n");
        git("init", "-q");
        git("add", "-A");
        git("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "init");

        List<String> errors = CrossLayerImportCheck.run(repo, List.of(CORE_FILE), false);
        assertEquals(errors.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("Forbidden jar: 'crmcore'"));
    }

    private void git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue("git timed out", process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(output, 0, process.exitValue());
    }
}
