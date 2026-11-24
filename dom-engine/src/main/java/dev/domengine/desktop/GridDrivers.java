package dev.domengine.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.log.EngineLog;
import dev.domengine.tree.DomNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opt-in Grid-pluggable desktop drivers behind the composite desktop calls (D9).
 * Windows: FlaUI.WebDriver, then NovaWindows (Appium), then WinAppDriver. macOS: Appium Mac2.
 * Linux has no Grid desktop driver (AT-SPI plus xdotool on X11/Xvfb). They speak W3C WebDriver over HTTP,
 * so this uses the JDK HTTP client and adds no dependency. {@code pkb_desktop_driver=auto|flaui|novawindows|
 * winappdriver|mac2|native} selects; {@code pkb_desktop_driver_url} points at a running driver or a remote Grid.
 * Unset is {@code native}: nothing is probed. {@code auto} uses the first driver that answers {@code /status},
 * else the native backend (PowerShell UIA, osascript, AT-SPI) with one warning. Nothing here installs anything.
 */
public final class GridDrivers {
    static final ObjectMapper JSON = new ObjectMapper();
    public static final String KEY = "pkb_desktop_driver";
    public static final String URL_KEY = "pkb_desktop_driver_url";
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** One driver: where it runs, how it is found, and what installing it means. */
    public record Driver(String name, String family, String defaultUrl, String license, String source, String version,
                         String artifact, String sha256, String install, List<String> needs) {
    }

    public static final List<Driver> DRIVERS = List.of(
            new Driver("flaui", "windows", "http://127.0.0.1:4723", "MIT", "https://github.com/FlaUI/FlaUI.WebDriver", "v0.4.0",
                    "https://github.com/FlaUI/FlaUI.WebDriver/releases/download/v0.4.0/FlaUI.WebDriver.exe",
                    "14eb97a7c1a826c25b5b085761499578ece690ffb0677efa23736c7e5176c667", "download", List.of()),
            new Driver("novawindows", "windows", "http://127.0.0.1:4723", "Apache-2.0", "https://github.com/AutomateThePlanet/appium-novawindows-driver", "1.5.0",
                    "", "", "appium driver install --source=npm appium-novawindows-driver@1.5.0", List.of("node", "appium")),
            new Driver("winappdriver", "windows", "http://127.0.0.1:4723", "MIT", "https://github.com/microsoft/WinAppDriver", "v1.2.1",
                    "https://github.com/microsoft/WinAppDriver/releases/download/v1.2.1/WindowsApplicationDriver_1.2.1.msi",
                    "a76a8f4e44b29bad331acf6b6c248fcc65324f502f28826ad2acd5f3c80857fe", "download", List.of("windows-developer-mode")),
            new Driver("mac2", "macos", "http://127.0.0.1:4723", "Apache-2.0", "https://github.com/appium/appium-mac2-driver", "5.0.7",
                    "", "", "appium driver install mac2@5.0.7", List.of("node", "appium", "xcode", "macos-accessibility"))
    );

    /** {@code url -> version} when a driver answers {@code GET /status}, else empty. Injectable for tests. */
    public interface Probe {
        Optional<String> status(String url);
    }

    public record Choice(String driver, String url, String reason) {
        public boolean grid() {
            return !"native".equals(driver);
        }
    }

    private GridDrivers() {
    }

    public static Optional<Driver> byName(String name) {
        if (name == null) return Optional.empty();
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        return DRIVERS.stream().filter(d -> d.name().equals(wanted)).findFirst();
    }

    /** Drivers for one OS family in fallback order. */
    public static List<Driver> forFamily(String family) {
        List<Driver> out = new ArrayList<>();
        for (Driver d : DRIVERS) if (d.family().equals(family)) out.add(d);
        return out;
    }

