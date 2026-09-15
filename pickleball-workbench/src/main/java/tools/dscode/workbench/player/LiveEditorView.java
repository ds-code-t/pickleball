package tools.dscode.workbench.player;

/**
 * Live Scenario Editor presentation. The only view is ordinary Gherkin text
 * over {@link LiveScenarioPlayer}; there is no Blocks mode.
 */
public final class LiveEditorView {
    public enum Mode {
        TEXT
    }

    public static LiveEditorView textOnly() {
        return new LiveEditorView();
    }

    public Mode mode() {
        return Mode.TEXT;
    }

    public boolean canShowBlocks() {
        return false;
    }

    public boolean showingBlocks() {
        return false;
    }
}
