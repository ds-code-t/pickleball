package tools.dscode.common.recording;

import tools.dscode.common.agentcontrol.AgentControl;
import tools.dscode.common.agentfast.PushChannel;
import tools.dscode.control.protocol.AgentEvents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** CLI and MCP entry for collaborative recording. */
public final class RecordControl {
    private RecordControl() {
    }

    public record Outcome(int exit, Map<String, Object> body) {
    }

    public static Outcome handle(Path project, String runId, Map<String, String> args) {
        Map<String, String> flags = args == null ? Map.of() : args;
        try {
            absorb(project);
            if (truth(flags, "start")) return start(project, flags, runId);
            if (truth(flags, "stop")) return stop(project);
            if (truth(flags, "pause")) return pause(project);
            if (truth(flags, "resume")) return resume(project);
            if (truth(flags, "inject")) return inject(project, flags, runId);
            if (truth(flags, "rename")) return rename(project, flags);
            if (present(flags.get("literal"))) return literal(project, flags.get("literal"));
            if (truth(flags, "reject")) return reject(project, flags, true);
            if (truth(flags, "accept")) return reject(project, flags, false);
            if (truth(flags, "context")) return context(project, flags);
            if (truth(flags, "editor")) return editor(project);
            if (truth(flags, "edit")) return mutate(project, "edit", flags);
            if (truth(flags, "move")) return mutate(project, "move", flags);
            if (truth(flags, "nest")) return mutate(project, "nest", flags);
            if (truth(flags, "outdent")) return mutate(project, "outdent", flags);
            if (truth(flags, "add")) return mutate(project, "add", flags);
            if (truth(flags, "delete")) return mutate(project, "delete", flags);
            if (truth(flags, "popout")) return pop(project, flags, "window");
            if (truth(flags, "popin")) return pop(project, flags, "page");
            if (truth(flags, "save")) return save(project, flags);
            if (truth(flags, "load")) return load(project, flags);
            if (truth(flags, "list")) return list(project);
            if (truth(flags, "export")) return export(project, flags);
            if (truth(flags, "run")) return run(project, flags);
            if (truth(flags, "script")) return script(project, true);
            return status(project);
        } catch (IOException failure) {
            return fail(1, failure.getMessage() == null ? "record failed" : failure.getMessage());
        }
    }

    /** Records one agent act or form-fill when record mode is on. */
    public static void noteAgent(Path project, String runId, String op, Map<String, String> args, Map<String, Object> body) {
        if (project == null || !RecordingSession.recording(project)) return;
        Map<String, String> flags = args == null ? Map.of() : args;
        if ("form-fill".equals(op)) {
            String fields = flags.get("fields");
            if (fields == null) return;
            for (String part : fields.split("\\|")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                Map<String, String> row = baseAgent(runId, "input");
                row.put("name", part.substring(0, eq).trim());
                row.put("text", row.get("name"));
                row.put("value", part.substring(eq + 1));
                row.put("role", "textbox");
                row.put("target", row.get("name"));
                InteractionLog.accept(project, row);
            }
            return;
        }
        if (!"act".equals(op)) return;
        String kind = InteractionLog.first(flags.get("kind"), "click");
        String mapped = switch (kind.toLowerCase(Locale.ROOT)) {
            case "type" -> "input";
            case "press" -> "key";
            case "select" -> "select";
            case "hover" -> "hover";
            case "drag" -> "drag";
            default -> "click";
        };
        Map<String, String> row = baseAgent(runId, mapped);
        String name = InteractionLog.first(flags.get("query"), flags.get("name"), flags.get("ref"), "");
        row.put("name", name);
        row.put("text", name);
        row.put("target", name);
        if (flags.get("value") != null) row.put("value", flags.get("value"));
        if (flags.get("ref") != null) row.put("ref", flags.get("ref"));
        if (flags.get("id") != null) row.put("id", flags.get("id"));
        String surface = body != null && "desktop".equals(String.valueOf(body.get("kind"))) ? "desktop" : "browser";
        row.put("surface", surface);
        if ("desktop".equals(surface)) row.put("window", InteractionLog.first(flags.get("target"), "desktop"));
        Object frame = body == null ? null : body.get("frame");
        if (frame != null) row.put("frame", String.valueOf(frame));
        InteractionLog.accept(project, row);
    }

