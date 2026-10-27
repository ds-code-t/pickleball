package tools.dscode.workbench.ui;

import tools.dscode.control.protocol.RunConfigs;
import tools.dscode.control.protocol.Workspace;

import java.nio.file.Path;
import java.util.List;

/**
 * View of one run's workspace configs. Showing it only reads. Saving writes
 * that copy atomically and does not write the consumer project's configs.
 * A change on disk since the view was loaded is a warning and is not overwritten.
 */
public final class ConfigTabModel {
    private Path project;
    private String runId = "";
    private boolean editable;
    private String selected = "";
    private String draft = "";
    private String status = "No run is open.";
    private Workspace.DiskStamp loadedStamp;

    public void show(Path project, String runId, boolean editable) {
        this.project = project;
        this.runId = runId == null ? "" : runId.trim();
        this.editable = editable && project != null && !this.runId.isEmpty();
        List<String> names = files();
        if (selected.isEmpty() || !names.contains(selected)) {
            selected = names.isEmpty() ? "" : names.getFirst();
        }
        load();
    }

    public List<String> files() {
        if (project == null || runId.isEmpty()) return List.of();
        return RunConfigs.files(project, runId);
    }

    public void select(String relative) {
        selected = relative == null ? "" : relative.trim();
        load();
    }

    public void setDraft(String text) {
        draft = text == null ? "" : text;
    }

    public void save() {
        if (project == null || runId.isEmpty() || selected.isEmpty()) {
            status = "No config file is open.";
            throw new IllegalStateException(status);
        }
        if (!editable) {
            status = "This run is read-only. The project configs were not written.";
            throw new IllegalStateException(status);
        }
        Path workspace = Workspace.directory(project, runId);
        if (loadedStamp != null && Workspace.changedSince(workspace, loadedStamp)) {
            status = "WARNING: workspace changed on disk. Reload before saving. Nothing was written.";
            throw new IllegalStateException(status);
        }
        RunConfigs.write(project, runId, selected, draft);
        loadedStamp = Workspace.stamp(workspace);
        status = "Saved " + selected + " in this run's workspace.";
    }

    public String selected() {
        return selected;
    }

    public String text() {
        return draft;
    }

    public String status() {
        return status;
    }

    public boolean editable() {
        return editable;
    }

    public String runId() {
        return runId;
    }

    private void load() {
        if (project == null || runId.isEmpty()) {
            draft = "";
            status = "No run is open.";
            loadedStamp = null;
            return;
        }
        if (selected.isEmpty()) {
            draft = "";
            status = editable
                    ? "This run has no config files."
                    : "Read-only. This run has no config files.";
            rememberStamp();
            return;
        }
        try {
            draft = RunConfigs.read(project, runId, selected);
            status = (editable ? "Editing " : "Read-only ") + selected + " in this run's workspace.";
        } catch (RuntimeException failure) {
            draft = "";
            status = failure.getMessage();
        }
        rememberStamp();
    }

    private void rememberStamp() {
        if (project == null || runId.isEmpty()) {
            loadedStamp = null;
            return;
        }
        loadedStamp = Workspace.stamp(Workspace.directory(project, runId));
    }
}
