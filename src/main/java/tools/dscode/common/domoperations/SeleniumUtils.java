package tools.dscode.common.domoperations;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chromium.ChromiumOptions;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static tools.dscode.coredefinitions.BrowserSteps.getCurrentDriverForNonUse;

public class SeleniumUtils {

    private static final Map<String, Set<String>> WINDOW_HANDLES_BY_SESSION =
            new ConcurrentHashMap<>();


    public static boolean windowsChanged() {
        return windowsChanged(getCurrentDriverForNonUse());
    }

    public static boolean windowsChanged(WebDriver driver) {
        String sessionId = String.valueOf(((RemoteWebDriver) driver).getSessionId());

        Set<String> currentHandles = Set.copyOf(driver.getWindowHandles());
        Set<String> previousHandles =
                WINDOW_HANDLES_BY_SESSION.put(sessionId, currentHandles);

        return previousHandles != null && !previousHandles.equals(currentHandles);
    }

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


    public static void waitForDuration(Duration duration, RemoteWebDriver driver) {
//        WebDriver driver = tools.dscode.coredefinitions.BrowserSteps.getCurrentDriverForNonUse();

        if (duration == null || duration.isNegative() || duration.isZero()) {
            System.out.print("wait completed");
            System.out.flush();
            return;
        }

        long totalSeconds = duration.toSeconds();

        try {
            System.out.print("wait " + formatDuration(totalSeconds) + " left");
            System.out.flush();

            if (totalSeconds > 30) {
                long remainder = totalSeconds % 30;

                if (remainder > 0) {
                    Thread.sleep(Duration.ofSeconds(remainder));
                    totalSeconds -= remainder;

                    System.out.print(" ... " + formatDuration(totalSeconds) + " left");
                    System.out.flush();
                }

                while (totalSeconds > 30) {
                    Thread.sleep(Duration.ofSeconds(30));
                    totalSeconds -= 30;

                    keepDriverSessionAlive(driver);

                    System.out.print(" ... " + formatDuration(totalSeconds) + " left");
                    System.out.flush();
                }
            }

            Thread.sleep(Duration.ofSeconds(totalSeconds));

            System.out.print(" ... wait completed");
            System.out.flush();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sleep interrupted", e);
        }
    }

    private static void keepDriverSessionAlive(WebDriver driver) {
        if (driver == null) {
            return;
        }

        try {
            driver.getCurrentUrl();
        } catch (WebDriverException e) {
            // Do not fail the wait just because the keepalive ping failed.
            // The real Selenium command later should report the real browser/session problem.
            System.out.print(" ... browser keepalive failed");
            System.out.flush();
        }
    }

    private static String formatDuration(long seconds) {
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;

        if (minutes > 0) {
            return "%d:%02d minutes".formatted(minutes, remainingSeconds);
        }

        return seconds + " seconds";
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




    public static boolean isDevToolsListening(String host, int port) {
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


    /**
     * UNION: Returns all elements in the given lists, preserving order
     * and removing duplicates based on WebElement equality.
     */
    @SafeVarargs
    public static List<WebElement> union(List<WebElement>... lists) {
        LinkedHashSet<WebElement> set = new LinkedHashSet<>();
        for (List<WebElement> list : lists) {
            if (list != null) {
                set.addAll(list);
            }
        }
        return new ArrayList<>(set);
    }

    /**
     * INTERSECTION: Returns elements that exist in ALL lists.
     * Order of the result follows the order of the *first list*.
     */
    @SafeVarargs
    public static List<WebElement> intersection(List<WebElement>... lists) {
        if (lists == null || lists.length == 0) {
            return List.of();
        }

        // If only one list, intersection is itself
        if (lists.length == 1) {
            return new ArrayList<>(lists[0]);
        }

        // Count occurrences
        Map<WebElement, Integer> countMap = new IdentityHashMap<>();

        for (List<WebElement> list : lists) {
            if (list == null) continue;
            for (WebElement el : list) {
                countMap.merge(el, 1, Integer::sum);
            }
        }

        int required = lists.length;

        // Return only elements that appear in ALL lists,
        // and preserve order based on list 0
        List<WebElement> first = lists[0];
        return first.stream()
                .filter(el -> countMap.getOrDefault(el, 0) == required)
                .collect(Collectors.toList());
    }

}

