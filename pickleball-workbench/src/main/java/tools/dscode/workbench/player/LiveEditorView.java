package tools.dscode.workbench.player;

/**
 * Headless Text vs Blocks presentation choice for the live scenario editor.
 *
 * <p>The view mode is independent of {@link LiveScenarioPlayer}: switching
 * does not change document text, line identities, selection, or playhead.
 * When JavaFX {@code WebView} is unavailable, block view is honestly
 * unavailable and the live buffer stays on the existing text fallback.</p>
 */
public final class LiveEditorView {
    public enum Mode {
        TEXT,
        BLOCKS
    }

    private final boolean blocksAvailable;
    private Mode mode;

    public LiveEditorView(boolean blocksAvailable) {
        this.blocksAvailable = blocksAvailable;
        this.mode = blocksAvailable ? Mode.BLOCKS : Mode.TEXT;
    }

    public static LiveEditorView blocksAvailable() {
        return new LiveEditorView(true);
    }

    public static LiveEditorView blocksUnavailable() {
        return new LiveEditorView(false);
    }

    public boolean canShowBlocks() {
        return blocksAvailable;
    }

    public Mode mode() {
        return mode;
    }

    public boolean showingBlocks() {
        return mode == Mode.BLOCKS;
    }

    /**
     * Selects Text or Blocks. Requests for Blocks when the WebView host is
     * unavailable are ignored and return {@code false}; the view stays Text.
     */
    public boolean setMode(Mode requested) {
        Mode next = requested == null ? mode : requested;
        if (next == Mode.BLOCKS && !blocksAvailable) {
            mode = Mode.TEXT;
            return false;
        }
        mode = next;
        return true;
    }

    public boolean showText() {
        return setMode(Mode.TEXT);
    }

    public boolean showBlocks() {
        return setMode(Mode.BLOCKS);
    }
}
