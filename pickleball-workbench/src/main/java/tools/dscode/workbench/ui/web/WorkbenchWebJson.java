package tools.dscode.workbench.ui.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.workbench.mapping.MappingTreeModel;
import tools.dscode.workbench.mapping.MappingValueCodec;
import tools.dscode.workbench.player.GherkinBlockDocument;
import tools.dscode.workbench.player.LiveScenarioPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorkbenchWebJson {
    public record MapChoice(String reference, String label, boolean restorable) { }
    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkbenchWebJson() {
    }

    public static String editorState(LiveScenarioPlayer player, Long executingId) {
        return editorState(player, executingId, false);
    }

    public static String editorState(LiveScenarioPlayer player, Long executingId, boolean locked) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roots", blocks(GherkinBlockDocument.fromPlayer(player).roots()));
        payload.put("selectedId", player.selectedId().isPresent() ? player.selectedId().getAsLong() : null);
        payload.put("playheadId", player.playheadId().isPresent() ? player.playheadId().getAsLong() : null);
        payload.put("executingId", executingId);
        payload.put("locked", locked);
        return write(payload);
    }

    public static String mappingState(
            List<MapChoice> entries,
            MapChoice selected,
            MappingTreeModel model,
            String status
    ) {
        return mappingState(entries, selected, model, status, false);
    }

    public static String mappingState(
            List<MapChoice> entries,
            MapChoice selected,
            MappingTreeModel model,
            String status,
            boolean locked
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        List<Map<String, Object>> maps = new ArrayList<>();
        for (MapChoice entry : entries) {
            maps.add(Map.of(
                    "reference", entry.reference(),
                    "label", entry.label(),
                    "restorable", entry.restorable()
            ));
        }
        payload.put("entries", maps);
        payload.put("mapReference", selected == null ? "" : selected.reference());
        payload.put("restorable", model != null && model.restorable());
        payload.put("status", status == null ? "" : status);
        List<Map<String, Object>> properties = new ArrayList<>();
        if (model != null) {
            for (MappingTreeModel.Property property : model.properties()) {
                properties.add(Map.of(
                        "key", property.key(),
                        "type", jsType(property.type()),
                        "text", property.text()
                ));
            }
        }
        payload.put("properties", properties);
        payload.put("locked", locked);
        return write(payload);
    }

    static String jsType(MappingValueCodec.ValueType type) {
        return switch (type) {
            case STRING -> "string";
            case NUMERIC -> "numeric";
            case BOOLEAN -> "boolean";
            case OBJECT_JSON -> "object-as-json";
            case OBJECT_XML -> "object-as-xml";
        };
    }

    public static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not encode Workbench WebView state.", failure);
        }
    }

    static Map<String, Object> readMap(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> value = JSON.readValue(json, Map.class);
            return value;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid WebView payload.", failure);
        }
    }

    private static List<Map<String, Object>> blocks(List<GherkinBlockDocument.Block> blocks) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (GherkinBlockDocument.Block block : blocks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", block.id());
            item.put("text", block.text());
            item.put("children", blocks(block.children()));
            items.add(item);
        }
        return items;
    }
}
