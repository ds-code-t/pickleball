package tools.dscode.workbench.ui;

import tools.dscode.control.protocol.RunConfigs;

import java.nio.file.Path;
import java.util.List;

/**
 * View of one run's config copy. Showing it only reads. Saving writes that
 * copy and does not write the consumer project's configs.
 */
public final class ConfigTabModel {
    private Path project;
    private String runId = "";
    private boolean editable;
    private String selected = "";
    private String draft = "";
    private String status = "No run is open.";

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
        RunConfigs.write(project, runId, selected, draft);
        status = "Saved " + selected + " in this run's config copy.";
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
            return;
        }
        if (selected.isEmpty()) {
            draft = "";
            status = editable
                    ? "This run has no config files."
                    : "Read-only. This run has no config files.";
            return;
        }
        try {
            draft = RunConfigs.read(project, runId, selected);
            status = (editable ? "Editing " : "Read-only ") + selected + " in this run's config copy.";
        } catch (RuntimeException failure) {
            draft = "";
            status = failure.getMessage();
        }
    }
}
