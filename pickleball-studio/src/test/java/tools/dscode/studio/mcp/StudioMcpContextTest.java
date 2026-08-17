package tools.dscode.studio.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.dscode.studio.collaboration.StudioCollaborationService;
import tools.dscode.studio.gui.StudioDesktopSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioMcpContextTest {

    @TempDir
    Path tempDir;

    @Test
    void bootsStreamableHttpServerWithStudioTools() {
        Map<String, Object> properties = Map.ofEntries(
                Map.entry("pickleball.studio.workspace", tempDir.toString()),
                Map.entry("server.address", "127.0.0.1"),
                Map.entry("server.port", 0),
                Map.entry("spring.ai.mcp.server.protocol", "STREAMABLE"),
                Map.entry("spring.ai.mcp.server.name", "pickleball-studio-test"),
                Map.entry("spring.ai.mcp.server.type", "SYNC"),
                Map.entry("spring.ai.mcp.server.capabilities.tool", true),
                Map.entry("spring.ai.mcp.server.capabilities.resource", false),
                Map.entry("spring.ai.mcp.server.capabilities.prompt", false),
                Map.entry("spring.ai.mcp.server.capabilities.completion", false),
                Map.entry("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp/test-token"),
                Map.entry("logging.level.root", "WARN")
        );

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(StudioMcpConfiguration.class)
                .web(WebApplicationType.SERVLET)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .properties(properties)
                .run()) {

            WebServerApplicationContext webContext = (WebServerApplicationContext) context;
            assertNotNull(webContext.getWebServer());
            assertTrue(webContext.getWebServer().getPort() > 0);
            StudioDesktopSession desktop = context.getBean(StudioDesktopSession.class);
            StudioCollaborationService collaboration = context.getBean(StudioCollaborationService.class);
            assertNotNull(desktop);
            desktop.activateDesktop();
            assertTrue(collaboration.activity(0L, 20).activities().stream()
                    .anyMatch(activity -> "desktop.session.open".equals(activity.operation())));

            ToolCallbackProvider provider = context.getBean("studioTools", ToolCallbackProvider.class);
            assertEquals(52, provider.getToolCallbacks().length);

            ToolCallback write = Arrays.stream(provider.getToolCallbacks())
                    .filter(callback -> "workspace_write_file".equals(
                            callback.getToolDefinition().name()
                    ))
                    .findFirst()
                    .orElseThrow();
            write.call("{\"path\":\"observed.txt\",\"content\":\"hello\"}");
            assertTrue(Files.exists(tempDir.resolve("observed.txt")));
            assertTrue(collaboration.activity(0L, 50).activities().stream()
                    .anyMatch(activity -> "workspace_write_file".equals(activity.target())
                            && "mcp.tool".equals(activity.operation())));
            assertTrue(collaboration.activity(0L, 50).activities().stream()
                    .noneMatch(activity -> activity.detail().contains("hello")));
        }
    }
}