    private static Map<String, String> baseAgent(String runId, String kind) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("type", kind);
        row.put("kind", kind);
        row.put("source", "agent");
        if (runId != null && !runId.isBlank()) row.put("sessionId", runId);
        return row;
    }

    private static Outcome start(Path project, Map<String, String> flags, String runId) {
        String session = InteractionLog.first(flags.get("session"), runId, "");
        Map<String, Object> state = RecordingSession.start(project, session);
        arm(project);
        Map<String, Object> body = ok("recording");
        body.put("op", "record");
        body.put("status", state.get("status"));
        body.put("sessionId", state.get("sessionId"));
        body.put("cursor", state.get("cursor"));
        body.put("sentence", "recording");
        return new Outcome(0, body);
    }

    private static Outcome stop(Path project) {
        Map<String, Object> state = RecordingSession.stop(project);
        arm(project);
        StepGenerator.Script script = current(project);
        Map<String, Object> body = ok("recording stopped");
        body.put("op", "record");
        body.put("status", state.get("status"));
        body.put("count", script.steps().size());
        body.put("script", script.gherkin());
        body.put("sentence", "recording stopped");
        return new Outcome(0, body);
    }

    private static Outcome pause(Path project) {
        marker(project, "pause", null);
        Map<String, Object> state = RecordingSession.pause(project);
        arm(project);
        return stateBody(state, "paused");
    }

    private static Outcome resume(Path project) {
        marker(project, "resume", null);
        Map<String, Object> state = RecordingSession.resume(project);
        arm(project);
        return stateBody(state, "recording");
    }

    private static void marker(Path project, String action, String index) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("event", "record");
        row.put("type", "record");
        row.put("action", action);
        row.put("source", "human");
        if (index != null) row.put("index", index);
        String session = RecordingSession.sessionId(project);
        if (!session.isBlank()) row.put("sessionId", session);
        PushChannel.accept(project, row);
    }

    private static Outcome status(Path project) {
        Map<String, Object> state = RecordingSession.read(project);
        StepGenerator.Script script = current(project);
        Map<String, Object> body = ok(String.valueOf(state.get("status")));
        body.put("op", "record");
        body.put("status", state.get("status"));
        body.put("sessionId", state.getOrDefault("sessionId", ""));
        body.put("cursor", state.getOrDefault("cursor", 0));
        body.put("count", script.steps().size());
        body.put("sentence", String.valueOf(state.get("status")));
        return new Outcome(0, body);
    }

    private static Outcome script(Path project, boolean push) {
        Presented presented = presented(project);
        if (push) arm(project);
        StepGenerator.Script script = presented.script();
        Map<String, Object> body = ok(script.steps().isEmpty() ? "no steps recorded" : "script ready");
        body.put("op", "record");
        body.put("status", presented.state().get("status"));
        body.put("count", script.steps().size());
        body.put("steps", script.steps());
        body.put("editor", EditorDraft.maps(presented.rows()));
        body.put("panels", EditorDraft.panels(presented.state()));
        body.put("script", script.gherkin());
        body.put("phrase", script.phrase());
        body.put("parameters", script.parameters());
        body.put("sentence", script.steps().isEmpty() ? "no steps recorded" : "script ready");
        return new Outcome(0, body);
    }

    private static Outcome editor(Path project) {
        Outcome outcome = script(project, true);
        int count = outcome.body().get("count") instanceof Number number ? number.intValue() : 0;
        outcome.body().put("sentence", count == 0 ? "no steps recorded" : "editor ready");
        return outcome;
    }

    private static Outcome mutate(Path project, String action, Map<String, String> flags) {
        Map<String, Object> state = RecordingSession.read(project);
        try {
            EditorDraft.apply(state, generated(project, state), action, flags);
        } catch (IllegalArgumentException failure) {
            return fail(2, failure.getMessage() == null ? "editor update failed" : failure.getMessage());
        }
        RecordingSession.update(project, state);
        Outcome outcome = script(project, true);
        outcome.body().put("sentence", "editor updated");
        return outcome;
    }

    private static Outcome pop(Path project, Map<String, String> flags, String where) {
        Map<String, Object> state = RecordingSession.read(project);
        try {
            EditorDraft.place(state, flags.get("panel"), where);
        } catch (IllegalArgumentException failure) {
            return fail(2, failure.getMessage() == null ? "pop failed" : failure.getMessage());
        }
        RecordingSession.update(project, state);
        arm(project);
        String sentence = "window".equals(where) ? "popped out" : "popped in";
        Map<String, Object> body = ok(sentence);
        body.put("op", "record");
        body.put("panel", flags.get("panel"));
        body.put("where", "window".equals(where) ? "window" : "page");
        body.put("panels", EditorDraft.panels(state));
        body.put("sentence", sentence);
        return new Outcome(0, body);
    }

    private static Outcome inject(Path project, Map<String, String> flags, String runId) {
        if (!RecordingSession.recording(project)) return fail(1, "record --start first");
        Map<String, String> row = new LinkedHashMap<>();
        String kind = InteractionLog.first(flags.get("kind"), "click");
        row.put("type", kind);
        row.put("kind", kind);
        row.put("source", "agent");
        row.put("name", InteractionLog.first(flags.get("query"), flags.get("name"), flags.get("text"), ""));
        row.put("text", row.get("name"));
        row.put("target", row.get("name"));
        if (flags.get("value") != null) row.put("value", flags.get("value"));
        if (flags.get("role") != null) row.put("role", flags.get("role"));
        if (flags.get("id") != null) row.put("id", flags.get("id"));
        if (flags.get("ref") != null) row.put("ref", flags.get("ref"));
        if (flags.get("frame") != null) row.put("frame", flags.get("frame"));
        if (flags.get("window") != null) row.put("window", flags.get("window"));
        row.put("sessionId", InteractionLog.first(flags.get("session"), runId, RecordingSession.sessionId(project)));
        Map<String, String> stored = InteractionLog.accept(project, row);
        Map<String, Object> body = ok("action recorded");
        body.put("op", "record");
        body.put("event", stored);
        body.put("sentence", "action recorded");
        return new Outcome(0, body);
    }

    private static Outcome rename(Path project, Map<String, String> flags) {
        String from = flags.get("from");
        String to = flags.get("to");
        if (!present(from) || !present(to)) return fail(2, "record --rename needs --from and --to");
        Map<String, Object> state = RecordingSession.read(project);
        Map<String, String> names = RecordingSession.renames(state);
        names.put(from, to);
        state.put("renames", names);
        RecordingSession.update(project, state);
        return script(project, false);
    }

    private static Outcome literal(Path project, String name) {
        Map<String, Object> state = RecordingSession.read(project);
        List<String> literals = RecordingSession.literals(state);
        if (!literals.contains(name)) literals.add(name);
        state.put("literals", literals);
        RecordingSession.update(project, state);
        return script(project, false);
    }

    private static Outcome reject(Path project, Map<String, String> flags, boolean drop) {
        if (!present(flags.get("index"))) return fail(2, "record --reject needs --index");
        int index = RecordingSession.number(flags.get("index"));
        Map<String, Object> state = RecordingSession.read(project);
        List<Integer> dropped = RecordingSession.dropped(state);
        if (drop) {
            if (!dropped.contains(index)) dropped.add(index);
        } else {
            dropped.removeIf(item -> item == index);
        }
        state.put("dropped", dropped);
        RecordingSession.update(project, state);
        return script(project, false);
    }

    private static Outcome context(Path project, Map<String, String> flags) {
        String text = flags.get("text");
        if (!present(text)) return fail(2, "record --context needs --text");
        Map<String, Object> state = RecordingSession.read(project);
        state.put("context", text.trim());
        RecordingSession.update(project, state);
        return script(project, false);
    }

    private static Outcome save(Path project, Map<String, String> flags) throws IOException {
        String name = InteractionLog.first(flags.get("name"), "recorded");
        Presented presented = presented(project);
        Map<String, Object> saved = RoutineStore.save(
                project, name, presented.script(), EditorDraft.depths(presented.rows()));
        Map<String, Object> body = ok("routine saved");
        body.put("op", "record");
        body.put("name", saved.get("name"));
        body.put("path", saved.get("path"));
        body.put("count", presented.script().steps().size());
        body.put("parameters", presented.script().parameters());
        body.put("sentence", "routine saved");
        return new Outcome(0, body);
    }

    private static Outcome load(Path project, Map<String, String> flags) throws IOException {
        if (!present(flags.get("name"))) return fail(2, "record --load needs --name");
        Map<String, Object> routine = RoutineStore.load(project, flags.get("name"));
        List<String> loadedSteps = stringList(routine.get("steps"));
        List<Integer> loadedDepths = RoutineStore.depthList(routine);
        Map<String, Object> state = RecordingSession.read(project);
        List<String> hidden = new ArrayList<>();
        for (EditorDraft.Row row : EditorDraft.rows(generated(project, state), Map.of())) {
            if (row.key() != null) hidden.add(row.key());
        }
        EditorDraft.install(state, loadedSteps, loadedDepths);
        state.put("removed", hidden);
        RecordingSession.update(project, state);
        Presented presented = presented(project);
        arm(project);
        Map<String, Object> body = ok("routine loaded");
        body.put("op", "record");
        body.put("name", routine.get("name"));
        body.put("path", routine.get("path"));
        body.put("script", presented.script().gherkin());
        body.put("steps", loadedSteps);
        body.put("depths", loadedDepths);
        body.put("editor", EditorDraft.maps(presented.rows()));
        body.put("parameters", routine.get("parameters"));
        body.put("sentence", "routine loaded");
        return new Outcome(0, body);
    }

    private static Outcome list(Path project) throws IOException {
        List<String> names = RoutineStore.list(project);
        Map<String, Object> body = ok(names.isEmpty() ? "no routines" : "routines");
        body.put("op", "record");
        body.put("count", names.size());
        body.put("names", names);
        body.put("sentence", names.isEmpty() ? "no routines" : "routines");
        return new Outcome(0, body);
    }

    private static Outcome export(Path project, Map<String, String> flags) throws IOException {
        String name = flags.get("name");
        Map<String, Object> routine = present(name) ? RoutineStore.load(project, name) : liveRoutine(project, "recorded");
        String format = InteractionLog.first(flags.get("format"), "feature");
        if (!"feature".equalsIgnoreCase(format)) return fail(2, "record --export format is feature");
        String feature = RoutineStore.feature(routine);
        Path out = outPath(project, flags.get("out"), String.valueOf(routine.get("name")) + ".feature");
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, feature, StandardCharsets.UTF_8);
        Map<String, Object> body = ok("feature exported");
        body.put("op", "record");
        body.put("format", "feature");
        body.put("path", out.toAbsolutePath().normalize().toString());
        body.put("feature", feature);
        body.put("sentence", "feature exported");
        return new Outcome(0, body);
    }

    private static Outcome run(Path project, Map<String, String> flags) throws IOException {
        String name = flags.get("name");
        Map<String, Object> routine = present(name) ? RoutineStore.load(project, name) : liveRoutine(project, "recorded");
        @SuppressWarnings("unchecked")
        List<String> steps = routine.get("steps") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parameters = new ArrayList<>();
        if (routine.get("parameters") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> parameter = new LinkedHashMap<>();
                    map.forEach((key, value) -> {
                        if (key != null && value != null) parameter.put(String.valueOf(key), value);
                    });
                    parameters.add(parameter);
                }
            }
        }
        Map<String, String> values = params(flags.get("param"));
        List<Integer> depths = RoutineStore.depthList(routine);
        List<String> concrete = StepGenerator.apply(steps, parameters, values);
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < concrete.size(); index++) {
            lines.add(EditorDraft.featureLine(concrete, depths, index).strip());
        }
        Path dir = project.resolve(".pickleball/recordings/replay");
        Files.createDirectories(dir);
        String safe = RoutineStore.safe(String.valueOf(routine.getOrDefault("name", "recorded")));
        Path feature = dir.resolve(safe + ".feature");
        Path stepsFile = dir.resolve(safe + ".steps.json");
        Files.writeString(feature, RoutineStore.feature(withExamples(routine, values)), StandardCharsets.UTF_8);
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(RoutineStore.replaySteps(lines));
        Files.writeString(stepsFile, json, StandardCharsets.UTF_8);
        Map<String, Object> body = ok("parameters applied");
        body.put("op", "record");
        body.put("name", routine.get("name"));
        body.put("steps", lines);
        body.put("count", lines.size());
        body.put("feature", feature.toAbsolutePath().normalize().toString());
        body.put("file", stepsFile.toAbsolutePath().normalize().toString());
        body.put("applied", false);
        body.put("sentence", "parameters applied");
        return new Outcome(0, body);
    }

    static StepGenerator.Script current(Path project) {
        return presented(project).script();
    }

    private record Presented(StepGenerator.Script script, List<EditorDraft.Row> rows, Map<String, Object> state) {
    }

    private static Presented presented(Path project) {
        Map<String, Object> state = RecordingSession.read(project);
        StepGenerator.Script generated = generated(project, state);
        List<EditorDraft.Row> rows = EditorDraft.rows(generated, state);
        StepGenerator.Script script = EditorDraft.edited(state)
                ? StepGenerator.presented(generated, EditorDraft.phrases(rows), EditorDraft.gherkin(rows))
                : generated;
        return new Presented(script, rows, state);
    }

    private static StepGenerator.Script generated(Path project, Map<String, Object> state) {
        int from = RecordingSession.number(state == null ? null : state.get("cursor"));
        Object endValue = state == null ? null : state.get("endCursor");
        int end = endValue == null ? -1 : RecordingSession.number(endValue);
        if (endValue != null && end < 0) end = -1;
        List<Map<String, String>> rows = new ArrayList<>();
        List<Map<String, String>> all = AgentEvents.lines(project);
        for (int index = Math.max(0, from); index < all.size(); index++) {
            if (end >= 0 && index >= end) break;
            rows.add(all.get(index));
        }
        return StepGenerator.generate(rows, state);
    }

    private static Map<String, Object> liveRoutine(Path project, String name) {
        Presented presented = presented(project);
        Map<String, Object> routine = routineFrom(presented.script(), name);
        routine.put("depths", EditorDraft.depths(presented.rows()));
        return routine;
    }

    /** Applies page control rows (pause, resume, accept, reject) onto the session. */
    static void absorb(Path project) {
        List<Map<String, String>> rows = AgentEvents.lines(project);
        Map<String, Object> state = RecordingSession.read(project);
        int absorbed = RecordingSession.number(state.get("absorbed"));
        if (absorbed < 0) absorbed = 0;
        boolean changed = false;
        for (int index = absorbed; index < rows.size(); index++) {
            Map<String, String> row = rows.get(index);
            String type = InteractionLog.first(row.get("type"), row.get("event"));
            if (!"record".equalsIgnoreCase(type)) continue;
            String action = InteractionLog.first(row.get("action"), "");
            if ("pause".equalsIgnoreCase(action)) {
                state.put("status", "paused");
                changed = true;
            } else if ("resume".equalsIgnoreCase(action)) {
                state.put("status", "recording");
                changed = true;
            } else if ("reject".equalsIgnoreCase(action) && row.get("index") != null) {
                List<Integer> dropped = RecordingSession.dropped(state);
                int step = RecordingSession.number(row.get("index"));
                if (!dropped.contains(step)) {
                    dropped.add(step);
                    state.put("dropped", dropped);
                    changed = true;
                }
            } else if ("accept".equalsIgnoreCase(action) && row.get("index") != null) {
                List<Integer> dropped = RecordingSession.dropped(state);
                int step = RecordingSession.number(row.get("index"));
                if (dropped.removeIf(item -> item == step)) {
                    state.put("dropped", dropped);
                    changed = true;
                }
            } else if (editorAction(action)) {
                try {
                    EditorDraft.apply(state, generated(project, state), action, row);
                    changed = true;
                } catch (IllegalArgumentException ignored) {
                    // A stray page index does not stop the rest of the stream.
                }
            } else if ("popout".equalsIgnoreCase(action) || "popin".equalsIgnoreCase(action)) {
                try {
                    EditorDraft.place(state, row.get("panel"), "popout".equalsIgnoreCase(action) ? "window" : "page");
                    changed = true;
                } catch (IllegalArgumentException ignored) {
                    // The page asked for a panel this session does not pop.
                }
            }
        }
        if (absorbed != rows.size()) {
            state.put("absorbed", rows.size());
            changed = true;
        }
        if (changed) RecordingSession.update(project, state);
    }

    private static Outcome stateBody(Map<String, Object> state, String sentence) {
        Map<String, Object> body = ok(sentence);
        body.put("op", "record");
        body.put("status", state.get("status"));
        body.put("sessionId", state.getOrDefault("sessionId", ""));
        body.put("sentence", sentence);
        return new Outcome(0, body);
    }

    private static Map<String, Object> routineFrom(StepGenerator.Script script, String name) {
        Map<String, Object> routine = new LinkedHashMap<>();
        routine.put("name", name);
        routine.put("steps", script.steps());
        routine.put("parameters", script.parameters());
        routine.put("script", script.gherkin());
        return routine;
    }

    private static Map<String, Object> withExamples(Map<String, Object> routine, Map<String, String> values) {
        if (values == null || values.isEmpty() || !(routine.get("parameters") instanceof List<?> list)) return routine;
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> parameter = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) parameter.put(String.valueOf(key), value);
            });
            String name = String.valueOf(parameter.getOrDefault("name", ""));
            if (values.containsKey(name)) parameter.put("example", values.get(name));
            parameters.add(parameter);
        }
        Map<String, Object> copy = new LinkedHashMap<>(routine);
        copy.put("parameters", parameters);
        return copy;
    }

    private static Map<String, String> params(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return values;
        for (String part : raw.split("\\|")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            values.put(part.substring(0, eq).trim(), part.substring(eq + 1));
        }
        return values;
    }

    private static Path outPath(Path project, String out, String fallback) {
        if (out == null || out.isBlank()) return project.resolve(".pickleball/recordings").resolve(fallback);
        Path path = Path.of(out);
        if (!path.isAbsolute()) path = project.resolve(out);
        return path;
    }

    private static boolean editorAction(String action) {
        return "edit".equalsIgnoreCase(action) || "move".equalsIgnoreCase(action)
                || "nest".equalsIgnoreCase(action) || "outdent".equalsIgnoreCase(action)
                || "add".equalsIgnoreCase(action) || "delete".equalsIgnoreCase(action);
    }

    private static List<String> stringList(Object value) {
        List<String> steps = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) if (item != null) steps.add(String.valueOf(item));
        }
        return steps;
    }

    private static void arm(Path project) {
        try {
            Presented presented = presented(project);
            String status = String.valueOf(presented.state().getOrDefault("status", "stopped"));
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("on", "recording".equals(status));
            spec.put("status", status);
            spec.put("script", presented.script().gherkin() == null ? "" : presented.script().gherkin());
            spec.put("steps", EditorDraft.maps(presented.rows()));
            spec.put("panels", EditorDraft.panels(presented.state()));
            AgentControl.armRecording(spec);
        } catch (RuntimeException ignored) {
            // No live page. The state file is still the record mode.
        }
    }

    private static boolean truth(Map<String, String> flags, String name) {
        String value = flags.get(name);
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static Map<String, Object> ok(String sentence) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("sentence", sentence);
        return body;
    }

    private static Outcome fail(int exit, String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("op", "record");
        body.put("error", error);
        body.put("sentence", error);
        return new Outcome(exit, body);
    }
}
