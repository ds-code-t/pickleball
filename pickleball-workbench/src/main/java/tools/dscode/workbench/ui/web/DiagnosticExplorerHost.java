package tools.dscode.workbench.ui.web;

import java.util.function.Consumer;

/** JavaScript bridge for retained-run navigation. It does not create diagnostic data. */
public final class DiagnosticExplorerHost {
    private Consumer<String> onSelectRun;
    private Consumer<String> onFocusLayer;
    private Runnable onReady;

    public void onSelectRun(Consumer<String> onSelectRun) {
        this.onSelectRun = onSelectRun;
    }

    public void onFocusLayer(Consumer<String> onFocusLayer) {
        this.onFocusLayer = onFocusLayer;
    }

    public void onReady(Runnable onReady) {
        this.onReady = onReady;
    }

    public void selectRun(String runId) {
        WebViewPanel.onSwing(() -> {
            if (onSelectRun != null) onSelectRun.accept(runId);
        });
    }

    public void focusLayer(String layer) {
        WebViewPanel.onSwing(() -> {
            if (onFocusLayer != null) onFocusLayer.accept(layer);
        });
    }

    public void ready() {
        WebViewPanel.onSwing(() -> {
            if (onReady != null) onReady.run();
        });
    }
}
