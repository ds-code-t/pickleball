package tools.dscode.dolt.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.dolt.DoltCli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltServerLifecycleTest {
    @TempDir
    Path project;

    @BeforeEach
    void preferCachedBinary() {
        Path cached = Path.of(System.getProperty("user.home"), ".pickleball", "tools", "dolt", "2.7.3",
                "linux-amd64", "dolt");
        if (Files.isRegularFile(cached)) System.setProperty("pkb_dolt_bin", cached.toString());
    }

    @AfterEach
    void stopAndClear() throws Exception {
        DoltServer.stop(project);
        System.clearProperty("pkb_dolt_bin");
        System.clearProperty("pkb_dolt_log");
        System.clearProperty("pkb_dolt_server_idle_minutes");
        System.clearProperty("pkb_dolt_test_fail_binds");
        System.clearProperty("pkb_dolt_port");
    }

    @Test
    void startReuseStopAndStatusJson() throws Exception {
        DoltServer.ServerInfo started = DoltServer.ensure(project);
        assertTrue(started.running());
        assertTrue(started.port() > 0);
        assertTrue(DoltServer.selectOne(started.port(), 2000));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = DoltCli.execute(new String[]{"dolt", "server", "status", "--json", "--project=" + project},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(0, code);
        String json = out.toString(StandardCharsets.UTF_8).trim();
        assertTrue(json.startsWith("{") && json.endsWith("}"));
        assertTrue(json.contains("\"running\":true"));
        assertTrue(json.contains("\"port\":" + started.port()));

        String java = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "tools.dscode.dolt.DoltCli", "dolt", "server", "start", "--json", "--project=" + project);
        builder.redirectErrorStream(true);
        Process second = builder.start();
        byte[] bytes = second.getInputStream().readAllBytes();
        assertEquals(0, second.waitFor(), new String(bytes, StandardCharsets.UTF_8));
        String secondJson = new String(bytes, StandardCharsets.UTF_8).trim();
        assertTrue(secondJson.contains("\"port\":" + started.port()), secondJson);
        assertTrue(secondJson.contains("\"reused\":true"), secondJson);

        DoltServer.stop(project);
        DoltServer.ServerInfo stopped = DoltServer.status(project);
        assertFalse(stopped.running());
        ByteArrayOutputStream stoppedOut = new ByteArrayOutputStream();
        DoltCli.execute(new String[]{"dolt", "server", "status", "--json", "--project=" + project},
                new PrintStream(stoppedOut, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        assertEquals("{\"running\":false}", stoppedOut.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void staleServerJsonIsReplaced() throws Exception {
        Path home = DoltServer.home(project);
        Files.createDirectories(home);
        Files.writeString(home.resolve("server.json"),
                "{\"pid\":2147483000,\"supervisorPid\":2147483001,\"port\":1,\"doltVersion\":\"2.7.3\","
                        + "\"startedAt\":\"2020-01-01T00:00:00Z\",\"project\":\"" + project + "\"}\n");
        DoltServer.ServerInfo started = DoltServer.ensure(project);
        assertTrue(started.port() > 1);
        assertTrue(DoltServer.selectOne(started.port(), 2000));
    }

    @Test
    void portRetryThenServes() throws Exception {
        System.setProperty("pkb_dolt_test_fail_binds", "1");
        System.setProperty("pkb_dolt_log", "server");
        DoltServer.ServerInfo started = DoltServer.ensure(project);
        assertTrue(DoltServer.selectOne(started.port(), 2000));
        String log = Files.readString(project.resolve(".pickleball/dolt/dolt.log"));
        assertTrue(log.contains("retry") || log.contains("test hold"), log);
        assertTrue(log.contains("sandbox=main"));
    }

    @Test
    void idleStopWhenNoLeaseIsFresh() throws Exception {
        System.setProperty("pkb_dolt_server_idle_minutes", "0");
        DoltServer.ServerInfo started = DoltServer.ensure(project);
        long deadline = System.nanoTime() + 15_000_000_000L;
        boolean stopped = false;
        while (System.nanoTime() < deadline) {
            if (!DoltServer.tcpOpen(started.port())) {
                stopped = true;
                break;
            }
            Thread.sleep(200);
        }
        assertTrue(stopped, "server stayed up on port " + started.port());
        assertFalse(DoltServer.status(project).running());
    }
}
