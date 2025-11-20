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
    private static final String CHROME_VENDOR_KEY = "goog:chromeOptions";
    private static final String EDGE_VENDOR_KEY   = "ms:edgeOptions";

    public static <T extends ChromiumOptions<T>> T ensureDevToolsPort(T options, String browserName) {
        return ensureDevToolsPort(options, portFromString(browserName));
    }

    public static <T extends ChromiumOptions<T>> T ensureDevToolsPort(T options, int port) {
        Objects.requireNonNull(options, "options must not be null");

        if (isDevToolsListening(HOST, port)) {
            // Attach to existing browser
            options.setExperimentalOption("debuggerAddress", HOST + ":" + port);
        } else {
            // Start new browser with that DevTools port
            options.addArguments("--remote-debugging-port=" + port);
        }

        return options;
    }


    private static String resolveVendorKey(Map<String, Object> capsMap, String browserName) {
        // Prefer what's already present in capabilities
        if (capsMap.containsKey(CHROME_VENDOR_KEY)) {
            return CHROME_VENDOR_KEY;
        }
        if (capsMap.containsKey(EDGE_VENDOR_KEY)) {
            return EDGE_VENDOR_KEY;
        }

        // Fall back to browserName hint
        String name = browserName == null ? "" : browserName.toLowerCase();
        if (name.contains("edge")) {
            return EDGE_VENDOR_KEY;
        }

        // Default to Chrome-style options
        return CHROME_VENDOR_KEY;
    }




    private static boolean isDevToolsListening(String host, int port) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(300))
                .build();
        System.out.println("@@client: " + client);
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

