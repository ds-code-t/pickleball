package tools.dscode.workbench.ui.web;

import java.util.Map;
import java.util.function.Consumer;

/** JavaScript bridge for Mapping property edits. Persistence stays in WorkbenchServices. */
public final class MappingEditorHost {
    public record PropertyEdit(String mapReference, String oldKey, String key, String type, String text) { }

    private Consumer<String> onSelect;
    private Consumer<PropertyEdit> onEdit;
    private Runnable onReady;

    public void onSelect(Consumer<String> onSelect) {
        this.onSelect = onSelect;
    }

    public void onEdit(Consumer<PropertyEdit> onEdit) {
        this.onEdit = onEdit;
    }

    public void onReady(Runnable onReady) {
        this.onReady = onReady;
    }

    public void selectMap(String reference) {
        WebViewPanel.onSwing(() -> {
            if (onSelect != null) onSelect.accept(reference);
        });
    }

    public void propertyChanged(String json) {
        Map<String, Object> payload = WorkbenchWebJson.readMap(json);
        PropertyEdit edit = new PropertyEdit(
                string(payload, "mapReference"),
                string(payload, "oldKey"),
                string(payload, "key"),
                string(payload, "type"),
                string(payload, "text")
        );
        WebViewPanel.onSwing(() -> {
            if (onEdit != null) onEdit.accept(edit);
        });
    }

    public void ready() {
        WebViewPanel.onSwing(() -> {
            if (onReady != null) onReady.run();
        });
    }

    private static String string(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : value.toString();
    }
}
