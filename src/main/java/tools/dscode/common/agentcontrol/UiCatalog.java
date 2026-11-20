package tools.dscode.common.agentcontrol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one registry of page UI components an agent can batch with {@code ui-apply}: each component's
 * options (type and default), a one-line example op, the agent verb it runs, and how it is removed.
 * {@code ui-catalog} and the MCP {@code ui_catalog} tool print it.
 */
final class UiCatalog {
    /** One option: JSON type, default (or null), and the agent flag it becomes (null = same name). */
    record Option(String type, Object fallback, String flag, String help) {
    }

    record Component(String name, String verb, boolean target, Map<String, Option> options, String example,
                     String removal, String summary) {
    }

    private static final Map<String, Component> COMPONENTS = new LinkedHashMap<>();

    static {
        Option text = new Option("string", null, "text", "text or HTML shown");
        Option theme = new Option("string", "auto", "theme", "auto, light or dark");
        Option block = new Option("boolean", false, "block", "true intercepts clicks on the target");
        add("overlay", "overlay", true, "callout beside the target with a ring on it",
                "{\"c\":\"overlay\",\"t\":\"#save\",\"text\":\"Click Save\"}", "overlay-remove --id",
                opt("text", new Option("string", null, "text", "HTML shown (required)")),
                opt("at", new Option("string", "auto", "at", "auto, top, bottom, left, right or over")), opt("theme", theme), opt("block", block));
        add("label", "label", true, "short label above the target",
                "{\"c\":\"label\",\"t\":\"#email\",\"text\":\"Work email\"}", "unlabel --id", opt("text", text), opt("block", block));
        add("border", "border", true, "outline around the target",
                "{\"c\":\"border\",\"t\":\"#plan\",\"color\":\"#2563eb\"}", "unborder --id",
                opt("color", new Option("string", null, "color", "CSS color")), opt("text", text), opt("block", block));
        add("arrow", "arrow", true, "arrow pointing at the target",
                "{\"c\":\"arrow\",\"t\":\"#save\",\"direction\":\"left\"}", "unarrow --id",
                opt("direction", new Option("string", "left", "direction", "up, down, left or right")), opt("block", block));
        add("step-badge", "step-badge", true, "numbered badge left of the target",
                "{\"c\":\"step-badge\",\"t\":\"#email\",\"number\":1}", "unstep-badge --id",
                opt("number", new Option("integer", 1, "number", "badge number")), opt("text", text), opt("block", block));
        add("magnifier", "magnifier", true, "zoomed copy beside the target",
                "{\"c\":\"magnifier\",\"t\":\"#terms\",\"zoom\":2}", "unmagnifier --id",
                opt("zoom", new Option("number", 2, "zoom", "1 to 8")), opt("text", text), opt("block", block));
        add("spotlight", "spotlight", true, "dims the page except the target",
                "{\"c\":\"spotlight\",\"t\":\"#save\",\"dim\":0.5}", "unspotlight --id",
                opt("dim", new Option("number", 0.5, "dim", "0 to 1")), opt("block", block));
        add("panel", "panel-open", false, "chat panel in the page",
                "{\"c\":\"panel\",\"tab\":\"steps\"}", "panel-close",
                opt("x", new Option("integer", null, "x", "left px")), opt("y", new Option("integer", null, "y", "top px")),
                opt("w", new Option("integer", 360, "width", "width px")), opt("h", new Option("integer", 420, "height", "height px")),
                opt("tab", new Option("string", "chat", "tab", "chat or steps")), opt("title", new Option("string", "Agent", "title", "header")),
                opt("min", new Option("boolean", false, "minimized", "start folded")), opt("theme", theme));
        add("panel-close", "panel-close", false, "closes the panel", "{\"c\":\"panel-close\"}", "");
        add("guide", "guide", false, "numbered steps that advance as the person acts",
                "{\"c\":\"guide\",\"steps\":[{\"t\":\"#email\",\"say\":\"Type your email\",\"do\":\"input\"}]}", "guide-done",
                opt("steps", new Option("array", null, "steps", "[{t, say, do: click|input|select|check|any, capture, sensitive}] (required)")),
                opt("id", new Option("string", "g1", "id", "guide id")), opt("scroll", new Option("boolean", false, "scroll", "scroll each step into view")));
        add("guide-next", "guide-next", false, "moves a guide forward", "{\"c\":\"guide-next\"}", "",
                opt("id", new Option("string", "g1", "id", "guide id")));
        add("guide-back", "guide-back", false, "moves a guide back", "{\"c\":\"guide-back\"}", "",
                opt("id", new Option("string", "g1", "id", "guide id")));
        add("guide-done", "guide-done", false, "closes a guide", "{\"c\":\"guide-done\"}", "",
                opt("id", new Option("string", "g1", "id", "guide id")));
        add("status", "status", false, "persistent waiting banner",
                "{\"c\":\"status\",\"text\":\"Waiting\"}", "",
                opt("text", new Option("string", null, "text", "banner text (required)")));
        add("pin", "pin", false, "comment pinned on the page",
                "{\"c\":\"pin\",\"text\":\"Note\"}", "",
                opt("text", new Option("string", null, "text", "comment (required)")));
        add("toast", "toast", false, "transient message",
                "{\"c\":\"toast\",\"text\":\"Saved\"}", "",
                opt("text", new Option("string", null, "text", "message (required)")));
        add("checklist", "checklist", false, "checklist of items",
                "{\"c\":\"checklist\",\"items\":\"A|B\"}", "",
                opt("items", new Option("string", null, "items", "items separated by | (required)")));
        add("ask", "ask", true, "question card; the answer arrives in ui-poll",
                "{\"c\":\"ask\",\"say\":\"Use the Pro plan?\",\"choices\":[\"Yes\",\"No\"]}", "",
                opt("say", new Option("string", null, "say", "question (required)")),
                opt("choices", new Option("array", null, "choices", "up to 6 answers; none = free text")),
                opt("sensitive", new Option("boolean", false, "sensitive", "never report the typed value")),
                opt("id", new Option("string", "q1", "id", "question id")));
        add("chat", "chat-send", false, "agent message in the panel and popup",
                "{\"c\":\"chat\",\"text\":\"Step 2 is the password\"}", "", opt("text", new Option("string", null, "text", "message (required)")));
        add("remove", "", false, "removes one placement by id",
                "{\"c\":\"remove\",\"id\":\"pkb-3\",\"of\":\"label\"}", "",
                opt("id", new Option("string", null, "id", "placement id (required)")),
                opt("of", new Option("string", "overlay", "of", "component it was: overlay, label, border, arrow, step-badge, magnifier or spotlight")));
        add("clear", "html-cleanup", false, "removes every placement, guide, and the saved state. The panel stays open", "{\"c\":\"clear\"}", "");
    }

