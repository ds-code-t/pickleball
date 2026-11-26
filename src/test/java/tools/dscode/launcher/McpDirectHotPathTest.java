package tools.dscode.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.bridge.ControlBridgeBootstrap;
import tools.dscode.control.protocol.ControlBridgeDescriptor;
import tools.dscode.control.protocol.LiveBridgeFile;
import tools.dscode.control.protocol.McpTransport;
import tools.dscode.workbench.mcp.McpInProcess;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpDirectHotPathTest {
    @TempDir
    Path project;

    @AfterEach
    void stopBridge() {
        ControlBridgeBootstrap.stop();
        LiveBridgeFile.delete(project);
        System.clearProperty(McpTransport.PROPERTY);
    }

    @Test
    void storeStatusMatchesVerbDispatchOnTheDirectTransport() throws Exception {
        System.clearProperty(McpTransport.PROPERTY);
        assertTrue(McpTransport.direct());
        Path session = project.resolve("bridge-session");
        String token = "r4-direct-token";
        ControlBridgeDescriptor descriptor = ControlBridgeBootstrap.start(session, "r4-direct", token, false);
        LiveBridgeFile.write(project, new LiveBridgeFile.Bridge(
                descriptor.host(), descriptor.port(), token, descriptor.pid(), descriptor.sessionId()
        ));
        Map<String, Object> first = McpInProcess.call(project, "store", Map.of("args", List.of("status")));
        Map<String, Object> second = McpInProcess.call(project, "store", Map.of("args", List.of("status")));
        assertEquals(McpTransport.DIRECT, first.get("transport"));
        assertEquals(0, ((Number) first.get("exit")).intValue(), String.valueOf(first.get("err")));
        assertEquals(first.get("out"), second.get("out"));
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("args", "status");
        flags.put("project", project.toAbsolutePath().normalize().toString());
        int code = VerbDispatch.run(
                "store",
                flags,
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code, errBytes.toString(StandardCharsets.UTF_8));
        assertEquals(outBytes.toString(StandardCharsets.UTF_8), first.get("out"));
    }
}
