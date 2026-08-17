package tools.dscode.studio.mcp;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.PrintStream;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StudioServer {
    private static final SecureRandom RANDOM = new SecureRandom();

    private StudioServer() {
    }

    public static int start(Path workspace, int port, String requestedToken, PrintStream out, PrintStream err) {
        try {
            StudioServerHandle handle = open(workspace, port, requestedToken);
            out.println("Pickleball Studio MCP server ready");
            out.println("Workspace: " + handle.workspace());
            out.println("MCP endpoint: " + handle.endpointUrl());
            return 0;
        } catch (IllegalArgumentException error) {
            err.println(error.getMessage());
            return 2;
        } catch (RuntimeException error) {
            err.println("Unable to start Pickleball Studio MCP server: " + error.getMessage());
            return 2;
        }
    }

    public static StudioServerHandle open(Path workspace, int port, String requestedToken) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Studio MCP port must be between 0 and 65535.");
        }

        String token = requestedToken == null ? generateToken() : validateToken(requestedToken);
        Path root = workspace.toAbsolutePath().normalize();
        String endpoint = "/mcp/" + token;

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pickleball.studio.workspace", root.toString());
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", port);
        properties.put("spring.ai.mcp.server.protocol", "STREAMABLE");
        properties.put("spring.ai.mcp.server.name", "pickleball-studio");
        properties.put("spring.ai.mcp.server.version", studioVersion());
        properties.put("spring.ai.mcp.server.type", "SYNC");
        properties.put(
                "spring.ai.mcp.server.instructions",
                "Use Pickleball Studio for deterministic project/runtime operations. "
                        + "Start an agent_session for collaboration-aware work, check studio_editor_states before edits, "
                        + "and use versioned checked writes when a human may be editing the same file."
        );
        properties.put("spring.ai.mcp.server.capabilities.tool", true);
        properties.put("spring.ai.mcp.server.capabilities.resource", false);
        properties.put("spring.ai.mcp.server.capabilities.prompt", false);
        properties.put("spring.ai.mcp.server.capabilities.completion", false);
        properties.put("spring.ai.mcp.server.resource-change-notification", false);
        properties.put("spring.ai.mcp.server.prompt-change-notification", false);
        properties.put("spring.ai.mcp.server.tool-change-notification", false);
        properties.put("spring.ai.mcp.server.streamable-http.mcp-endpoint", endpoint);
        properties.put("logging.level.root", "WARN");

        ConfigurableApplicationContext context = new SpringApplicationBuilder(StudioMcpConfiguration.class)
                .web(WebApplicationType.SERVLET)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .properties(properties)
                .run();

        if (!(context instanceof WebServerApplicationContext webContext)) {
            context.close();
            throw new IllegalStateException("Studio MCP server did not create a web application context.");
        }
        WebServer webServer = webContext.getWebServer();
        if (webServer == null) {
            context.close();
            throw new IllegalStateException("Studio MCP server did not create a web server.");
        }

        return new StudioServerHandle(
                context,
                root,
                webServer.getPort(),
                endpoint
        );
    }

    private static String studioVersion() {
        String version = StudioServer.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }

    private static String generateToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String validateToken(String token) {
        if (!token.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new IllegalArgumentException(
                    "Studio MCP token must contain 8-128 URL-safe letters, digits, '_' or '-'."
            );
        }
        return token;
    }
}
