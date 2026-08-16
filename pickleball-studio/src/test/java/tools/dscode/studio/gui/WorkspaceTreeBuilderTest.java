
package tools.dscode.studio.gui;

import org.junit.jupiter.api.Test;
import tools.dscode.studio.workspace.WorkspaceEntry;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkspaceTreeBuilderTest {

    @Test
    void buildsNestedWorkspaceTreeFromFlatEntries() {
        DefaultMutableTreeNode root = WorkspaceTreeBuilder.build(
                "fixture",
                List.of(
                        new WorkspaceEntry("src", true, 0),
                        new WorkspaceEntry("src/main", true, 0),
                        new WorkspaceEntry("src/main/App.java", false, 42),
                        new WorkspaceEntry("README.md", false, 10)
                )
        );

        assertEquals("fixture", root.getUserObject().toString());
        assertEquals(2, root.getChildCount());

        DefaultMutableTreeNode src = (DefaultMutableTreeNode) root.getChildAt(0);
        assertEquals("src", src.getUserObject().toString());
        DefaultMutableTreeNode main = (DefaultMutableTreeNode) src.getChildAt(0);
        assertEquals("main", main.getUserObject().toString());

        WorkspaceTreeItem app = (WorkspaceTreeItem)
                ((DefaultMutableTreeNode) main.getChildAt(0)).getUserObject();
        assertEquals("src/main/App.java", app.path());
        assertFalse(app.directory());
    }
}
