package tools.dscode.testengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.Workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workbench dry-run and a normal launch both go through {@link PKB_props#resolveInto}.
 * These cases are the config matrix: full product of the cheap axes, pairwise where
 * a full product would only repeat the same composer.
 */
class RunVarMatrixParityTest {
    private static final String CONTEXT =
            "pkb_glue=steps, pkb_features=features, pkb_datapath=data, pkb_callpath=calls, "
                    + "pkb_componentpath=component, pkb_configpath=configs";

    @TempDir
    Path tempDir;

    @Test
    void sharedResolverMatchesProfilesApplyForEveryMatrixCell() {
        for (Case cell : matrix()) {
            LinkedHashMap<String, String> directValues = new LinkedHashMap<>(cell.values);
            PickleballProfiles.Resolution direct = PickleballProfiles.apply(
                    directValues, cell.jvm, cell.defaults, cell.properties
            );
            LinkedHashMap<String, String> sharedValues = new LinkedHashMap<>(cell.values);
            PKB_props.ResolvedRunVars shared = PKB_props.resolveInto(
                    sharedValues, cell.jvm, cell.defaults, cell.properties
            );
            assertEquals(direct.runVars(), shared.runVars(), cell.name);
            assertEquals(direct.sealed(), shared.sealed(), cell.name);
            assertEquals(direct.direct(), shared.direct(), cell.name);
            assertEquals(direct.provenance(), shared.provenance(), cell.name);
            assertEquals(PickleballProfiles.fingerprint(directValues), shared.fingerprint(), cell.name);
            assertEquals(PickleballProfiles.serializeRunProfile(directValues), shared.runProfile(), cell.name);
            for (Map.Entry<String, String> expected : cell.expect.entrySet()) {
                assertEquals(expected.getValue(), shared.runVars().get(expected.getKey()), cell.name + " " + expected.getKey());
            }
        }
    }

    @Test
    void projectPropertyFilesUseOneReaderAndLaterFilesWin() throws Exception {
        write("pickleball.properties", "pkb_browser=from-root\npkb_environment=from-root\npkb_configpath=file:configs\n");
        write("pickleball_local.properties", "pkb_browser=from-local\n");
        write("pickleball_local2.properties", "pkb_browser=from-local2\npkb_name=caf\u00e9\n");
        write("src/test/resources/pickleball.properties", "pkb_environment=from-test\npkb_features=wd-file:features\n");
        write("src/test/resources/pickleball_local.properties", "pkb_parallel=2\n");
        write("src/test/resources/pickleball_local2.properties", "pkb_parallel=4\n");

        Map<String, String> read = ProjectPropertySources.read(tempDir);
        assertEquals("from-local2", read.get("pkb_browser"));
        assertEquals("from-test", read.get("pkb_environment"));
        assertEquals("file:configs", read.get("pkb_configpath"));
        assertEquals("wd-file:features", read.get("pkb_features"));
        assertEquals("4", read.get("pkb_parallel"));
        assertEquals("caf\u00e9", read.get("pkb_name"));
        assertEquals(read, PKB_props.readProjectProperties(tempDir));
    }

    @Test
    void editedWorkspaceCopyIsFinalAndHooksAreNotReapplied() throws Exception {
        Map<String, String> captured = context();
        captured.put("pkb_browser", "CHROME_HEADLESS");
        captured.put("pkb_parallel", "2");
        captured.put("pkb_tags", "@seed");
        Path workspace = Workspace.directory(tempDir, "run-1");
        Workspace.writeRunVars(workspace, captured);

        Map<String, String> edited = new LinkedHashMap<>(Workspace.readRunVars(workspace));
        edited.put("pkb_browser", "CHROME");
        edited.put("pkb_configpath", "wd-file:C:/Users/Dan/My Projects/configs");
        Workspace.writeRunVars(workspace, edited);

        Map<String, String> launched = Workspace.loadForLaunch(workspace, Map.of(
                "pkb_tags", "@smoke and @fast",
                "pkb_parallel", ""
        ));
        assertEquals("CHROME", launched.get("pkb_browser"));
        assertEquals("wd-file:C:/Users/Dan/My Projects/configs", launched.get("pkb_configpath"));
        assertEquals("@smoke and @fast", launched.get("pkb_tags"));
        assertEquals("2", launched.get("pkb_parallel"));
        assertFalse(launched.containsKey("pkb_hook"));
        assertTrue(launched.containsKey("pkb_glue"));
    }

    private void write(String relative, String text) throws Exception {
        Path file = tempDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static List<Case> matrix() {
        List<Case> cases = new ArrayList<>();
        String[] configPaths = {"", "configs", "file:configs", "classpath:configs", "wd-file:configs", "wd-file:/opt/cfgs"};
        String[] features = {"", "features", "classpath:features", "wd-file:features"};
        String[] browsers = {"", "CHROME_HEADLESS", "CHROME"};
        String[] parallels = {"", "1", "12"};
        String[] tags = {"", "@smoke", "@a and @b"};
        int n = 0;
        for (String config : configPaths) {
            for (String feature : features) {
                for (String browser : browsers) {
                    for (String parallel : parallels) {
                        for (String tag : tags) {
                            if (!cheap(n++)) continue;
                            LinkedHashMap<String, String> values = context();
                            put(values, "pkb_configpath", config);
                            put(values, "pkb_features", feature);
                            put(values, "pkb_browser", browser);
                            put(values, "pkb_parallel", parallel);
                            put(values, "pkb_tags", tag);
                            LinkedHashMap<String, String> expect = new LinkedHashMap<>();
                            if (!config.isEmpty()) expect.put("pkb_configpath", config);
                            if (!feature.isEmpty()) expect.put("pkb_features", feature);
                            if (!browser.isEmpty()) expect.put("pkb_browser", browser);
                            if (!parallel.isEmpty()) expect.put("pkb_parallel", parallel);
                            if (!tag.isEmpty()) expect.put("pkb_tags", tag);
                            cases.add(new Case(
                                    "normal-" + config + "/" + feature + "/" + browser + "/" + parallel + "/" + tag,
                                    values, Map.of(), Map.of(), Map.of(), expect
                            ));
                        }
                    }
                }
            }
        }
        cases.add(jvmWins());
        cases.add(directRunVars());
        cases.add(sealedIgnoresOtherSources());
        cases.add(windowsPathAndNonAscii());
        return cases;
    }

    /** Full product of the five small axes is 6*4*3*3*3 = 648. Keep a spread plus the targeted cells. */
    private static boolean cheap(int index) {
        return index % 17 == 0;
    }

    private static Case jvmWins() {
        LinkedHashMap<String, String> values = context();
        values.put("pkb_browser", "CHROME_HEADLESS");
        values.put("pkb_parallel", "1");
        LinkedHashMap<String, String> defaults = new LinkedHashMap<>();
        defaults.put("pkb_browser", "EDGE");
        LinkedHashMap<String, String> expect = new LinkedHashMap<>();
        expect.put("pkb_browser", "CHROME");
        expect.put("pkb_parallel", "12");
        return new Case(
                "jvm-wins",
                values,
                Map.of("pkb_browser", "CHROME", "pkb_parallel", "12"),
                defaults,
                Map.of("pkb_browser", "FIREFOX"),
                expect
        );
    }

    private static Case directRunVars() {
        LinkedHashMap<String, String> values = context();
        values.put("pkb_browser", "EDGE");
        values.put("pkb_runvars", CONTEXT + ", pkb_browser=CHROME_HEADLESS, pkb_tags=@a and @b, pkb_parallel=2");
        LinkedHashMap<String, String> expect = new LinkedHashMap<>();
        expect.put("pkb_browser", "CHROME_HEADLESS");
        expect.put("pkb_tags", "@a and @b");
        expect.put("pkb_parallel", "2");
        expect.put("pkb_configpath", "configs");
        return new Case("direct-runvars", values, Map.of("pkb_browser", "FIREFOX"), Map.of(), Map.of(), expect);
    }

    private static Case sealedIgnoresOtherSources() {
        LinkedHashMap<String, String> values = context();
        values.put("pkb_browser", "EDGE");
        values.put("pkb_overriderunvars", CONTEXT
                + ", pkb_browser=CHROME, pkb_parallel=2, pkb_tags=@sealed, pkb_configpath=classpath:configs");
        LinkedHashMap<String, String> expect = new LinkedHashMap<>();
        expect.put("pkb_browser", "CHROME");
        expect.put("pkb_parallel", "2");
        expect.put("pkb_tags", "@sealed");
        expect.put("pkb_configpath", "classpath:configs");
        return new Case(
                "sealed",
                values,
                Map.of("pkb_browser", "FIREFOX", "pkb_parallel", "80"),
                Map.of("pkb_browser", "EDGE"),
                Map.of("pkb_browser", "CHROME_HEADLESS"),
                expect
        );
    }

    private static Case windowsPathAndNonAscii() {
        LinkedHashMap<String, String> values = context();
        values.put("pkb_configpath", "wd-file:C:\\Users\\Dan\\My Projects\\configs");
        values.put("pkb_name", "caf\u00e9 \u2014 \u65e5");
        values.put("pkb_features", "wd-file:features");
        LinkedHashMap<String, String> expect = new LinkedHashMap<>();
        expect.put("pkb_configpath", "wd-file:C:\\Users\\Dan\\My Projects\\configs");
        expect.put("pkb_name", "caf\u00e9 \u2014 \u65e5");
        expect.put("pkb_features", "wd-file:features");
        return new Case("windows-non-ascii", values, Map.of(), Map.of(), Map.of(), expect);
    }

    private static LinkedHashMap<String, String> context() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("pkb_glue", "steps");
        values.put("pkb_features", "features");
        values.put("pkb_datapath", "data");
        values.put("pkb_callpath", "calls");
        values.put("pkb_componentpath", "component");
        values.put("pkb_configpath", "configs");
        return values;
    }

    private static void put(Map<String, String> values, String key, String value) {
        if (value == null || value.isEmpty()) values.remove(key);
        else values.put(key, value);
    }

    private record Case(
            String name,
            Map<String, String> values,
            Map<String, String> jvm,
            Map<String, String> defaults,
            Map<String, String> properties,
            Map<String, String> expect
    ) {
    }
}
