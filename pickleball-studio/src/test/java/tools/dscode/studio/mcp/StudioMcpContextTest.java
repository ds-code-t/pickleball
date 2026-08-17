package tools.dscode.studio.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
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

            ToolCallbackProvider provider = context.getBean("studioTools", ToolCallbackProvider.class);
            assertEquals(34, provider.getToolCallbacks().length);
        }
    }
}
