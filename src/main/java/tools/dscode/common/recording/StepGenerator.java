package tools.dscode.common.recording;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns interaction rows into dynamic-step phrases and a parameter schema.
 * A typed or selected value becomes {@code <name>}. A key stays literal.
 */
public final class StepGenerator {
    private StepGenerator() {
    }

    public record Script(
            List<String> steps,
            String gherkin,
            String phrase,
            List<Map<String, Object>> parameters,
            List<Map<String, String>> kept
    ) {
    }

    public static Script generate(List<Map<String, String>> rows, Map<String, Object> state) {
        List<Map<String, String>> prepared = prepare(rows, state);
        List<Integer> dropped = RecordingSession.dropped(state);
        List<String> literals = RecordingSession.literals(state);
        Map<String, String> renames = RecordingSession.renames(state);
        String context = state == null ? "" : String.valueOf(state.getOrDefault("context", ""));
        if ("null".equals(context)) context = "";
        List<String> steps = new ArrayList<>();
        List<Map<String, Object>> parameters = new ArrayList<>();
        List<Map<String, String>> kept = new ArrayList<>();
        Map<String, Integer> used = new LinkedHashMap<>();
        int index = 0;
        for (Map<String, String> row : prepared) {
            if (dropped.contains(index)) {
                index++;
                continue;
            }
            Phrase phrase = phrase(row, literals, renames, used, parameters);
            if (phrase == null) {
                index++;
                continue;
            }
            String text = withFrame(row, phrase.text);
            if (context != null && !context.isBlank() && steps.isEmpty()) {
                text = context.trim() + (context.trim().endsWith(",") ? " " : ", ") + stripComma(text);
                if (!text.startsWith(",")) text = ", " + text;
            }
            steps.add(text.startsWith(",") ? text : ", " + text);
            kept.add(row);
            index++;
        }
        return new Script(steps, gherkin(steps), chain(steps), parameters, kept);
    }

    /** Same parameters and kept rows, with editor phrases and gherkin in front. */
    public static Script presented(Script generated, List<String> phrases, String gherkin) {
        List<String> steps = phrases == null ? List.of() : List.copyOf(phrases);
        List<Map<String, Object>> parameters = generated == null || generated.parameters() == null
                ? List.of() : generated.parameters();
        List<Map<String, String>> kept = generated == null || generated.kept() == null
                ? List.of() : generated.kept();
        return new Script(steps, gherkin == null ? "" : gherkin, chain(steps), parameters, kept);
    }

    /** Substitutes {@code <name>} using the supplied values, then the recorded example. */
    public static List<String> apply(List<String> steps, List<Map<String, Object>> parameters, Map<String, String> values) {
        List<String> concrete = new ArrayList<>();
        if (steps == null) return concrete;
        for (String step : steps) {
            String text = step;
            if (parameters != null) {
                for (Map<String, Object> parameter : parameters) {
                    String name = String.valueOf(parameter.getOrDefault("name", ""));
                    if (name.isBlank()) continue;
                    String value = values != null && values.get(name) != null
                            ? values.get(name)
                            : String.valueOf(parameter.getOrDefault("example", ""));
                    if ("null".equals(value)) value = "";
                    text = text.replace("<" + name + ">", escapeStep(value));
                }
            }
            concrete.add(text);
        }
        return concrete;
    }

    private static List<Map<String, String>> prepare(List<Map<String, String>> rows, Map<String, Object> state) {
        List<Map<String, String>> raw = rows == null ? List.of() : rows;
        boolean paused = false;
        List<Map<String, String>> open = new ArrayList<>();
        for (Map<String, String> row : raw) {
            if (row == null) continue;
            if ("record".equalsIgnoreCase(row.get("type")) || "record".equalsIgnoreCase(row.get("event"))) {
                String action = InteractionLog.first(row.get("action"), "");
                if ("pause".equalsIgnoreCase(action)) paused = true;
                else if ("resume".equalsIgnoreCase(action)) paused = false;
                continue;
            }
            if (paused) continue;
            if (!InteractionLog.isInteraction(row) && !InteractionLog.KINDS.contains(
                    InteractionLog.first(row.get("type"), "").toLowerCase(Locale.ROOT))) {
                continue;
            }
            open.add(row);
        }
        List<Map<String, String>> coalesced = new ArrayList<>();
        Map<String, String> pending = null;
        java.util.Set<String> covered = formCoverage(open);
        for (Map<String, String> row : open) {
            String kind = kind(row);
            if (ownRow(row)) continue;
            if (("focus".equals(kind) || "hover".equals(kind)) && !noise(state)) {
                continue;
            }
            if (coveredByForm(kind, row, covered)) continue;
            if (("input".equals(kind) || "change".equals(kind)) && pending != null && sameTarget(pending, row)
                    && ("input".equals(kind(pending)) || "change".equals(kind(pending)))) {
                Map<String, String> merged = new LinkedHashMap<>(pending);
                if (row.get("value") != null) merged.put("value", row.get("value"));
                merged.put("type", kind);
                merged.put("kind", kind);
                pending = merged;
                continue;
            }
            if (pending != null) coalesced.add(pending);
            pending = "input".equals(kind) || "change".equals(kind) ? new LinkedHashMap<>(row) : null;
            if (pending == null) coalesced.add(row);
        }
        if (pending != null) coalesced.add(pending);
        if (state != null && "paused".equals(String.valueOf(state.get("status")))) {
            // A paused session still shows the steps captured before the pause.
        }
        return coalesced;
    }

