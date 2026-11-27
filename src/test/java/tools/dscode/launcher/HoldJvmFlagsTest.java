package tools.dscode.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code spine start} copies warm flags into the hold JVM argument file. */
class HoldJvmFlagsTest {
    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        System.clearProperty("pkb_warm");
        System.clearProperty("pkb_warm_packages");
        System.clearProperty("pkb_heap_subprocess_percent");
        System.clearProperty("pkb_heap_refuse_percent");
        System.clearProperty("pkb_worker_pool");
        System.clearProperty("pkb_cdp_endpoint");
        SpineCli.resetForTests();
        SpineCli.execute(new String[] {"spine", "stop", "--project=" + dir}, sink(), sink());
    }

    @Test
    void startWritesWarmFlagsAndACleanEndpoint() throws Exception {
        System.setProperty("pkb_warm", "on");
        System.setProperty("pkb_warm_packages", "glue.sample");
        System.setProperty("pkb_heap_subprocess_percent", "70");
        System.setProperty("pkb_heap_refuse_percent", "85");
        System.setProperty("pkb_worker_pool", "2");
        System.setProperty("pkb_cdp_endpoint", "http://127.0.0.1:9222");
        SpineCli.inlineHoldForTests = true;
        assertEquals(0, start());
        String args = Files.readString(dir.resolve(".pickleball/spine/java.args"), StandardCharsets.UTF_8);
        assertTrue(args.contains("-Dpkb_warm=on"), args);
        assertTrue(args.contains("-Dpkb_warm_packages=glue.sample"), args);
        assertTrue(args.contains("-Dpkb_heap_subprocess_percent=70"), args);
        assertTrue(args.contains("-Dpkb_heap_refuse_percent=85"), args);
        assertTrue(args.contains("-Dpkb_worker_pool=2"), args);
        assertTrue(args.contains("-Dpkb_cdp_endpoint=http://127.0.0.1:9222"), args);
        assertTrue(args.contains("-cp"), args);
        assertTrue(args.contains("tools.dscode.launcher.SpineCli"), args);
        assertTrue(args.contains("hold"), args);
    }

    @Test
    void credentialedEndpointStaysOutOfTheArgumentFile() throws Exception {
        System.setProperty("pkb_warm", "on");
        System.setProperty("pkb_cdp_endpoint", "http://user:s3cret@127.0.0.1:9222");
        SpineCli.inlineHoldForTests = true;
        assertEquals(0, start());
        String args = Files.readString(dir.resolve(".pickleball/spine/java.args"), StandardCharsets.UTF_8);
        assertFalse(args.contains("s3cret"), args);
        assertFalse(args.contains("user:s3cret"), args);
        assertTrue(args.contains("-Dpkb_warm=on"), args);
        assertEquals("http://user:s3cret@127.0.0.1:9222", SpineCli.holdEnvironment().get("PKB_CDP_ENDPOINT"));
    }

    private int start() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SpineCli.execute(
                new String[] {"spine", "start", "--project=" + dir},
                sink(),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        if (code != 0) throw new AssertionError(err.toString(StandardCharsets.UTF_8));
        return code;
    }

    private static PrintStream sink() {
        return new PrintStream(new ByteArrayOutputStream());
    }
}
