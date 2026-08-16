package tools.dscode.studio.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.dscode.studio.workspace.TextSearchMatch;
import tools.dscode.studio.workspace.WorkspaceEntry;
import tools.dscode.studio.workspace.WorkspaceFileService;
import tools.dscode.studio.workspace.WorkspaceInfo;
import tools.dscode.studio.workspace.WorkspaceTextFile;
import tools.dscode.studio.workspace.WorkspaceWriteResult;

import java.util.List;

public final class StudioMcpTools {
    private static final int DEFAULT_TREE_DEPTH = 4;
    private static final int DEFAULT_TREE_ENTRIES = 500;
    private static final int DEFAULT_SEARCH_RESULTS = 100;

    private final WorkspaceInfo workspace;
    private final WorkspaceFileService files;

    public StudioMcpTools(WorkspaceInfo workspace, WorkspaceFileService files) {
        this.workspace = workspace;
        this.files = files;
    }

    @Tool(
            name = "workspace_status",
            description = "Return the currently opened Studio workspace and its Maven, Gradle, and Git markers."
    )
    public StudioWorkspaceStatus workspaceStatus() {
        return new StudioWorkspaceStatus(
                workspace.root().toString(),
                workspace.name(),
                workspace.mavenProject(),
                workspace.gradleProject(),
                workspace.gitRepository()
        );
    }

    @Tool(
            name = "workspace_tree",
            description = "List a deterministic workspace directory tree. Generated/build directories are skipped."
    )
    public List<WorkspaceEntry> workspaceTree(
            @ToolParam(description = "Workspace-relative directory path. Empty means the workspace root.", required = false)
            String path,
            @ToolParam(description = "Maximum directory depth. Defaults to 4.", required = false)
            Integer maxDepth,
            @ToolParam(description = "Maximum returned entries. Defaults to 500.", required = false)
            Integer maxEntries
    ) {
        return files.tree(
                path,
                maxDepth == null ? DEFAULT_TREE_DEPTH : maxDepth,
                maxEntries == null ? DEFAULT_TREE_ENTRIES : maxEntries
        );
    }

    @Tool(
            name = "workspace_read_file",
            description = "Read one UTF-8 text file from the current workspace."
    )
    public WorkspaceTextFile readFile(
            @ToolParam(description = "Workspace-relative file path.") String path
    ) {
        return files.readText(path);
    }

    @Tool(
            name = "workspace_write_file",
            description = "Create or replace one UTF-8 text file inside the current workspace. Parent directories are created as needed."
    )
    public WorkspaceWriteResult writeFile(
            @ToolParam(description = "Workspace-relative file path.") String path,
            @ToolParam(description = "Complete UTF-8 text content to write.") String content
    ) {
        return files.writeText(path, content);
    }

    @Tool(
            name = "workspace_search_text",
            description = "Search UTF-8 workspace text files for a literal substring. Generated/build directories are skipped."
    )
    public List<TextSearchMatch> searchText(
            @ToolParam(description = "Literal text to search for.") String query,
            @ToolParam(description = "Workspace-relative file or directory to search. Empty means the workspace root.", required = false)
            String path,
            @ToolParam(description = "Whether matching is case-sensitive. Defaults to true.", required = false)
            Boolean caseSensitive,
            @ToolParam(description = "Maximum returned matches. Defaults to 100.", required = false)
            Integer maxResults
    ) {
        return files.searchText(
                query,
                path,
                caseSensitive == null || caseSensitive,
                maxResults == null ? DEFAULT_SEARCH_RESULTS : maxResults
        );
    }
}