    private record Phrase(String text) {
    }

    private static Phrase phrase(
            Map<String, String> row,
            List<String> literals,
            Map<String, String> renames,
            Map<String, Integer> used,
            List<Map<String, Object>> parameters
    ) {
        String kind = kind(row);
        String name = InteractionLog.first(row.get("name"), row.get("text"), row.get("query"), row.get("target"), "");
        String category = category(row, kind);
        String quotedName = quote(name);
        return switch (kind) {
            case "click" -> new Phrase(", click the " + named(quotedName, category));
            case "hover" -> new Phrase(", hover the " + named(quotedName, category));
            case "check" -> new Phrase(", ensure the " + named(quotedName, "Checkbox") + " is "
                    + ("false".equalsIgnoreCase(row.get("checked")) || "false".equalsIgnoreCase(row.get("value"))
                    ? "unchecked" : "checked"));
            case "select" -> selectPhrase(row, name, literals, renames, used, parameters);
            case "input", "change" -> enterPhrase(row, name, category, literals, renames, used, parameters);
            case "key" -> new Phrase(", press " + quote(InteractionLog.first(row.get("value"), row.get("name"), "Enter")));
            case "drag" -> dragPhrase(row, name);
            case "form" -> formPhrase(row, literals, renames, used, parameters);
            default -> null;
        };
    }

    private static Phrase selectPhrase(
            Map<String, String> row,
            String name,
            List<String> literals,
            Map<String, String> renames,
            Map<String, Integer> used,
            List<Map<String, Object>> parameters
    ) {
        String value = InteractionLog.first(row.get("value"), "");
        if (value.contains("|")) {
            StringBuilder chain = new StringBuilder();
            String[] parts = value.split("\\|");
            for (String part : parts) {
                if (part.isBlank()) continue;
                if (!chain.isEmpty()) chain.append(", ");
                chain.append("select ").append(token(row, name, part, "select", literals, renames, used, parameters))
                        .append(" in the ").append(quote(name)).append(" Dropdown");
            }
            if (chain.isEmpty()) return null;
            return new Phrase(", " + chain);
        }
        String token = token(row, name, value, "select", literals, renames, used, parameters);
        return new Phrase(", select " + token + " in the " + quote(name) + " Dropdown");
    }

    private static Phrase enterPhrase(
            Map<String, String> row,
            String name,
            String category,
            List<String> literals,
            Map<String, String> renames,
            Map<String, Integer> used,
            List<Map<String, Object>> parameters
    ) {
        if ("checkbox".equalsIgnoreCase(row.get("role")) || "Checkbox".equals(category)) {
            return new Phrase(", ensure the " + named(quote(name), "Checkbox") + " is "
                    + ("false".equalsIgnoreCase(row.get("checked")) || "false".equalsIgnoreCase(row.get("value"))
                    ? "unchecked" : "checked"));
        }
        String field = "Textarea".equals(category) ? "Textarea" : "Textbox";
        String token = token(row, name, InteractionLog.first(row.get("value"), ""), "text", literals, renames, used, parameters);
        return new Phrase(", enter " + token + " in the " + quote(name) + " " + field);
    }

    private static Phrase dragPhrase(Map<String, String> row, String name) {
        String drop = InteractionLog.first(row.get("drop"), row.get("value"), "");
        String source = name.isBlank() ? "Source" : name;
        if (drop.isBlank() || drop.equals(source)) return null;
        return new Phrase(", dragAndDrop the " + quote(source) + " Button the " + quote(drop) + " Button");
    }

