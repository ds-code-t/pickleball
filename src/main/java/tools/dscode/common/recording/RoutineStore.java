package tools.dscode.common.recording;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Named routines under {@code .pickleball/recordings/routines}. */
public final class RoutineStore {
    public static final String DIR = ".pickleball/recordings/routines";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private RoutineStore() {
    }

    public static Path directory(Path project) {
        Path root = project == null ? Path.of("") : project;
        return root.resolve(DIR);
    }

    public static Path file(Path project, String name) {
        return directory(project).resolve(safe(name) + ".json");
    }

    public static Map<String, Object> save(Path project, String name, StepGenerator.Script script) throws IOException {
        return save(project, name, script, List.of());
    }

    public static Map<String, Object> save(Path project, String name, StepGenerator.Script script, List<Integer> depths) throws IOException {
        String title = name == null || name.isBlank() ? "recorded" : name.trim();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", title);
        body.put("savedAt", Instant.now().toString());
        body.put("script", script.gherkin());
        body.put("phrase", script.phrase());
        body.put("steps", script.steps());
        body.put("depths", depths == null ? List.of() : depths);
        body.put("parameters", script.parameters());
        Path target = file(project, title);
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(target, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(body), StandardCharsets.UTF_8);
        body.put("path", target.toAbsolutePath().normalize().toString());
        return body;
    }

    public static Map<String, Object> load(Path project, String name) throws IOException {
        Path target = file(project, name);
        if (!Files.isRegularFile(target)) {
            throw new IOException("error: no routine named " + name);
        }
        Map<String, Object> body = JSON.readValue(Files.readString(target, StandardCharsets.UTF_8), MAP);
        body.put("path", target.toAbsolutePath().normalize().toString());
        return body;
    }

    public static List<String> list(Path project) throws IOException {
        Path dir = directory(project);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> {
                        String file = path.getFileName().toString();
                        return file.substring(0, file.length() - ".json".length());
                    })
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    public static String feature(Map<String, Object> routine) {
        String name = String.valueOf(routine.getOrDefault("name", "recorded"));
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
                        if (key != null) parameter.put(String.valueOf(key), value);
                    });
                    parameters.add(parameter);
                }
            }
        }
        StringBuilder text = new StringBuilder();
        text.append("Feature: ").append(name).append("\n\n");
        text.append("  # Recorded collaborative routine. Replay with the Examples row or record --run --param.\n");
        if (parameters.isEmpty()) {
            text.append("  Scenario: ").append(name).append("\n");
            writeSteps(text, steps, depthList(routine));
            return text.toString();
        }
        text.append("  Scenario Outline: ").append(name).append("\n");
        writeSteps(text, steps, depthList(routine));
        text.append("\n    Examples:\n      |");
        for (Map<String, Object> parameter : parameters) {
            text.append(' ').append(parameter.get("name")).append(" |");
        }
        text.append("\n      |");
        for (Map<String, Object> parameter : parameters) {
            text.append(' ').append(cell(String.valueOf(parameter.getOrDefault("example", "")))).append(" |");
        }
        text.append('\n');
        return text.toString();
    }

    public static List<Map<String, Object>> replaySteps(List<String> concrete) {
        List<Map<String, Object>> steps = new ArrayList<>();
        if (concrete == null) return steps;
        for (String line : concrete) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("op", "execute-step");
            Map<String, String> args = new LinkedHashMap<>();
            args.put("text", line.startsWith("Then ") || line.startsWith("And ") || line.startsWith("* ")
                    ? line : "Then " + line);
            step.put("args", args);
            steps.add(step);
        }
        return steps;
    }

    public static List<Integer> depthList(Map<String, Object> routine) {
        List<Integer> depths = new ArrayList<>();
        if (routine == null || !(routine.get("depths") instanceof List<?> list)) return depths;
        for (Object item : list) depths.add(RecordingSession.number(item));
        return depths;
    }

    private static void writeSteps(StringBuilder text, List<String> steps, List<Integer> depths) {
        for (int index = 0; index < steps.size(); index++) {
            text.append(EditorDraft.featureLine(steps, depths, index)).append('\n');
        }
    }

    private static String cell(String value) {
        String text = value == null || "null".equals(value) ? "" : value;
        return text.replace("|", "/").replace("\n", " ").replace("\r", "");
    }

    static String safe(String name) {
        String raw = name == null || name.isBlank() ? "recorded" : name.trim();
        StringBuilder safe = new StringBuilder();
        for (int index = 0; index < raw.length(); index++) {
            char ch = raw.charAt(index);
            if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') safe.append(ch);
            else safe.append('-');
        }
        String text = safe.toString().replaceAll("-{2,}", "-");
        if (text.isBlank()) return "recorded";
        return text.length() > 80 ? text.substring(0, 80) : text;
    }
}
