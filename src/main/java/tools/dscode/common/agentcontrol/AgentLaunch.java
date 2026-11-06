package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies a resolved template onto the config {@code DriverConstruction} already
 * reads. Absent file means the existing launch is unchanged.
 */
public final class AgentLaunch {
    private AgentLaunch() {
    }

    public static Path file(Path project) {
        Path root = project == null ? Path.of("").toAbsolutePath() : project.toAbsolutePath().normalize();
        return root.resolve(".pickleball").resolve("agent-launch.json");
    }

    public static void write(Path project, LaunchTemplates.Resolved resolved) throws IOException {
        Path file = file(project);
        Files.createDirectories(file.getParent());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("template", resolved.template().name());
        body.put("browser", resolved.template().browser());
        body.put("pkbBrowser", resolved.template().pkbBrowser());
        body.put("headless", resolved.template().headless());
        body.put("verbose", resolved.template().verbose() || resolved.template().logs());
        body.put("logs", resolved.template().logs());
        body.put("args", resolved.args());
        body.put("sentence", resolved.sentence());
        AgentStore.JSON.writeValue(file.toFile(), body);
    }

    public static void apply(String browserName, ObjectNode driverConfig) {
        apply(browserName, driverConfig, Path.of("").toAbsolutePath());
    }

    public static void apply(String browserName, ObjectNode driverConfig, Path project) {
        Path file = file(project);
        if (!Files.isRegularFile(file) || driverConfig == null) return;
        JsonNode saved;
        try {
            saved = AgentStore.JSON.readTree(file.toFile());
        } catch (IOException failure) {
            return;
        }
        ObjectNode options = driverConfig.with("options");
        ArrayNode args = options.withArray("args");
        for (JsonNode arg : saved.path("args")) {
            String text = arg.asText("");
            if (text.isBlank() || contains(args, text)) continue;
            args.add(text);
        }
        if (saved.path("verbose").asBoolean(false)) {
            driverConfig.with("service").put("verbose", true);
            if (saved.path("logs").asBoolean(false)) {
                driverConfig.with("service").put("logFile", file.getParent().resolve("driver.log").toString());
            }
        }
        if (browserName == null || browserName.isBlank()) {
            driverConfig.put("browser", saved.path("browser").asText("chrome"));
        }
    }

    private static boolean contains(ArrayNode args, String text) {
        for (JsonNode arg : args) {
            if (text.equals(arg.asText())) return true;
        }
        return false;
    }
}
