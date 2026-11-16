package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.sql.SqlConnections;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A sandbox file stands alone. Deleting main and every other sandbox does not break it. */
class SandboxIndependenceTest {
    @TempDir
    Path project;

    @AfterEach
    void release() {
        StoreScope.clear();
        SqlConnections.closeProject(project);
    }

    @Test
    void deletingMainLeavesTheSandboxReadable() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), """
                Feature: Pay
                  Scenario: Ada
                    * INFO: amount is 1
                """);
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        StoreScope.clear();
        assertEquals(0, cli("store", "sandbox", "create", "alpha", "--project=" + project).code);
        StoreScope.clear();
        assertEquals(0, cli("store", "sandbox", "create", "beta", "--project=" + project).code);
        StoreScope.clear();
        Capture edited = cli("store", "feature", "put", "features/pay.feature",
                "--text=Feature: Pay\n  Scenario: Ada\n    * INFO: amount is 8\n",
                "--sandbox=alpha", "--project=" + project);
        assertEquals(0, edited.code, edited.err);
        StoreScope.clear();
        Capture mainEdit = cli("store", "feature", "put", "features/pay.feature",
                "--text=Feature: Pay\n  Scenario: Ada\n    * INFO: amount is 3\n",
                "--project=" + project);
        assertEquals(0, mainEdit.code, mainEdit.err);
        StoreScope.clear();

        Capture alpha = cli("store", "features", "show", "features/pay.feature", "--sandbox=alpha", "--project=" + project);
        assertEquals(0, alpha.code, alpha.err);
        assertTrue(alpha.out.contains("amount is 8"), alpha.out);
        assertFalse(alpha.out.contains("amount is 3"), alpha.out);
        StoreScope.clear();
        Capture main = cli("store", "features", "show", "features/pay.feature", "--project=" + project);
        assertEquals(0, main.code, main.err);
        assertTrue(main.out.contains("amount is 3"), main.out);
        assertFalse(main.out.contains("amount is 8"), main.out);

        StoreScope.clear();
        SqlConnections.closeProject(project);
        Path sandbox = StorePaths.sandboxDatabase(project, "alpha");
        String bytes = new String(Files.readAllBytes(sandbox), StandardCharsets.ISO_8859_1);
        assertFalse(bytes.contains("main.db"), "sandbox file names main.db");
        assertFalse(bytes.contains("runstate.db"), "sandbox file names runstate.db");
        assertFalse(bytes.contains("sandboxes/beta"), bytes);

        for (String suffix : new String[] {"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(StorePaths.mainDatabase(project).toString() + suffix));
        }
        deleteTree(StorePaths.sandboxHome(project, "beta"));

        Capture diff = cli("store", "sandbox", "diff", "alpha", "--project=" + project);
        assertEquals(0, diff.code, diff.err);
        assertTrue(diff.out.contains("features/pay.feature"), diff.out);
        Path outside = project.resolve("sibling");
        Capture extracted = cli("store", "sandbox", "extract", "alpha", "--to=" + outside, "--project=" + project);
        assertEquals(0, extracted.code, extracted.err);
        assertTrue(Files.readString(outside.resolve("features/pay.feature")).contains("amount is 8"));
        Capture still = cli("store", "features", "show", "features/pay.feature", "--sandbox=alpha", "--project=" + project);
        assertEquals(0, still.code, still.err);
        assertTrue(still.out.contains("amount is 8"), still.out);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private Capture cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = StoreCli.execute(args, new PrintStream(out), new PrintStream(err));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
