package tools.dscode.studio.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.dscode.studio.workspace.WorkspaceFileService;
import tools.dscode.studio.workspace.WorkspaceInfo;
import tools.dscode.studio.workspace.WorkspaceService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioMcpToolsTest {

    @TempDir
    Path tempDir;

    @Test
    void exposesWorkspaceToolsThroughSpringAiCallbacks() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "Pickleball Studio\n");
        WorkspaceInfo info = new WorkspaceService().open(tempDir);
        StudioMcpTools tools = new StudioMcpTools(info, new WorkspaceFileService(info.root()));
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder().toolObjects(tools).build();

        Map<String, ToolCallback> callbacks = Arrays.stream(provider.getToolCallbacks())
                .collect(Collectors.toMap(
                        callback -> callback.getToolDefinition().name(),
                        Function.identity()
                ));

        assertEquals(
                Set.of(
                        "workspace_status",
                        "workspace_tree",
                        "workspace_read_file",
                        "workspace_write_file",
                        "workspace_search_text"
                ),
                callbacks.keySet()
        );

        String readResult = callbacks.get("workspace_read_file").call("{\"path\":\"README.md\"}");
        assertTrue(readResult.contains("Pickleball Studio"), readResult);

        callbacks.get("workspace_write_file").call(
                "{\"path\":\"notes/studio.txt\",\"content\":\"created through tool callback\"}"
        );
        assertEquals("created through tool callback", Files.readString(tempDir.resolve("notes/studio.txt")));
    }
}
