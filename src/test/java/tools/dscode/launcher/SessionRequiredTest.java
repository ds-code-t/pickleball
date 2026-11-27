package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.SpineSession;
import tools.dscode.control.protocol.VerbArgs;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VerbDispatch} requires {@code --session=<id>} or the default session
 * from {@code spine session new}. The check is the dispatch entry.
 */
class SessionRequiredTest {
    @TempDir
    Path project;

    @Test
    void dispatchRefusesAVerbUntilSpineSessionNew() throws Exception {
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int missing = storeStatus(null, new ByteArrayOutputStream(), errBytes);
        String err = errBytes.toString(StandardCharsets.UTF_8);
        assertEquals(2, missing, err);
        assertTrue(err.contains("error: missing session"), err);
        assertTrue(err.contains("fix: spine session new"), err);

        errBytes.reset();
        int named = storeStatus("missing-session", new ByteArrayOutputStream(), errBytes);
        String namedErr = errBytes.toString(StandardCharsets.UTF_8);
        assertEquals(2, named, namedErr);
        assertTrue(namedErr.contains("error: missing session"), namedErr);
        assertTrue(namedErr.contains("fix: spine session new"), namedErr);

        ByteArrayOutputStream createdOut = new ByteArrayOutputStream();
        ByteArrayOutputStream createdErr = new ByteArrayOutputStream();
        int created = VerbDispatch.run(
                "spine",
                VerbArgs.capture(new String[] {"spine", "session", "new", project.toAbsolutePath().toString()}),
                new PrintStream(createdOut, true, StandardCharsets.UTF_8),
                new PrintStream(createdErr, true, StandardCharsets.UTF_8));
        assertEquals(0, created, createdErr.toString(StandardCharsets.UTF_8));
        String id = SpineSession.current(project);
        assertTrue(id != null && !id.isBlank(), createdOut.toString(StandardCharsets.UTF_8));

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        errBytes.reset();
        int ready = storeStatus(null, outBytes, errBytes);
        assertEquals(0, ready, errBytes.toString(StandardCharsets.UTF_8));
        assertTrue(outBytes.toString(StandardCharsets.UTF_8).contains("\"database\""), outBytes.toString(StandardCharsets.UTF_8));

        errBytes.reset();
        int explicit = storeStatus(id, new ByteArrayOutputStream(), errBytes);
        assertEquals(0, explicit, errBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void positionalProjectRequiresThatDirectorySession() throws Exception {
        String[] argv = new String[] {"chat", "list", project.toAbsolutePath().toString()};
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int missing = VerbDispatch.run(
                "chat",
                VerbArgs.capture(argv),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
        String err = errBytes.toString(StandardCharsets.UTF_8);
        assertEquals(2, missing, err);
        assertTrue(err.contains("error: missing session"), err);
        assertFalse(err.contains(project.toString()), err);

        int created = VerbDispatch.run(
                "spine",
                VerbArgs.capture(new String[] {"spine", "session", "new", project.toAbsolutePath().toString()}),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals(0, created);

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        errBytes.reset();
        int listed = VerbDispatch.run(
                "chat",
                VerbArgs.capture(argv),
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
        assertEquals(0, listed, errBytes.toString(StandardCharsets.UTF_8));
    }

    private int storeStatus(String session, ByteArrayOutputStream outBytes, ByteArrayOutputStream errBytes) {
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put(VerbArgs.ARGS, "status");
        flags.put("json", "true");
        flags.put("project", project.toString());
        if (session != null) flags.put("session", session);
        return VerbDispatch.run(
                "store",
                flags,
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }
}
