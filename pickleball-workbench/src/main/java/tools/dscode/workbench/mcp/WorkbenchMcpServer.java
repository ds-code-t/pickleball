package tools.dscode.workbench.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import tools.dscode.workbench.WorkbenchController;
import tools.dscode.workbench.WorkbenchServices;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lightweight non-Spring stdio MCP adapter over shared Workbench services. */
public final class WorkbenchMcpServer implements AutoCloseable {
    private final WorkbenchServices services;
    private final McpSyncServer server;
    private final AtomicBoolean closed = new AtomicBoolean();

    public WorkbenchMcpServer(Path projectRoot, InputStream input, OutputStream protocolOutput) {
        this(new WorkbenchController(projectRoot), input, protocolOutput);
    }

    WorkbenchMcpServer(
            WorkbenchServices services,
            InputStream input,
            OutputStream protocolOutput
    ) {
        this.services = Objects.requireNonNull(services, "services");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(protocolOutput, "protocolOutput");

        ObjectMapper jackson = new ObjectMapper();
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(jackson);
        StdioServerTransportProvider transport =
                new StdioServerTransportProvider(mapper, input, protocolOutput);
        WorkbenchMcpTools tools = new WorkbenchMcpTools(services, jackson);

        this.server = McpServer.sync(transport)
                .serverInfo("pickleball-workbench", implementationVersion())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tools.specifications())
                .build();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            server.closeGracefully();
        } finally {
            services.close();
        }
    }

    private static String implementationVersion() {
        String version = WorkbenchMcpServer.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }
}
