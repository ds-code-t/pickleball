package tools.dscode.studio.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.studio.collaboration.StudioAgentSession;
import tools.dscode.studio.collaboration.StudioCollaborationService;
import tools.dscode.studio.workspace.WorkspaceCheckedWriteResult;
import tools.dscode.studio.workspace.WorkspaceConcurrencyService;
import tools.dscode.studio.workspace.WorkspaceFileService;
import tools.dscode.studio.workspace.WorkspaceVersionedTextFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioCollaborationMcpToolsTest {

    @TempDir
    Path tempDir;

    @Test
    void checkedWriteHonorsVersionAndDirtyDesktopEditor() throws Exception {
        Path file = tempDir.resolve("sample.feature");
        Files.writeString(file, "Feature: old\n");

        StudioCollaborationService collaboration = new StudioCollaborationService();
        WorkspaceConcurrencyService files = new WorkspaceConcurrencyService(
                new WorkspaceFileService(tempDir)
        );
        StudioCollaborationMcpTools tools = new StudioCollaborationMcpTools(
                collaboration,
                files,
                null,
                null
        );
        StudioAgentSession agent = tools.startAgentSession("test-agent");

        WorkspaceVersionedTextFile first = tools.readVersioned("sample.feature");
        collaboration.editorState("desktop-test", "sample.feature", true, first.sha256());

        WorkspaceCheckedWriteResult blocked = tools.writeChecked(
                agent.id(),
                "./sample.feature",
                first.sha256(),
                "Feature: blocked\n"
        );
        assertFalse(blocked.written());
        assertTrue(blocked.blockedByDirtyEditor());
        assertEquals("Feature: old\n", Files.readString(file));

        collaboration.editorState("desktop-test", "sample.feature", false, first.sha256());
        WorkspaceCheckedWriteResult written = tools.writeChecked(
                agent.id(),
                "sample.feature",
                first.sha256(),
                "Feature: new\n"
        );
        assertTrue(written.written());
        assertEquals("Feature: new\n", Files.readString(file));

        WorkspaceCheckedWriteResult stale = tools.writeChecked(
                agent.id(),
                "sample.feature",
                first.sha256(),
                "Feature: stale\n"
        );
        assertFalse(stale.written());
        assertFalse(stale.blockedByDirtyEditor());
        assertEquals("Feature: new\n", Files.readString(file));
    }
}
