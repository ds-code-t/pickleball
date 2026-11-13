package dev.domengine.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.tree.DomNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Row states for one query. Live properties are one {@code Runtime.callFunctionOn} for the whole hit list. */
public final class RowStates {
    public static final List<String> NAMES = List.of(
            "hidden", "disabled", "checked", "selected", "readonly", "required", "expanded", "collapsed", "focused"
    );

    private static final String READ = """
            function() {
              const active = document.activeElement;
              const out = [];
              for (let i = 0; i < arguments.length; i++) {
                const el = arguments[i];
                if (!el || el.nodeType !== 1) { out.push(null); continue; }
                let disabled = el.disabled === true || el.hasAttribute('disabled');
                for (let p = el.parentElement; p; p = p.parentElement) {
                  if (p.tagName === 'FIELDSET' && (p.disabled === true || p.hasAttribute('disabled'))) disabled = true;
                }
                const expanded = el.getAttribute('aria-expanded');
                out.push({
                  disabled: disabled,
                  checked: el.checked === true,
                  selected: el.selected === true,
                  readonly: el.readOnly === true || el.hasAttribute('readonly'),
                  required: el.required === true || el.hasAttribute('required'),
                  expanded: expanded === 'true',
                  collapsed: expanded === 'false',
                  focused: el === active
                });
              }
              return out;
            }
            """;

    private RowStates() {
    }

    public static void fill(CdpConnection connection, List<DomNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return;
        if (connection == null) {
            for (DomNode node : nodes) node.liveState = offline(node);
            return;
        }
        Map<String, List<DomNode>> groups = new LinkedHashMap<>();
        for (DomNode node : nodes) {
            String key = node.framePath == null ? "" : node.framePath;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(node);
        }
        for (List<DomNode> group : groups.values()) fillGroup(connection, group);
        EngineLog.info("state", "rows=" + nodes.size() + " batches=" + groups.size());
    }

    private static void fillGroup(CdpConnection connection, List<DomNode> nodes) {
        List<DomNode> resolved = new ArrayList<>();
        List<String> objectIds = new ArrayList<>();
        for (DomNode node : nodes) {
            if (node.backendNodeId <= 0) {
                node.liveState = offline(node);
                continue;
            }
            try {
                ObjectNode params = Jsons.object();
                params.put("backendNodeId", node.backendNodeId);
                JsonNode result = connection.call("DOM.resolveNode", params, Duration.ofSeconds(5));
                String objectId = result.path("object").path("objectId").asText("");
                if (objectId.isBlank()) {
                    node.liveState = offline(node);
                    continue;
                }
                resolved.add(node);
                objectIds.add(objectId);
            } catch (RuntimeException ignored) {
                node.liveState = offline(node);
            }
        }
        if (objectIds.isEmpty()) return;
        try {
            apply(connection, resolved, objectIds);
        } catch (RuntimeException failure) {
            for (int i = 0; i < resolved.size(); i++) {
                try {
                    apply(connection, List.of(resolved.get(i)), List.of(objectIds.get(i)));
                } catch (RuntimeException ignored) {
                    resolved.get(i).liveState = offline(resolved.get(i));
                }
            }
        }
    }

    private static void apply(CdpConnection connection, List<DomNode> resolved, List<String> objectIds) {
        ObjectNode call = Jsons.object();
        call.put("functionDeclaration", READ);
        call.put("objectId", objectIds.get(0));
        call.put("returnByValue", true);
        ArrayNode args = call.putArray("arguments");
        for (String objectId : objectIds) args.addObject().put("objectId", objectId);
        JsonNode called = connection.call("Runtime.callFunctionOn", call, Duration.ofSeconds(5));
        JsonNode values = called.path("result").path("value");
        for (int i = 0; i < resolved.size(); i++) {
            JsonNode row = values.isArray() && i < values.size() ? values.get(i) : null;
            resolved.get(i).liveState = row == null || row.isNull() ? offline(resolved.get(i)) : row;
        }
    }

    public static void add(ArrayNode states, DomNode node) {
        JsonNode live = node.liveState == null ? offline(node) : node.liveState;
        if (!Displayed.isDisplayed(node)) states.add("hidden");
        if (live.path("disabled").asBoolean(false) || disabled(node)) states.add("disabled");
        if (live.path("checked").asBoolean(false)) states.add("checked");
        if (live.path("selected").asBoolean(false)) states.add("selected");
        if (live.path("readonly").asBoolean(false)) states.add("readonly");
        if (live.path("required").asBoolean(false)) states.add("required");
        if (live.path("expanded").asBoolean(false)) states.add("expanded");
        if (live.path("collapsed").asBoolean(false)) states.add("collapsed");
        if (live.path("focused").asBoolean(false)) states.add("focused");
    }

    private static boolean disabled(DomNode node) {
        for (DomNode cursor = node; cursor != null; cursor = cursor.parent) {
            if (cursor.attributes.containsKey("disabled")
                    && ("fieldset".equalsIgnoreCase(cursor.localName) || cursor == node)) {
                return true;
            }
        }
        return node.attributes.containsKey("disabled");
    }

    private static ObjectNode offline(DomNode node) {
        ObjectNode row = Jsons.object();
        row.put("disabled", node.attributes.containsKey("disabled"));
        row.put("checked", node.attributes.containsKey("checked"));
        row.put("selected", node.attributes.containsKey("selected"));
        row.put("readonly", node.attributes.containsKey("readonly"));
        row.put("required", node.attributes.containsKey("required"));
        String expanded = node.attributes.get("aria-expanded");
        row.put("expanded", "true".equalsIgnoreCase(expanded));
        row.put("collapsed", "false".equalsIgnoreCase(expanded));
        row.put("focused", false);
        if (expanded != null) row.put("aria", expanded.toLowerCase(Locale.ROOT));
        return row;
    }
}
