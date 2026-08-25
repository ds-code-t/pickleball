package tools.dscode.workbench.ui.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** JavaScript bridge for the live Gherkin block editor. Does not execute Gherkin. */
public final class GherkinEditorHost {
    private Consumer<List<String>> onDocument;
    private LongConsumer onSeek;
    private Runnable onAddStep;
    private Runnable onReady;

    public void onDocument(Consumer<List<String>> onDocument) {
        this.onDocument = onDocument;
    }

    public void onSeek(LongConsumer onSeek) {
        this.onSeek = onSeek;
    }

    public void onAddStep(Runnable onAddStep) {
        this.onAddStep = onAddStep;
    }

    public void onReady(Runnable onReady) {
        this.onReady = onReady;
    }

    public void documentChanged(String json) {
        Map<String, Object> payload = WorkbenchWebJson.readMap(json);
        Object raw = payload.get("lines");
        List<String> lines = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) lines.add(item == null ? "" : item.toString());
        }
        WebViewPanel.onSwing(() -> {
            if (onDocument != null) onDocument.accept(lines);
        });
    }

    public void seek(long id) {
        WebViewPanel.onSwing(() -> {
            if (id >= 0 && onSeek != null) onSeek.accept(id);
        });
    }

    public void requestAddStep() {
        WebViewPanel.onSwing(() -> {
            if (onAddStep != null) onAddStep.run();
        });
    }

    public void ready() {
        WebViewPanel.onSwing(() -> {
            if (onReady != null) onReady.run();
        });
    }
}