    private UiCatalog() {
    }

    private static Object[] opt(String name, Option option) {
        return new Object[]{name, option};
    }

    private static void add(String name, String verb, boolean target, String summary, String example, String removal, Object[]... options) {
        Map<String, Option> map = new LinkedHashMap<>();
        for (Object[] pair : options) {
            if (pair.length == 2) map.put((String) pair[0], (Option) pair[1]);
        }
        COMPONENTS.put(name, new Component(name, verb, target, java.util.Collections.unmodifiableMap(map), example, removal, summary));
    }

    static Map<String, Component> components() {
        return java.util.Collections.unmodifiableMap(COMPONENTS);
    }

    static Component component(String name) {
        return name == null ? null : COMPONENTS.get(name);
    }

    /** Verbs that ui-apply covers, so every one-off verb maps to a component. */
    static List<String> coveredVerbs() {
        return List.of("overlay", "label", "border", "arrow", "step-badge", "magnifier", "spotlight", "panel-open", "panel-close",
                "guide", "guide-next", "guide-back", "guide-done", "ask", "chat-send", "html-cleanup",
                "overlay-remove", "unlabel", "unborder", "unarrow", "unstep-badge", "unmagnifier", "unspotlight",
                "status", "pin", "toast", "checklist");
    }

    /** Compact JSON-ready view; {@code only} limits it to one component. */
    static Map<String, Object> describe(String only) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Component component : COMPONENTS.values()) {
            if (only != null && !only.isBlank() && !component.name().equals(only)) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("does", component.summary());
            if (component.target()) entry.put("t", "target: id, h:<handle>, css, text:<words>, role:<role>[name=..], x:<xpath>");
            Map<String, Object> options = new LinkedHashMap<>();
            component.options().forEach((name, option) -> options.put(name,
                    option.type() + (option.fallback() == null ? "" : "=" + option.fallback()) + "; " + option.help()));
            if (!options.isEmpty()) entry.put("opts", options);
            entry.put("eg", component.example());
            if (!component.removal().isBlank()) entry.put("undo", component.removal());
            out.put(component.name(), entry);
        }
        return out;
    }
}