    private static Phrase formPhrase(
            Map<String, String> row,
            List<String> literals,
            Map<String, String> renames,
            Map<String, Integer> used,
            List<Map<String, Object>> parameters
    ) {
        List<FormField> fields = formFields(row);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            FormField field = fields.get(i);
            String piece = switch (field.kind) {
                case "select" -> {
                    String token = token(row, field.name, field.value, "text", literals, renames, used, parameters);
                    yield "select " + token + " in the " + quote(field.name) + " Dropdown";
                }
                case "checkbox", "radio" -> {
                    String category = "radio".equals(field.kind) ? "Radio Button" : "Checkbox";
                    boolean on = !"false".equalsIgnoreCase(field.value) && !"off".equalsIgnoreCase(field.value) && !field.value.isBlank();
                    yield "ensure the " + quote(field.name) + " " + category + " is " + (on ? "checked" : "unchecked");
                }
                default -> {
                    String token = token(row, field.name, field.value, "text", literals, renames, used, parameters);
                    yield "enter " + token + " in the " + quote(field.name) + " Textbox";
                }
            };
            if (i > 0) text.append(i == fields.size() - 1 ? ", and " : ", ");
            text.append(piece);
        }
        if (text.isEmpty()) return null;
        return new Phrase(", " + text);
    }

    private record FormField(String kind, String name, String value) {
    }

    private static List<FormField> formFields(Map<String, String> row) {
        String raw = InteractionLog.first(row.get("fields"), "");
        List<FormField> fields = new ArrayList<>();
        if (raw.isBlank()) return fields;
        for (String part : raw.split("\\|")) {
            if (part.isBlank()) continue;
            String kind = "text";
            String body = part.trim();
            int colon = body.indexOf(':');
            int eq = body.indexOf('=');
            if (colon > 0 && (eq < 0 || colon < eq)) {
                String prefix = body.substring(0, colon).toLowerCase(Locale.ROOT);
                if (prefix.equals("text") || prefix.equals("select") || prefix.equals("checkbox") || prefix.equals("radio")) {
                    kind = prefix;
                    body = body.substring(colon + 1);
                }
            }
            eq = body.indexOf('=');
            if (eq <= 0) continue;
            fields.add(new FormField(kind, body.substring(0, eq).trim(), body.substring(eq + 1)));
        }
        return fields;
    }

    private static java.util.Set<String> formCoverage(List<Map<String, String>> rows) {
        java.util.Set<String> covered = new java.util.LinkedHashSet<>();
        for (Map<String, String> row : rows) {
            if (!"form".equals(kind(row))) continue;
            for (FormField field : formFields(row)) {
                if (!field.name.isBlank()) covered.add(field.name);
            }
        }
        return covered;
    }

    private static boolean coveredByForm(String kind, Map<String, String> row, java.util.Set<String> covered) {
        if (covered.isEmpty()) return false;
        if (!"input".equals(kind) && !"change".equals(kind) && !"select".equals(kind) && !"check".equals(kind)) return false;
        String name = InteractionLog.first(row.get("name"), "");
        String id = InteractionLog.first(row.get("id"), "");
        String target = InteractionLog.first(row.get("target"), "");
        return covered.contains(name) || covered.contains(id) || covered.contains(target);
    }

    private static String token(
            Map<String, String> row,
            String label,
            String value,
            String type,
            List<String> literals,
            Map<String, String> renames,
            Map<String, Integer> used,
            List<Map<String, Object>> parameters
    ) {
        if ("true".equalsIgnoreCase(row.get("sensitive"))) {
            String name = unique("secret", used);
            remember(parameters, name, "secret", "", "Sensitive value. Supply it at replay.");
            return quote("<" + name + ">");
        }
        String base = paramBase(label.isBlank() ? type : label);
        String renamed = renames.getOrDefault(base, base);
        String name = unique(renamed, used);
        boolean literal = literals.contains(base) || literals.contains(name) || literals.contains(renamed);
        remember(parameters, name, type, value, label);
        if (literal) return quote(value);
        return quote("<" + name + ">");
    }

    private static void remember(List<Map<String, Object>> parameters, String name, String type, String example, String description) {
        for (Map<String, Object> parameter : parameters) {
            if (name.equals(parameter.get("name"))) return;
        }
        Map<String, Object> parameter = new LinkedHashMap<>();
        parameter.put("name", name);
        parameter.put("type", type);
        parameter.put("example", example == null ? "" : example);
        parameter.put("description", description == null ? "" : description);
        parameter.put("literal", false);
        parameters.add(parameter);
    }

    private static String unique(String base, Map<String, Integer> used) {
        String name = base == null || base.isBlank() ? "value" : base;
        int count = used.merge(name, 1, Integer::sum);
        if (count == 1) return name;
        String suffixed = name + count;
        used.put(suffixed, 1);
        return suffixed;
    }

    static String paramBase(String label) {
        String[] parts = label.trim().split("[^\\p{L}\\p{N}]+");
        StringBuilder name = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (name.isEmpty()) {
                name.append(part.substring(0, 1).toLowerCase(Locale.ROOT));
                if (part.length() > 1) name.append(part.substring(1));
            } else {
                name.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
                if (part.length() > 1) name.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        String text = name.isEmpty() ? "value" : name.toString();
        if (!Character.isLetter(text.charAt(0))) text = "p" + text;
        return text;
    }

    private static String withFrame(Map<String, String> row, String text) {
        String frame = InteractionLog.first(row.get("frame"), "");
        if (frame.isBlank() || "top".equalsIgnoreCase(frame) || "main".equalsIgnoreCase(frame)
                || "desktop".equalsIgnoreCase(frame) || "null".equals(frame)) {
            return text;
        }
        String body = text == null ? "" : text.trim();
        if (body.startsWith(",")) body = body.substring(1).trim();
        return ", in the " + quote(frame) + " IFrame, " + body;
    }

    private static String category(Map<String, String> row, String kind) {
        String given = row.get("category");
        if (given != null && !given.isBlank()) return given;
        String role = InteractionLog.first(row.get("role"), "").toLowerCase(Locale.ROOT);
        if ("select".equals(kind) || "combobox".equals(role) || "listbox".equals(role)) return "Dropdown";
        if ("checkbox".equals(role)) return "Checkbox";
        if ("radio".equals(role)) return "Radio Button";
        if ("link".equals(role) || "a".equals(role)) return "Link";
        if ("textarea".equals(role)) return "Textarea";
        if ("textbox".equals(role) || "text".equals(role) || "input".equals(kind) || "change".equals(kind)) {
            return "textarea".equals(role) ? "Textarea" : "Textbox";
        }
        if ("button".equals(role) || "submit".equals(role) || role.isBlank()) return "Button";
        if ("tab".equals(role)) return "Tab";
        return Character.toUpperCase(role.charAt(0)) + role.substring(1);
    }

    private static String named(String quotedName, String category) {
        if (quotedName == null || "\"\"".equals(quotedName)) return category;
        return quotedName + " " + category;
    }

    static String quote(String text) {
        String value = text == null ? "" : text;
        if (!value.contains("\"")) return "\"" + value + "\"";
        if (!value.contains("'")) return "'" + value + "'";
        return "`" + value.replace("`", "") + "`";
    }

    private static String escapeStep(String value) {
        return value == null ? "" : value;
    }

    private static String kind(Map<String, String> row) {
        return InteractionLog.first(row.get("kind"), row.get("type"), "click").toLowerCase(Locale.ROOT);
    }

    private static boolean noise(Map<String, Object> state) {
        if (state == null) return false;
        Object value = state.get("noise");
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static boolean ownRow(Map<String, String> row) {
        if (row == null) return false;
        if ("true".equalsIgnoreCase(row.get("own"))) return true;
        String id = InteractionLog.first(row.get("id"), "");
        return id.startsWith("pkb-") || id.startsWith("ask-");
    }

    private static boolean sameTarget(Map<String, String> left, Map<String, String> right) {
        String leftId = InteractionLog.first(left.get("id"), "");
        String rightId = InteractionLog.first(right.get("id"), "");
        if (!leftId.isBlank() && leftId.equals(rightId)) return true;
        if (!leftId.isBlank() || !rightId.isBlank()) return false;
        String a = InteractionLog.first(left.get("name"), left.get("target"));
        String b = InteractionLog.first(right.get("name"), right.get("target"));
        return !a.isBlank() && a.equals(b);
    }

    static String gherkin(List<String> steps) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < steps.size(); index++) {
            if (index > 0) text.append('\n');
            text.append(index == 0 ? "Then " : "And ").append(steps.get(index));
        }
        return text.toString();
    }

    private static String chain(List<String> steps) {
        if (steps.isEmpty()) return "";
        StringBuilder text = new StringBuilder("Then ");
        for (int index = 0; index < steps.size(); index++) {
            if (index > 0) text.append(", ");
            text.append(stripComma(steps.get(index)));
        }
        return text.toString();
    }

    private static String stripComma(String step) {
        String text = step == null ? "" : step.trim();
        if (text.startsWith(",")) text = text.substring(1).trim();
        return text;
    }
}
