package tools.dscode.browser;

import com.fasterxml.jackson.databind.JsonNode;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.WebDriver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Reads a CDP or BiDi websocket from driver capabilities. Host:port is resolved with a 2 second cap. */
final class DriverEndpoints {
    private DriverEndpoints() {
    }

    static URI cdp(WebDriver driver) {
        String direct = first(driver, "se:cdp", "webSocketDebuggerUrl");
        if (isWebSocket(direct)) return URI.create(direct);
        String address = debuggerAddress(driver);
        if (isWebSocket(address)) return URI.create(address);
        if (address == null || address.isBlank()) return null;
        return jsonVersion(address);
    }

    static URI bidi(WebDriver driver) {
        String direct = first(driver, "webSocketUrl", "se:bidi");
        if (isWebSocket(direct)) return URI.create(direct);
        return null;
    }

    private static String first(WebDriver driver, String... names) {
        if (!(driver instanceof HasCapabilities has)) return null;
        for (String name : names) {
            Object value = has.getCapabilities().getCapability(name);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String debuggerAddress(WebDriver driver) {
        if (!(driver instanceof HasCapabilities has)) return null;
        for (String key : new String[]{"goog:chromeOptions", "ms:edgeOptions"}) {
            Object options = has.getCapabilities().getCapability(key);
            if (options instanceof Map<?, ?> map && map.get("debuggerAddress") != null) {
                return String.valueOf(map.get("debuggerAddress"));
            }
        }
        return null;
    }

    private static boolean isWebSocket(String value) {
        return value != null && (value.startsWith("ws://") || value.startsWith("wss://"));
    }

    private static URI jsonVersion(String hostPort) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + hostPort + "/json/version"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode node = JsonRpc.MAPPER.readTree(response.body());
            String url = node.path("webSocketDebuggerUrl").asText("");
            return isWebSocket(url) ? URI.create(url) : null;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }
}
