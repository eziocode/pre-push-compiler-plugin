package com.github.prepushchecker;

import junit.framework.TestCase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class CrossLayerImportCheckTest extends TestCase {
    private static final String RULES = String.join("\n",
        "root source",
        "layer crmcore",
        "  include source/com/zoho/crm/core/**",
        "  include source/com/adventnet/crm/common/util/**",
        "layer crm",
        "  include source/**",
        "");

    private static final String CONSTANTS = "source/com/adventnet/crm/common/util/CrmConstants.java";
    private static final String TERRITORY = "source/com/adventnet/crm/authorization/territory/util/CrmTerritoryConstants.java";
    private static final String CORE = "source/com/zoho/crm/core/common/CoreConstants.java";
    private static final String API = "source/com/adventnet/crm/api/v2/commons/CrmAssignAndRemoveTerritories.java";

    private static final Set<String> HEAD_FILES = Set.of(CONSTANTS, TERRITORY, CORE, API);

    private static LayerRules rules() {
        return LayerRulesParser.parse(RULES).rules();
    }

    /** The ZOHOCRM master break of Oct 22 2026 (commit 82611f51). */
    public void testFlagsCoreFileImportingLaterLayerSource() {
        String source = String.join("\n",
            "//$Id$",
            "package com.adventnet.crm.common.util;",
            "import java.io.File;",
            "import com.zoho.crm.core.common.CoreConstants;",
            "import com.adventnet.crm.authorization.territory.util.CrmTerritoryConstants;",
            "public class CrmConstants {",
            "    String s = CrmTerritoryConstants.BLOCK_TERRITORY_CHILD_ASSIGNMENT;",
            "}");
        List<String> errors = CrossLayerImportCheck.analyze(rules(), HEAD_FILES, Map.of(CONSTANTS, source));
        assertEquals(errors.toString(), 1, errors.size());
        String error = errors.get(0);
        assertTrue(error, error.startsWith("[" + CONSTANTS + " (5:1)] Layer violation: 'crmcore'"));
        assertTrue(error, error.contains("CrmTerritoryConstants from later layer 'crm'"));
        assertEquals(CONSTANTS, CompilationEntryRenderer.extractPath(error));
        assertEquals("5:1", CompilationEntryRenderer.extractPosition(error));
    }

    public void testLaterLayerMayImportEarlierLayer() {
        String source = "package com.adventnet.crm.api.v2.commons;\n"
            + "import com.zoho.crm.core.common.CoreConstants;\n"
            + "import com.adventnet.crm.common.util.CrmConstants;\n"
            + "class CrmAssignAndRemoveTerritories {}\n";
        assertTrue(CrossLayerImportCheck.analyze(rules(), HEAD_FILES, Map.of(API, source)).isEmpty());
    }

    public void testJarPackagesAreIgnored() {
        String source = "package com.adventnet.crm.common.util;\n"
            + "import com.zoho.crm.core.authorization.territory.util.TerritoryConstants;\n"
            + "import org.json.JSONObject;\n"
            + "class CrmConstants {}\n";
        assertTrue(CrossLayerImportCheck.analyze(rules(), HEAD_FILES, Map.of(CONSTANTS, source)).isEmpty());
    }

    public void testStaticNestedAndWildcardImports() {
        String source = String.join("\n",
            "package com.adventnet.crm.common.util;",
            "import static com.adventnet.crm.authorization.territory.util.CrmTerritoryConstants.MANUAL_ADD;",
            "import com.adventnet.crm.authorization.territory.util.CrmTerritoryConstants.TerritoryAssignmentOperations;",
            "import com.adventnet.crm.authorization.territory.util.*;",
            "import static com.adventnet.crm.authorization.territory.util.CrmTerritoryConstants.*;",
            "class CrmConstants {}");
        List<String> errors = CrossLayerImportCheck.analyze(rules(), HEAD_FILES, Map.of(CONSTANTS, source));
        assertEquals(errors.toString(), 4, errors.size());
        assertTrue(errors.get(0).contains("(2:1)"));
        assertTrue(errors.get(3).contains("(5:1)"));
    }

    public void testIgnoresCommentsAndStopsAtTypeDeclaration() {
        String source = String.join("\n",
            "package com.adventnet.crm.common.util;",
            "// import com.adventnet.crm.authorization.territory.util.CrmTerritoryConstants;",
            "/*",
            "import com.adventnet.crm.authorization.territory.util.CrmTerritoryConstants;",
            "*/",
            "/** doc */ public final class CrmConstants {",
            "import com.adventnet.crm.authorization.territory.util.CrmTerritoryConstants;",
            "}");
        assertTrue(CrossLayerImportCheck.analyze(rules(), HEAD_FILES, Map.of(CONSTANTS, source)).isEmpty());
    }

    public void testSelectTargetsSkipsLastLayerAndEscalatesOnRulesOrDeletedFiles() {
        LayerRules rules = rules();
        assertEquals(List.of(CONSTANTS),
            CrossLayerImportCheck.selectTargets(rules, HEAD_FILES, List.of(CONSTANTS, API), false));
        assertEquals(Set.of(CONSTANTS, CORE), Set.copyOf(
            CrossLayerImportCheck.selectTargets(rules, HEAD_FILES, List.of(".prepush-layers"), false)));
        assertEquals(Set.of(CONSTANTS, CORE), Set.copyOf(CrossLayerImportCheck.selectTargets(
            rules, HEAD_FILES, List.of("source/com/zoho/crm/core/Moved.java"), false)));
    }

    public void testRunReadsPushedSnapshotFromGit() throws Exception {
        Path repo = Files.createTempDirectory("layer-check");
        try {
            git(repo, "init", "-q");
            write(repo, ".prepush-layers", RULES);
            write(repo, TERRITORY, "package com.adventnet.crm.authorization.territory.util;\n"
                + "public class CrmTerritoryConstants {}\n");
            write(repo, CONSTANTS, "package com.adventnet.crm.common.util;\n"
                + "import com.adventnet.crm.authorization.territory.util.CrmTerritoryConstants;\n"
                + "public class CrmConstants {}\n");
            git(repo, "add", "-A");
            git(repo, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "init");
            // Unpushed working-tree fix must not hide the violation in HEAD.
            write(repo, CONSTANTS, "package com.adventnet.crm.common.util;\npublic class CrmConstants {}\n");

            List<String> errors = CrossLayerImportCheck.run(repo, List.of(CONSTANTS), false);
            assertEquals(errors.toString(), 1, errors.size());
            assertTrue(errors.get(0), errors.get(0).startsWith("[" + CONSTANTS + " (2:1)]"));

            assertEquals(1, CrossLayerImportCheck.run(repo, List.of(), true).size());
        } finally {
            deleteRecursively(repo);
        }
    }

    public void testRulesErrorsBlockWithLocation() throws Exception {
        Path repo = Files.createTempDirectory("layer-check");
        try {
            write(repo, ".prepush-layers", "root source\nlayer a\n include **\n");
            List<String> errors = CrossLayerImportCheck.run(repo, List.of(CONSTANTS), false);
            assertEquals(1, errors.size());
            assertTrue(errors.get(0), errors.get(0).startsWith("[.prepush-layers (1:1)] Layer rules error:"));
        } finally {
            deleteRecursively(repo);
        }
    }

    public void testNoRulesFileMeansNoCheck() throws Exception {
        Path repo = Files.createTempDirectory("layer-check");
        try {
            assertTrue(CrossLayerImportCheck.run(repo, List.of(CONSTANTS), true).isEmpty());
        } finally {
            deleteRecursively(repo);
        }
    }

    private static void write(Path repo, String relative, String content) throws IOException {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void git(Path repo, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(repo.toFile())
            .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue("git timed out", process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(output, 0, process.exitValue());
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
