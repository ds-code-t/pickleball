package tools.dscode.workbench.player;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One open Gherkin document in the Workbench editor tab strip. */
public final class EditorTabState {
    private final String id;
    private String title;
    private Path file;
    private String scenarioName;
    private int startLine;
    private int endLine;
    private int exampleRow;
    private String exampleLabel;
    private String documentText;
    private boolean pinned;
    private boolean dirty;
    private boolean peek;
    private boolean readOnly;

    public EditorTabState(String title, Path file, String documentText, boolean pinned) {
        this.id = UUID.randomUUID().toString();
        this.title = title == null || title.isBlank() ? "Untitled" : title;
        this.file = file;
        this.scenarioName = "";
        this.exampleLabel = "";
        this.documentText = documentText == null ? "" : documentText;
        this.pinned = pinned;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public Path file() {
        return file;
    }

    public String scenarioName() {
        return scenarioName;
    }

    public int startLine() {
        return startLine;
    }

    public int endLine() {
        return endLine;
    }

    public int exampleRow() {
        return exampleRow;
    }

    public String exampleLabel() {
        return exampleLabel;
    }

    public String documentText() {
        return documentText;
    }

    public boolean pinned() {
        return pinned;
    }

    public boolean dirty() {
        return dirty;
    }

    public boolean peek() {
        return peek;
    }

    public boolean readOnly() {
        return readOnly || peek;
    }

    public void setPeek(boolean peek) {
        this.peek = peek;
        if (peek) this.readOnly = true;
    }

    public void setTitle(String title) {
        this.title = title == null || title.isBlank() ? this.title : title;
    }

    public void setOrigin(Path file, String scenarioName, int startLine, int endLine, int exampleRow, String exampleLabel) {
        this.file = file;
        this.scenarioName = scenarioName == null ? "" : scenarioName;
        this.startLine = startLine;
        this.endLine = endLine;
        this.exampleRow = exampleRow;
        this.exampleLabel = exampleLabel == null ? "" : exampleLabel;
    }

    public void setDocumentText(String documentText) {
        String next = documentText == null ? "" : documentText;
        if (!Objects.equals(this.documentText, next)) {
            this.documentText = next;
            this.dirty = true;
        }
    }

    public void markClean() {
        this.dirty = false;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public boolean sameTarget(Path file, String scenarioName, int exampleRow) {
        if (file == null || this.file == null) return false;
        return this.file.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())
                && Objects.equals(this.scenarioName, scenarioName == null ? "" : scenarioName)
                && this.exampleRow == exampleRow;
    }

    public List<String> lines() {
        if (documentText.isEmpty()) return List.of();
        return List.of(documentText.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    }

    public String tabTitle() {
        String base = title;
        return dirty ? base + " *" : base;
    }
}