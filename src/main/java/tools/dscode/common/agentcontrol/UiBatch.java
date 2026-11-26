package tools.dscode.common.agentcontrol;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code ui-apply}: validates a batch of page UI ops against {@link UiCatalog}, resolves short
 * targets, and runs each through the same agent verbs, saving the page state once at the end.
 * {@code ui-poll} merges overlay events and chat into one long-poll reply.
 */
final class UiBatch {
    static final int MAX_OPS = 50;
    /** Compact poll drops these. Typed values stay in the recording file, not the reply. */
    private static final Set<String> NOISE = Set.of(
            "at", "codes", "sentence", "runId", "channel",
            "bounds", "target", "value", "oldValue", "eid", "sensitive", "drop");

    private UiBatch() {
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> parse(Path project, String raw) throws Exception {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("ui-apply requires --ops=<json array> or --ops=@file.json");
        String text = raw.trim();
        if (text.startsWith("@")) {
            Path file = Path.of(text.substring(1));
            if (!file.isAbsolute() && project != null) file = project.resolve(file);
            text = Files.readString(file);
        }
        Object parsed;
        try {
            parsed = AgentStore.JSON.readValue(text, Object.class);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("ui-apply --ops is not valid JSON: " + failure.getMessage().lines().findFirst().orElse(""));
        }
        if (parsed instanceof Map<?, ?> single) parsed = List.of(single);
        if (!(parsed instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException("ui-apply --ops must be a non-empty JSON array");
        if (list.size() > MAX_OPS) throw new IllegalArgumentException("ui-apply --ops holds at most " + MAX_OPS + " ops");
        List<Map<String, Object>> ops = new ArrayList<>();
        int n = 0;
        for (Object item : list) {
            n++;
            if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException("ui-apply op " + n + " must be an object like {\"c\":\"label\",\"t\":\"#email\",\"text\":\"Email\"}");
            validate(n, (Map<String, Object>) map);
            ops.add((Map<String, Object>) map);
        }
        return ops;
    }

    static void validate(int n, Map<String, Object> op) {
        Object name = op.get("c");
        UiCatalog.Component component = UiCatalog.component(name == null ? null : String.valueOf(name));
        if (component == null) {
            throw new IllegalArgumentException("ui-apply op " + n + ": unknown component " + name + "; see ui-catalog for "
                    + String.join(", ", UiCatalog.components().keySet()));
        }
        for (String key : op.keySet()) {
            if ("c".equals(key)) continue;
            if ("t".equals(key)) {
                if (!component.target()) throw new IllegalArgumentException("ui-apply op " + n + ": " + component.name() + " takes no t");
                continue;
            }
            if (!component.options().containsKey(key)) {
                throw new IllegalArgumentException("ui-apply op " + n + ": " + component.name() + " has no option " + key
                        + (component.options().isEmpty() ? "" : "; options: " + String.join(", ", component.options().keySet())));
            }
        }
        component.options().forEach((key, option) -> {
            if (option.help().contains("(required)") && blank(op.get(key))) {
                throw new IllegalArgumentException("ui-apply op " + n + ": " + component.name() + " needs " + key);
            }
        });
        if (component.target() && !"ask".equals(component.name()) && blank(op.get("t"))) {
            throw new IllegalArgumentException("ui-apply op " + n + ": " + component.name() + " needs t (target)");
        }
        if ("guide".equals(component.name())) {
            try {
                AgentControl.guideSteps(null, AgentStore.JSON.writeValueAsString(op.get("steps")));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("ui-apply op " + n + ": " + failure.getMessage());
            } catch (Exception failure) {
                throw new IllegalArgumentException("ui-apply op " + n + ": steps " + failure.getMessage());
            }
        }
    }

    private static boolean blank(Object value) {
        return value == null || String.valueOf(value).isBlank() || (value instanceof List<?> list && list.isEmpty());
    }

    /** Agent flags for one op; null target handle means the target was not found. */
    static Map<String, String> flags(Map<String, Object> op, String handle) throws Exception {
        UiCatalog.Component component = UiCatalog.component(String.valueOf(op.get("c")));
        Map<String, String> flags = new LinkedHashMap<>();
        for (Map.Entry<String, UiCatalog.Option> entry : component.options().entrySet()) {
            Object value = op.get(entry.getKey());
            if (value == null) continue;
            String flag = entry.getValue().flag();
            if ("steps".equals(entry.getKey()) || ("choices".equals(entry.getKey()) && value instanceof List<?>)) {
                flags.put(flag, AgentStore.JSON.writeValueAsString(value));
            } else if (value instanceof List<?> list) flags.put(flag, String.join(",", list.stream().map(String::valueOf).toList()));
            else flags.put(flag, String.valueOf(value));
        }
        if (handle != null) flags.put("handle", handle);
        if (component.target() && !"ask".equals(component.name())) flags.put("target", "page");
        if ("panel".equals(component.name())) flags.put("target", "page");
        return flags;
    }

    /** A short target becomes a handle: a remembered handle as is, anything else found by the page runtime. */
    static String resolve(String target) throws Exception {
        if (target == null || target.isBlank()) return null;
        String t = target.trim();
        if (t.startsWith("h:")) t = t.substring(2);
        String placed = tools.dscode.common.agentfast.TargetVerb.placementHandle(t);
        if (placed != null && !placed.isBlank()) {
            if (PageSurfaces.rememberedPoint(placed) != null || PageSurfaces.rankedHandle(placed)) return placed;
            t = placed;
        } else if (t.startsWith("ref:")) {
            String selector = tools.dscode.common.agentfast.TargetVerb.anchorSelector(t);
            if (selector == null || selector.isBlank()) return null;
            t = selector;
        }
        if (PageSurfaces.rememberedPoint(t) != null) return t;
        String raw = PageSurfaces.runtimeCall("resolve", Map.of("t", t), true);
        if (raw == null || "null".equals(raw)) return null;
        Map<?, ?> box = AgentStore.JSON.readValue(raw, Map.class);
        String handle = String.valueOf(box.get("h"));
        PageSurfaces.rememberBounds(handle, num(box.get("x")), num(box.get("y")), num(box.get("w")), num(box.get("hh")));
        return handle;
    }

    private static double num(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    /** Visibility and overlap stay on a direct placement verb. A batch row carries them only with --verbose. */
    private static void copyPlacement(Map<String, Object> row, Map<String, Object> body, boolean verbose) {
        if (!verbose || row == null || body == null) return;
        for (String key : List.of("visible", "hidden", "hiddenReason", "overlaps", "lost")) {
            if (body.containsKey(key)) row.put(key, body.get(key));
        }
    }

    static AgentControl.Result apply(Path project, String runId, Map<String, String> args, String agentId) throws Exception {
        List<Map<String, Object>> ops = parse(project, args.get("ops"));
        boolean verbose = "true".equalsIgnoreCase(String.valueOf(args.get("verbose")));
        boolean needsPage = ops.stream().anyMatch(op -> !"chat".equals(op.get("c")));
        if (needsPage && !PageSurfaces.hasLivePage()) return AgentControl.failure(1, "ui-apply needs a live browser page; run launch first");
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean allOk = true;
        boolean cleared = false;
        int n = 0;
        for (Map<String, Object> op : ops) {
            n++;
            String name = String.valueOf(op.get("c"));
            UiCatalog.Component component = UiCatalog.component(name);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("i", n);
            row.put("c", name);
            String handle = null;
            if (component.target() && op.get("t") != null) {
                handle = resolve(String.valueOf(op.get("t")));
                if (handle == null) {
                    row.put("miss", String.valueOf(op.get("t")));
                    rows.add(row);
                    allOk = false;
                    continue;
                }
            }
            String verb = component.verb();
            Map<String, String> flags = flags(op, handle);
            if ("remove".equals(name)) {
                String of = flags.getOrDefault("of", "overlay");
                verb = "overlay".equals(of) ? "overlay-remove" : "un" + of;
                flags.remove("of");
            }
            AgentControl.Result result = AgentControl.dispatch(project, runId, verb, flags, List.of(), agentId);
            if ("clear".equals(name) && result.exit() == 0) {
                UiState.clear(project, runId);
                cleared = true;
            }
            if (result.exit() == 0) {
                row.put("ok", true);
                Object id = result.body().get("id");
                if (id != null) row.put("id", id);
                if (result.body().get("miss") instanceof List<?> miss && !miss.isEmpty()) row.put("miss", miss);
            } else {
                allOk = false;
                row.put("ok", false);
                row.put("error", String.valueOf(result.body().getOrDefault("error", "failed")));
            }
            copyPlacement(row, result.body(), verbose);
            if (verbose) row.put("body", result.body());
            rows.add(row);
        }
        if (!cleared) UiState.sync(project, runId, args);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", allOk);
        body.put("ops", rows);
        return new AgentControl.Result(allOk ? 0 : 1, body);
    }

    static AgentControl.Result poll(Path project, String runId, Map<String, String> args, String agentId) throws Exception {
        long waitMillis = 0;
        if (args.get("wait") != null && !args.get("wait").isBlank()) {
            try {
                waitMillis = tools.dscode.control.protocol.LiveSessionAttach.declaredWaitMs("ui-poll", args);
            } catch (NumberFormatException failure) {
                return AgentControl.failure(2, "ui-poll --wait is seconds, up to 120");
            }
        }
        boolean cursor = args.get("cursor") != null && !args.get("cursor").isBlank();
        String after;
        if (args.get("after") != null && !args.get("after").isBlank()) {
            after = args.get("after").trim();
        } else if (cursor) {
            after = "0";
        } else {
            int stored = 0;
            try {
                stored = PageSurfaces.storedInputCount(project, runId);
            } catch (RuntimeException failure) {
                if (!pageGone(failure.getMessage())) throw failure;
            }
            after = Integer.toString(stored);
        }
        String chatAfter = args.get("chat-after");
        String wantId = args.get("id");
        boolean wantAnswer = wantId != null && !wantId.isBlank();
        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        while (true) {
            long wakeSeen = tools.dscode.common.agentfast.PushWake.generation();
            Map<String, String> eventArgs = new LinkedHashMap<>();
            eventArgs.put("after", after);
            AgentControl.Result events;
            try {
                events = AgentControl.dispatch(project, runId, "overlay-poll", eventArgs, List.of(), agentId);
            } catch (RuntimeException failure) {
                if (!pageGone(failure.getMessage())) throw failure;
                events = emptyOverlay();
            }
            if (events.exit() != 0) {
                Object error = events.body().get("error");
                if (!pageGone(error == null ? "" : String.valueOf(error))) return events;
                events = emptyOverlay();
            }
            Map<String, String> chatArgs = new LinkedHashMap<>();
            chatArgs.put("peek", "true");
            chatArgs.put("consume-events", "true");
            if (chatAfter != null && !chatAfter.isBlank()) chatArgs.put("after", chatAfter);
            else {
                String stored = ChatCursor.after(project, runId, args.getOrDefault("channel", "run"));
                chatArgs.put("after", stored == null || stored.isBlank() ? "0" : stored);
            }
            chatArgs.put("channel", args.getOrDefault("channel", "run"));
            AgentControl.Result chat = AgentControl.dispatch(project, runId, "chat-poll", chatArgs, List.of(), agentId);
            List<Map<String, Object>> eventRows = compact(events.body().get("events"));
            List<Map<String, Object>> chatRows = compact(chat.exit() == 0 ? chat.body().get("messages") : null);
            chatRows.removeIf(row -> "agent".equals(row.get("source")));
            chatRows.removeIf(UiBatch::overlayEcho);
            List<Map<String, String>> agentEvents = eventRows(chat.exit() == 0 ? chat.body().get("agentEvents") : null);
            String matched = wantAnswer ? choiceFor(eventRows, wantId) : null;
            if (matched == null && wantAnswer) {
                Map<String, String> stored = AnswerRecovery.find(project, runId, 0L, wantId, List.of());
                if (stored != null && stored.get("value") != null && !stored.get("value").isBlank()) {
                    matched = stored.get("value");
                }
            }
            boolean due = System.nanoTime() >= deadline;
            boolean ready = wantAnswer
                    ? matched != null || due
                    : !eventRows.isEmpty() || !chatRows.isEmpty() || !agentEvents.isEmpty() || due;
            if (ready) {
                UiState.sync(project, runId, args);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("ok", true);
                body.put("events", eventRows);
                if (!chatRows.isEmpty()) body.put("chat", chatRows);
                if (!agentEvents.isEmpty()) body.put("agentEvents", agentEvents);
                if (matched != null) {
                    body.put("id", wantId);
                    body.put("value", matched);
                    body.put("choice", matched);
                    body.put("applied", true);
                }
                attachAnswer(project, args, body);
                body.put("next", events.body().get("next"));
                List<?> raw = chat.exit() == 0 && chat.body().get("messages") instanceof List<?> list ? list : List.of();
                if (!raw.isEmpty() && raw.get(raw.size() - 1) instanceof Map<?, ?> last && last.get("id") != null) {
                    body.put("chatNext", String.valueOf(last.get("id")));
                } else if (chatAfter != null && !chatAfter.isBlank()) {
                    body.put("chatNext", chatAfter);
                }
                return new AgentControl.Result(0, body);
            }
            tools.dscode.common.agentfast.PushWake.await(wakeSeen, 250, deadline);
        }
    }

    /** A stored typed card. {@code type} and {@code value} stay on the poll body. */
    private static void attachAnswer(Path project, Map<String, String> args, Map<String, Object> body) {
        Map<String, Object> card = AskCards.answered(project, args == null ? null : args.get("session"));
        if (card == null) return;
        if (body.get("type") == null) body.put("type", card.get("type"));
        if (body.get("value") == null) body.put("value", card.get("value"));
        if (body.get("id") == null) body.put("id", card.get("id"));
    }

    /** The clicked label for this question id, or null when this batch is a different event. */
    private static String choiceFor(List<Map<String, Object>> rows, String id) {
        if (rows == null || id == null || id.isBlank()) return null;
        for (Map<String, Object> row : rows) {
            if (row == null || !id.equals(String.valueOf(row.get("id")))) continue;
            String type = String.valueOf(row.get("type"));
            String event = String.valueOf(row.get("event"));
            if (!"answer".equals(type) && !"answer".equals(event)) continue;
            Object choice = row.get("choice");
            if (choice == null) choice = row.get("value");
            if (choice == null || String.valueOf(choice).isBlank()) continue;
            return String.valueOf(choice);
        }
        return null;
    }

    /** Overlay drain fails once Chrome's page socket is gone. The poll still returns agent events. */
    private static boolean pageGone(String message) {
        if (message == null || message.isBlank()) return false;
        return message.contains("cdp connection is closed")
                || message.contains("no live browser")
                || message.contains("tab enumeration failed")
                || message.contains("input poll");
    }

    private static AgentControl.Result emptyOverlay() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("count", 0);
        body.put("events", List.of());
        body.put("next", 0);
        return new AgentControl.Result(0, body);
    }

    private static List<Map<String, Object>> compact(Object raw) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return rows;
        for (Object item : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (item instanceof Map<?, ?> map) {
                copyCompact(row, map);
            } else if (item != null) {
                Map<?, ?> converted = AgentStore.JSON.convertValue(item, Map.class);
                copyCompact(row, converted);
            }
            rows.add(row);
        }
        return rows;
    }

    /** The system line ChatLog writes for each overlay event. The event itself is already in the poll. */
    private static boolean overlayEcho(Map<String, Object> row) {
        if (row == null || !"system".equals(row.get("source"))) return false;
        Object text = row.get("text");
        return text != null && String.valueOf(text).startsWith("overlay input");
    }

    /** Typed field text stays in the recording file. A compact poll keeps the identity an agent acts on. */
    private static void copyCompact(Map<String, Object> row, Map<?, ?> map) {
        boolean overlay = overlayRow(map);
        map.forEach((key, value) -> {
            if (key == null || value == null) return;
            String name = String.valueOf(key);
            if (NOISE.contains(name)) return;
            if (overlay && POLL_BULK.contains(name)) return;
            if (overlay && "text".equals(name)) return;
            row.put(name, value);
        });
    }

    private static final Set<String> POLL_BULK = Set.of(
            "binding", "sessionId", "frame", "source", "kind", "event", "filled");

    private static boolean overlayRow(Map<?, ?> map) {
        if (map.containsKey("event") || map.containsKey("kind") || map.containsKey("g") || map.containsKey("how")) return true;
        Object type = map.get("type");
        if (type == null) return false;
        return switch (String.valueOf(type)) {
            case "step", "guide", "input", "change", "click", "check", "select", "key" -> true;
            default -> false;
        };
    }

    private static List<Map<String, String>> eventRows(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, String>> rows = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, String> row = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key == null || value == null) return;
                String name = String.valueOf(key);
                if (NOISE.contains(name) || "text".equals(name)) return;
                row.put(name, String.valueOf(value));
            });
            rows.add(row);
        }
        return rows;
    }
}