    public static Choice select(Map<String, String> env, String family, Probe probe) {
        String raw = setting(env, KEY);
        String mode = raw == null ? "native" : raw.trim().toLowerCase(Locale.ROOT);
        String url = setting(env, URL_KEY);
        if ("native".equals(mode) || mode.isBlank()) return new Choice("native", "", raw == null ? "unset" : "native");
        if (!"auto".equals(mode)) {
            Driver driver = byName(mode).orElseThrow(() -> new IllegalArgumentException(
                    "error: " + KEY + " must be auto, flaui, novawindows, winappdriver, mac2, or native\nfix: set " + KEY + "=auto"));
            if (!driver.family().equals(family)) {
                warnOnce("family:" + driver.name(), driver.name() + " runs on " + driver.family() + ", not " + family + "; using the native desktop backend");
                return new Choice("native", "", "wrong-family");
            }
            String at = url == null ? driver.defaultUrl() : url;
            if (probe.status(at).isPresent()) return new Choice(driver.name(), at, "explicit");
            warnOnce("down:" + driver.name(), driver.name() + " is not answering at " + at + "; using the native desktop backend");
            return new Choice("native", "", "not-running");
        }
        for (Driver driver : forFamily(family)) {
            String at = url == null ? driver.defaultUrl() : url;
            Optional<String> version = probe.status(at);
            if (version.isPresent() && matches(driver, version.get())) return new Choice(driver.name(), at, "auto");
        }
        warnOnce("auto:" + family, "no desktop driver is running for " + family + "; using the native desktop backend");
        return new Choice("native", "", "none-running");
    }

    /** A {@code /status} body names the driver loosely; a bare 4723 answer is taken by the first driver in order. */
    static boolean matches(Driver driver, String status) {
        String text = status.toLowerCase(Locale.ROOT);
        if (text.contains("flaui")) return driver.name().equals("flaui");
        if (text.contains("novawindows")) return driver.name().equals("novawindows");
        if (text.contains("mac2")) return driver.name().equals("mac2");
        if (text.contains("winappdriver") || text.contains("windows application driver")) return driver.name().equals("winappdriver");
        return true;
    }

