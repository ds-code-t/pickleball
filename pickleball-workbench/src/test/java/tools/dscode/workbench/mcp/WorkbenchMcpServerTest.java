package tools.dscode.workbench.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.WorkbenchServices;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchMcpServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path project;

    @Test
    void packagedServerCompletesInitializeHandshake() throws Exception {
        try (ProcessHarness harness = new ProcessHarness(project)) {
            harness.initialize();
        }
    }

    @Test
    void packagedServerInitializesListsToolsInvokesControllerAndKeepsStdoutProtocolOnly()
            throws Exception {
        try (ProcessHarness harness = new ProcessHarness(project)) {
            harness.initialize();

            JsonNode listed = harness.request(2, "tools/list", "{}");
            Set<String> toolNames = new HashSet<>();
            listed.at("/result/tools").forEach(tool -> toolNames.add(tool.path("name").asText()));
            assertTrue(toolNames.contains("workbench_worker_status"));
            assertTrue(toolNames.contains("workbench_execute_step"));
            assertTrue(toolNames.contains("workbench_mapping_snapshot"));
            assertTrue(toolNames.contains("workbench_browser_screenshot"));
            assertTrue(toolNames.contains("workbench_breakpoint_add"));
            assertTrue(toolNames.contains("workbench_step_override_compile"));
            assertTrue(toolNames.contains("workbench_step_override_clear"));

            JsonNode status = harness.toolCall(3, "workbench_worker_status", "{}");
            assertFalse(status.at("/result/isError").asBoolean());
            assertTrue(resultText(status).contains("\"running\":false"));

            JsonNode overrideError = harness.toolCall(4, "workbench_step_override_list", "{}");
            assertTrue(overrideError.at("/result/isError").asBoolean());
            assertTrue(resultText(overrideError).contains("interactive worker"));

            JsonNode invalidArguments = harness.toolCall(5, "workbench_execute_step", "{}");
            assertTrue(invalidArguments.has("error") || invalidArguments.at("/result/isError").asBoolean());

            JsonNode invalidMethod = harness.request(6, "workbench/not-a-method", "{}");
            assertNotNull(invalidMethod.get("error"));
        }
    }

    @Test
    void stepOverrideCompileUsesSharedServicesAndServerCloseIsIdempotent() throws Exception {
        AtomicBoolean compileCalled = new AtomicBoolean();
        AtomicInteger closes = new AtomicInteger();
        WorkbenchServices services = fakeServices((method, args) -> {
            if ("compileStepOverride".equals(method)) {
                assertEquals("probe", args[0]);
                assertEquals("^PROBE (.+)$", args[1]);
                assertTrue(((String) args[2]).contains("{{CLASS_NAME}}"));
                compileCalled.set(true);
                throw new IllegalStateException("compile-probe");
            }
            if ("close".equals(method)) closes.incrementAndGet();
            return null;
        });

        WorkbenchMcpTools tools = new WorkbenchMcpTools(services, JSON);
        var specification = tools.specifications().stream()
                .filter(spec -> "workbench_step_override_compile".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow();
        var result = specification.callHandler().apply(null, new McpSchema.CallToolRequest(
                "workbench_step_override_compile",
                Map.of(
                        "id", "probe",
                        "regex", "^PROBE (.+)$",
                        "source", "public final class {{CLASS_NAME}} {}"
                ),
                null
        ));
        assertTrue(Boolean.TRUE.equals(result.isError()));
        assertTrue(((McpSchema.TextContent) result.content().getFirst()).text().contains("compile-probe"));
        assertTrue(compileCalled.get());

        try (WorkbenchMcpServer server = new WorkbenchMcpServer(
                services,
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream()
        )) {
            server.close();
            server.close();
        }
        assertEquals(1, closes.get());
    }

    private static WorkbenchServices fakeServices(FakeInvocation invocation) {
        return (WorkbenchServices) Proxy.newProxyInstance(
                WorkbenchServices.class.getClassLoader(),
                new Class<?>[]{WorkbenchServices.class},
                (proxy, method, args) -> {
                    Object value = invocation.invoke(
                            method.getName(), args == null ? new Object[0] : args
                    );
                    if (value != null || method.getReturnType() == void.class) return value;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }
        );
    }

    private static String resultText(JsonNode response) {
        return response.at("/result/content/0/text").asText();
    }

    @FunctionalInterface
    private interface FakeInvocation {
        Object invoke(String method, Object[] args);
    }

    private static final class ProcessHarness implements AutoCloseable {
        private final Process process;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final ExecutorService reads;
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private final Thread stderrReader;
        private boolean closed;

        private ProcessHarness(Path project) throws Exception {
            String jar = System.getProperty("pickleball.workbench.test.jar");
            if (jar == null || jar.isBlank()) {
                throw new IllegalStateException("pickleball.workbench.test.jar was not configured by Gradle.");
            }
            String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                    .toString();
            process = new ProcessBuilder(java, "-jar", jar, "mcp", project.toString()).start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            reads = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "workbench-mcp-test-stdout");
                thread.setDaemon(true);
                return thread;
            });
            stderrReader = new Thread(() -> {
                try {
                    process.getErrorStream().transferTo(stderr);
                } catch (Exception ignored) {
                    // Process shutdown closes the stream.
                }
            }, "workbench-mcp-test-stderr");
            stderrReader.setDaemon(true);
            stderrReader.start();
        }

        private void initialize() throws Exception {
            sendRequest(1, "initialize", """
                    {
                      "protocolVersion":"2025-11-25",
                      "capabilities":{},
                      "clientInfo":{"name":"workbench-test","version":"1.0"}
                    }
                    """);
            JsonNode initialized = readResponse(STARTUP_TIMEOUT);
            assertEquals("pickleball-workbench", initialized.at("/result/serverInfo/name").asText());
            assertTrue(initialized.at("/result/capabilities/tools").isObject());
            notification("notifications/initialized", "{}");
        }

        private JsonNode request(int id, String method, String paramsJson) throws Exception {
            sendRequest(id, method, paramsJson);
            return readResponse(RESPONSE_TIMEOUT);
        }

        private void sendRequest(int id, String method, String paramsJson) throws Exception {
            send("{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"method\":" + JSON.writeValueAsString(method)
                    + ",\"params\":" + paramsJson + "}");
        }

        private JsonNode toolCall(int id, String name, String argumentsJson) throws Exception {
            return request(id, "tools/call", "{\"name\":" + JSON.writeValueAsString(name)
                    + ",\"arguments\":" + argumentsJson + "}");
        }

        private void notification(String method, String paramsJson) throws Exception {
            send("{\"jsonrpc\":\"2.0\",\"method\":" + JSON.writeValueAsString(method)
                    + ",\"params\":" + paramsJson + "}");
        }

        private void send(String json) throws Exception {
            String frame = JSON.writeValueAsString(JSON.readTree(json));
            if (frame.indexOf('\n') >= 0 || frame.indexOf('\r') >= 0) {
                throw new AssertionError("MCP stdio frame contains an embedded newline: " + frame);
            }
            writer.write(frame);
            writer.newLine();
            writer.flush();
        }

        private JsonNode readResponse(Duration timeout) throws Exception {
            try {
                String line = callWithTimeout(reads, reader::readLine, timeout);
                assertNotNull(line, () -> "MCP server closed stdout before returning a response. stderr=" + stderrText());
                return JSON.readTree(line);
            } catch (java.util.concurrent.TimeoutException failure) {
                throw new AssertionError(
                        "Timed out waiting for MCP response after " + timeout.toSeconds()
                                + "s. processAlive=" + process.isAlive()
                                + ", stderr=" + stderrText(),
                        failure
                );
            }
        }

        @Override
        public void close() throws Exception {
            if (closed) return;
            closed = true;
            try {
                writer.close();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                    throw new AssertionError("Workbench MCP process did not exit after stdin closed. stderr=" + stderrText());
                }
                assertEquals(0, process.exitValue(), () -> "Workbench MCP process failed. stderr=" + stderrText());
                String extra = callWithTimeout(reads, reader::readLine, Duration.ofSeconds(1));
                assertTrue(extra == null || extra.isBlank(), () -> "Non-protocol/extra MCP stdout: " + extra);
            } finally {
                reads.shutdownNow();
                process.destroyForcibly();
                reader.close();
            }
        }

        private String stderrText() {
            return stderr.toString(StandardCharsets.UTF_8);
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase().contains("win");
        }
    }

    private static <T> T callWithTimeout(
            ExecutorService executor,
            Callable<T> action,
            Duration timeout
    ) throws Exception {
        Future<T> future = executor.submit(action);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }
}
