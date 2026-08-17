package tools.dscode.studio.mcp;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.dscode.studio.collaboration.StudioClientKind;
import tools.dscode.studio.collaboration.StudioCollaborationService;

import java.util.Set;

/** Adds human-visible activity for existing mutating/high-impact MCP tools. */
final class StudioObservedToolCallbackProvider implements ToolCallbackProvider {
    private static final Set<String> OBSERVED_TOOLS = Set.of(
            "workspace_write_file",
            "process_run",
            "process_start",
            "process_cancel",
            "maven_run",
            "maven_start",
            "gradle_run",
            "gradle_start",
            "runtime_start",
            "runtime_pause",
            "runtime_resume",
            "runtime_execute_step",
            "runtime_mapping_put",
            "runtime_mapping_snapshot",
            "runtime_mapping_restore",
            "runtime_browser_screenshot",
            "runtime_service_call",
            "runtime_breakpoint_add",
            "runtime_breakpoint_remove",
            "runtime_breakpoints_clear"
    );

    private final ToolCallback[] callbacks;

    StudioObservedToolCallbackProvider(
            ToolCallbackProvider delegate,
            StudioCollaborationService collaboration
    ) {
        callbacks = java.util.Arrays.stream(delegate.getToolCallbacks())
                .map(callback -> observed(callback, collaboration))
                .toArray(ToolCallback[]::new);
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return callbacks.clone();
    }

    private static ToolCallback observed(
            ToolCallback delegate,
            StudioCollaborationService collaboration
    ) {
        String toolName = delegate.getToolDefinition().name();
        if (!OBSERVED_TOOLS.contains(toolName)) {
            return delegate;
        }
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }

            @Override
            public String call(String toolInput) {
                record(collaboration, toolName, "invoked");
                try {
                    return delegate.call(toolInput);
                } catch (RuntimeException failure) {
                    record(collaboration, toolName, failureText(failure));
                    throw failure;
                }
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                record(collaboration, toolName, "invoked");
                try {
                    return delegate.call(toolInput, toolContext);
                } catch (RuntimeException failure) {
                    record(collaboration, toolName, failureText(failure));
                    throw failure;
                }
            }
        };
    }

    private static void record(
            StudioCollaborationService collaboration,
            String toolName,
            String detail
    ) {
        collaboration.record(
                StudioClientKind.MCP,
                "",
                "mcp.tool",
                toolName,
                detail
        );
    }

    private static String failureText(RuntimeException failure) {
        return "failed: " + failure.getClass().getSimpleName();
    }

}
