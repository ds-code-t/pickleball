package tools.dscode.common.reporting.diagnostic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** A live suite's -Dpkb_parallel=80 must not leak into the hint's dry-run. */
class DiscoverHintJvmLeakTest {

    @Test
    void discoverHintIgnoresJvmPkbParallelButResolveRunVarsStillSeesIt(@TempDir Path project) {
        String previousParallel = System.getProperty("pkb_parallel");
        String previousRunVarsParallel = System.getProperty("pkb_runvars.pkb_parallel");
        System.setProperty("pkb_parallel", "80");
        System.setProperty("pkb_runvars.pkb_parallel", "80");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertEquals(0, DiagnosticCli.run(new String[]{"discover-hint", project.toString()},
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
            String text = out.toString(StandardCharsets.UTF_8);
            assertFalse(text.contains("pkb_parallel=80"), text);

            PKB_props.ResolvedRunVars projectOnly = PickleballRunner.previewLaunch(project, false);
            assertFalse("80".equals(projectOnly.runVars().get("pkb_parallel")), projectOnly.runVars().toString());
            PKB_props.ResolvedRunVars ambient = PickleballRunner.previewLaunch(project);
            assertEquals("80", ambient.runVars().get("pkb_parallel"), ambient.runVars().toString());
        } finally {
            if (previousParallel == null) System.clearProperty("pkb_parallel");
            else System.setProperty("pkb_parallel", previousParallel);
            if (previousRunVarsParallel == null) System.clearProperty("pkb_runvars.pkb_parallel");
            else System.setProperty("pkb_runvars.pkb_parallel", previousRunVarsParallel);
        }
    }
}