    public static Probe httpProbe() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(400)).build();
        return url -> {
            try {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url.replaceAll("/+$", "") + "/status"))
                        .timeout(Duration.ofMillis(800)).GET().build(), HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
            } catch (Exception unreachable) {
                return Optional.empty();
            }
        };
    }

    /** Doctor rows: one per driver for this family with present/running, version, and what is missing. */
    public static ObjectNode report(Map<String, String> env, String family, Probe probe, java.util.function.Predicate<String> onPath) {
        ObjectNode out = JSON.createObjectNode();
        out.put("family", family);
        String url = setting(env, URL_KEY);
        var rows = out.putArray("drivers");
        if ("linux".equals(family)) {
            out.put("note", "Linux has no Grid desktop driver; desktop control is AT-SPI plus xdotool on X11 or Xvfb");
        }
        for (Driver driver : forFamily(family)) {
            ObjectNode row = rows.addObject();
            row.put("name", driver.name());
            row.put("version", driver.version());
            row.put("license", driver.license());
            String at = url == null ? driver.defaultUrl() : url;
            Optional<String> status = probe.status(at);
            row.put("running", status.isPresent() && matches(driver, status.get()));
            row.put("url", at);
            var missing = row.putArray("missing");
            for (String need : driver.needs()) {
                if (("node".equals(need) || "appium".equals(need)) && onPath.test(need)) continue;
                missing.add(need);
            }
            row.put("fix", row.path("running").asBoolean() ? "" : fix(driver));
        }
        Choice choice;
        try {
            choice = select(env, family, probe);
        } catch (IllegalArgumentException bad) {
            choice = new Choice("native", "", "invalid");
        }
        out.put("selected", choice.driver());
        out.put("reason", choice.reason());
        return out;
    }

    static String fix(Driver driver) {
        String base = "pickleball setup desktop-driver --driver=" + driver.name() + " (prints the " + driver.license()
                + " license and " + driver.source() + ", installs only with --confirm), then start it and set " + KEY + "=" + driver.name();
        return switch (driver.name()) {
            case "winappdriver" -> base + "; WinAppDriver also needs Windows Developer Mode (Settings > For developers)";
            case "mac2" -> base + "; Mac2 needs Node, Appium, Xcode command line tools, and Accessibility permission for the terminal";
            case "novawindows" -> base + "; NovaWindows needs Node and Appium";
            default -> base;
        };
    }

    static void warnOnce(String key, String message) {
        if (WARNED.add(key)) EngineLog.warn("desktop", message);
    }

    public static void resetForTests() {
        WARNED.clear();
    }

    static String setting(Map<String, String> env, String key) {
        String value = System.getProperty(key);
        if (value == null && env != null) value = env.get(key);
        if (value == null && env != null) value = env.get(key.toUpperCase(Locale.ROOT));
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Minimal W3C WebDriver client for a desktop session: page source in, element click and value out. */
    public static final class Session {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        private final String base;
        private final String id;
        public final String driver;

        private Session(String base, String id, String driver) {
            this.base = base;
            this.id = id;
            this.driver = driver;
        }

        public static Session open(Choice choice, String windowName) {
            ObjectNode caps = JSON.createObjectNode();
            ObjectNode always = caps.putObject("capabilities").putObject("alwaysMatch");
            switch (choice.driver()) {
                case "mac2" -> {
                    always.put("platformName", "mac");
                    always.put("appium:automationName", "Mac2");
                }
                case "novawindows" -> {
                    always.put("platformName", "Windows");
                    always.put("appium:automationName", "NovaWindows");
                    always.put("appium:app", "Root");
                }
                case "flaui" -> {
                    always.put("platformName", "windows");
                    always.put("appium:automationName", "FlaUI");
                    if (windowName != null && !windowName.isBlank()) always.put("appium:appTopLevelWindowTitleMatch", java.util.regex.Pattern.quote(windowName) + ".*");
                    else always.put("appium:app", "Root");
                }
                default -> {
                    always.put("platformName", "Windows");
                    always.put("appium:deviceName", "WindowsPC");
                    always.put("appium:app", "Root");
                }
            }
            String base = choice.url().replaceAll("/+$", "");
            JsonNode created = call(HttpClient.newHttpClient(), "POST", base + "/session", caps);
            String id = created.path("value").path("sessionId").asText(created.path("sessionId").asText(""));
            if (id.isBlank()) throw new IllegalStateException("desktop driver " + choice.driver() + " did not create a session");
            EngineLog.info("desktop", "grid session " + choice.driver() + " " + base + (windowName == null ? "" : " window=" + windowName));
            return new Session(base, id, choice.driver());
        }

        public String pageSource() {
            return call(client, "GET", base + "/session/" + id + "/source", null).path("value").asText("");
        }

        public String findXPath(String xpath) {
            ObjectNode body = JSON.createObjectNode();
            body.put("using", "xpath");
            body.put("value", xpath);
            JsonNode found = call(client, "POST", base + "/session/" + id + "/element", body).path("value");
            String element = found.path("element-6066-11e4-a07c-4f37b7ce3a7c").asText(found.path("ELEMENT").asText(""));
            if (element.isBlank()) throw new IllegalStateException("desktop driver found no element for " + xpath);
            return element;
        }

        public void click(String element) {
            call(client, "POST", base + "/session/" + id + "/element/" + element + "/click", JSON.createObjectNode());
        }

        public void type(String element, String text, boolean append) {
            if (!append) call(client, "POST", base + "/session/" + id + "/element/" + element + "/clear", JSON.createObjectNode());
            ObjectNode body = JSON.createObjectNode();
            body.put("text", text == null ? "" : text);
            call(client, "POST", base + "/session/" + id + "/element/" + element + "/value", body);
        }

        public void close() {
            try {
                call(client, "DELETE", base + "/session/" + id, null);
            } catch (RuntimeException ignored) {
                // The driver may already be gone.
            }
        }

        static JsonNode call(HttpClient client, String method, String url, JsonNode body) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json");
                request = switch (method) {
                    case "POST" -> request.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString()));
                    case "DELETE" -> request.DELETE();
                    default -> request.GET();
                };
                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                JsonNode parsed = response.body() == null || response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
                if (response.statusCode() >= 400) {
                    throw new IllegalStateException("desktop driver " + method + " " + url + " failed: " + parsed.path("value").path("message").asText(response.body()));
                }
                return parsed;
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("desktop driver unreachable: " + failure.getMessage(), failure);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", interrupted);
            }
        }
    }

    /** Captures a window through the selected driver: its page source becomes the canonical desktop tree. */
    static DomNode capture(Choice choice, String windowName) {
        Session session = Session.open(choice, windowName);
        try {
            DomNode root = PageSourceTree.parse(session.pageSource(), choice.driver());
            PageSourceTree.mark(root, choice.driver(), choice.url());
            return root;
        } finally {
            session.close();
        }
    }

    /** Re-finds the node by its driver XPath in a fresh session and clicks or types. */
    static void act(DomNode node, String verb, String text, boolean append) {
        String url = node.attributes.getOrDefault("grid-url", "");
        String driver = node.attributes.getOrDefault("grid-driver", "");
        Session session = Session.open(new Choice(driver, url, "act"), null);
        try {
            String element = session.findXPath(node.attributes.getOrDefault("grid-xpath", ""));
            if ("click".equals(verb)) session.click(element);
            else session.type(element, text, append);
        } finally {
            session.close();
        }
    }
}
