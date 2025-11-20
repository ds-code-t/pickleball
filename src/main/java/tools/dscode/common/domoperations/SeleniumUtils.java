package tools.dscode.common.domoperations;

import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.chromium.ChromiumOptions;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class SeleniumUtils {
    public static void waitSeconds(int seconds) {
        long l = seconds * 1000;
        waitMilliseconds(l);
    }

    public static void waitMilliseconds(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }


    public static int portFromString(String key) {
        return 9000 + (Math.abs(key.hashCode()) % 1000);
    }

    private static final String HOST = "127.0.0.1";

    public static Map<String, Object> ensureDevToolsPort(
            Map<String, Object> capsMap, String browserName) {

        return ensureDevToolsPort(capsMap, portFromString(browserName));
    }
    public static Map<String, Object> ensureDevToolsPort(
            Map<String, Object> capsMap, int port) {

        @SuppressWarnings("unchecked")
        Map<String, Object> chromeOptions =
                (Map<String, Object>) capsMap.get("goog:chromeOptions");

        if (chromeOptions == null) {
            chromeOptions = new HashMap<>();
            capsMap.put("goog:chromeOptions", chromeOptions);
        }

        if (isDevToolsListening(HOST, port)) {
            // Attach to existing browser through debuggerAddress
            chromeOptions.put("debuggerAddress", HOST + ":" + port);
        } else {
            // Configure Chrome to start with that DevTools port
            @SuppressWarnings("unchecked")
            List<String> args = (List<String>) chromeOptions.get("args");

            if (args == null) {
                args = new ArrayList<>();
                chromeOptions.put("args", args);
            }

            args.add("--remote-debugging-port=" + port);
        }

        return capsMap;
    }



    public static MutableCapabilities ensureDevToolsPort(MutableCapabilities caps, String browserName) {
        return ensureDevToolsPort(caps, portFromString(browserName));
    }

    public static MutableCapabilities ensureDevToolsPort(MutableCapabilities caps, int port) {
        @SuppressWarnings("unchecked")
        Map<String, Object> chromeOptions =
                (Map<String, Object>) caps.getCapability("goog:chromeOptions");

        if (chromeOptions == null) {
            chromeOptions = new HashMap<>();
            caps.setCapability("goog:chromeOptions", chromeOptions);
        }

        if (isDevToolsListening(HOST, port)) {
            // Attach to an existing browser via debuggerAddress
            chromeOptions.put("debuggerAddress", HOST + ":" + port);
        } else {
            // Start new browser with specified DevTools port
            List<String> args = (List<String>) chromeOptions.get("args");
            if (args == null) {
                args = new ArrayList<>();
                chromeOptions.put("args", args);
            }
            args.add("--remote-debugging-port=" + port);
        }

        return caps;
    }


    private static boolean isDevToolsListening(String host, int port) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(300))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + "/json/version"))
                .timeout(Duration.ofMillis(500))
                .GET()
                .build();

        try {
            HttpResponse<Void> response =
                    client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            // Any failure → assume no DevTools server on that port
            return false;
        }
    }
}

