
package tools.dscode.studio.gui;

import tools.dscode.studio.workspace.WorkspaceEntry;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class WorkspaceTreeBuilder {
    private WorkspaceTreeBuilder() {
    }

    static DefaultMutableTreeNode build(String workspaceName, List<WorkspaceEntry> entries) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(
                new WorkspaceTreeItem("", workspaceName, true)
        );
        Map<String, DefaultMutableTreeNode> nodes = new HashMap<>();
        nodes.put("", root);

        for (WorkspaceEntry entry : entries) {
            String path = entry.path();
            String parentPath = parent(path);
            DefaultMutableTreeNode parent = nodes.get(parentPath);
            if (parent == null) {
                continue;
            }

            DefaultMutableTreeNode node = new DefaultMutableTreeNode(
                    new WorkspaceTreeItem(path, name(path), entry.directory())
            );
            parent.add(node);
            if (entry.directory()) {
                nodes.put(path, node);
            }
        }

        return root;
    }

    private static String parent(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator);
    }

    private static String name(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? path : path.substring(separator + 1);
    }
}
