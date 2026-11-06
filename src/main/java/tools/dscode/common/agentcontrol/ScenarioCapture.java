package tools.dscode.common.agentcontrol;

import tools.dscode.common.composite.CompositeLog;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Turns observed network events into existing service-call component scenarios. */
public final class ScenarioCapture {
    private ScenarioCapture() {
    }

    public static String feature(List<ObserveEvents.Event> events) {
        StringBuilder text = new StringBuilder();
        text.append("Feature: Captured service calls\n\n");
        text.append("  # Generated from observe buffers. Existing EXECUTE SERVICE CALL syntax.\n");
        text.append("  # Agent loop: observe buffers, filter, capture-scenario.\n");
        int index = 0;
        for (ObserveEvents.Event event : events) {
            if (event.method() == null || !event.method().startsWith("Network.request")) continue;
            index++;
            String method = event.httpMethod() == null || event.httpMethod().isBlank() ? "GET" : event.httpMethod().toUpperCase(Locale.ROOT);
            String url = event.url() == null ? "" : event.url();
            String endpoint = url;
            String query = "";
            try {
                URI uri = URI.create(url);
                if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
                    endpoint = url.substring(0, url.indexOf('?'));
                    query = uri.getRawQuery();
                }
            } catch (RuntimeException ignored) {
                endpoint = url;
            }
            String name = "Captured" + method.charAt(0) + method.substring(1).toLowerCase(Locale.ROOT) + index;
            String tag = "%captured-" + method.toLowerCase(Locale.ROOT) + "-" + index;
            text.append("  Scenario Outline: ").append(name).append('\n');
            text.append("    Given MAP \"REQUEST\" TABLE VALUES TO SCENARIO MAP\n");
            text.append("      | endpoint | <endpoint> |\n");
            text.append("      | method   | ").append(method).append(" |\n");
            text.append("      | accept   | application/json |\n");
            if (!query.isBlank()) {
                text.append("    And MAP \"REQUEST.queryParams\" TABLE VALUES TO SCENARIO MAP\n");
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    String key = eq < 0 ? pair : pair.substring(0, eq);
                    String value = eq < 0 ? "" : pair.substring(eq + 1);
                    text.append("      | ").append(key).append(" | ").append(value).append(" |\n");
                }
            }
            text.append("    When EXECUTE SERVICE CALL\n");
            text.append("    Examples:\n");
            text.append("      | Scenario Tags | endpoint |\n");
            text.append("      | ").append(tag).append(" | ").append(endpoint).append(" |\n\n");
        }
        if (index == 0) {
            text.append("  # No Network.requestWillBeSent events matched the filter.\n");
        }
        return text.toString();
    }

    public static Map<String, Object> write(Path project, String runId, List<ObserveEvents.Event> events, String out) throws IOException {
        String body = feature(events);
        Path file = out == null || out.isBlank()
                ? AgentStore.root(project, runId).resolve("captured-service-calls.feature")
                : (Path.of(out).isAbsolute() || project == null ? Path.of(out) : project.resolve(out));
        AgentStore.ensure(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
        CompositeLog.info("capture", "out=" + file + " requests=" + countRequests(events));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", file.toAbsolutePath().normalize().toString());
        result.put("requests", countRequests(events));
        result.put("feature", body);
        result.put("sentence", "Existing service-call component scenario syntax. No new Gherkin phrases.");
        return result;
    }

    private static int countRequests(List<ObserveEvents.Event> events) {
        int count = 0;
        for (ObserveEvents.Event event : events) {
            if (event.method() != null && event.method().startsWith("Network.request")) count++;
        }
        return count;
    }
}
