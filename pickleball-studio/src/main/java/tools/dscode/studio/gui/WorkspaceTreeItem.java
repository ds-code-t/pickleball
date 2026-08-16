
package tools.dscode.studio.gui;

record WorkspaceTreeItem(
        String path,
        String name,
        boolean directory
) {
    @Override
    public String toString() {
        return name;
    }
}
