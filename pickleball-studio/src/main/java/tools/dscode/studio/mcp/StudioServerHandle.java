package tools.dscode.studio.mcp;

import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

public final class StudioServerHandle implements AutoCloseable {
    private final ConfigurableApplicationContext context;
    private final Path workspace;
    private final int port;
    private final String endpointPath;

    StudioServerHandle(
            ConfigurableApplicationContext context,
            Path workspace,
            int port,
            String endpointPath
    ) {
        this.context = context;
        this.workspace = workspace;
        this.port = port;
        this.endpointPath = endpointPath;
    }

    public ConfigurableApplicationContext context() { return context; }
    public Path workspace() { return workspace; }
    public int port() { return port; }
    public String endpointPath() { return endpointPath; }
    public String endpointUrl() { return "http://127.0.0.1:" + port + endpointPath; }

    @Override
    public void close() {
        context.close();
    }
}
