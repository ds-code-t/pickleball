package tools.dscode.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.domengine.session.BrowserSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves a browser channel and version against {@code browser.source}.
 * A string {@code browser:} name is not {@code browser.channel}. Only a channel
 * selects Playwright. Blank source and a missing browser block keep today's no-download path.
 * Local resolution uses registered installs ({@link BrowserSession#findChrome()} and
 * {@link BrowserSession#findEdge()}) and does not read the Playwright browser cache.
 */
public final class BrowserVersionResolver {
    public enum Adapter {
        UNCHANGED, PLAYWRIGHT, SELENIUM
    }

    /** One install the tests or the platform can see. */
    public record Install(String channel, String version, Path binary) {
    }

    /** The block read from a browser config. A null channel means the string {@code browser:} form. */
    public record Block(String channel, String browserName, String version, String source, String framePolicy, boolean seleniumBidi) {
        static Block empty() {
            return new Block(null, null, "", "", "top", false);
        }
    }

    /** A finished lookup. {@code binary} may be null when the version came from an injected install. */
    public record Resolution(String channel, String version, String source, Adapter adapter, Path binary) {
    }

    /** Lists installs for one channel. An empty list is a miss. */
    @FunctionalInterface
    public interface Catalog {
        List<Install> list(String channel);
    }

    /** Playwright's downloader. Tests inject a spy. The live path runs only when this is unset. */
    @FunctionalInterface
    public interface Downloader {
        Install fetch(String channel, String version) throws Exception;
    }

    /** Opens Selenium. Playwright connect failure must not call it. Safari does not call it. */
    @FunctionalInterface
    public interface SeleniumFactory {
        void open(Resolution resolution);
    }

    /** Attaches Playwright. A failure fails the attach. */
    @FunctionalInterface
    public interface PlaywrightConnect {
        void connect(Resolution resolution);
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final List<String> PLAYWRIGHT_CHANNELS = List.of("chrome", "msedge", "chromium", "firefox", "webkit");

    private static volatile Block current = Block.empty();
    private static volatile Catalog installs;
    private static volatile Catalog cache;
    private static volatile Downloader downloader;
    private static volatile SeleniumFactory seleniumFactory;
    private static volatile PlaywrightConnect playwrightConnect;

    private BrowserVersionResolver() {
    }

    public static void resetForTests() {
        current = Block.empty();
        installs = null;
        cache = null;
        downloader = null;
        seleniumFactory = null;
        playwrightConnect = null;
    }

    public static void useInstallsForTests(Catalog next) {
        installs = next;
    }

    public static void useCacheForTests(Catalog next) {
        cache = next;
    }

    public static void useDownloaderForTests(Downloader next) {
        downloader = next;
    }

    public static void useSeleniumFactoryForTests(SeleniumFactory next) {
        seleniumFactory = next;
    }

    public static void usePlaywrightConnectForTests(PlaywrightConnect next) {
        playwrightConnect = next;
    }

    /** The block noticed from the last driver config, or empty. */
    public static Block current() {
        return current == null ? Block.empty() : current;
    }

    /** Adapter for the noticed block. No channel keeps today's protocol choice. */
    public static Adapter adapter() {
        return adapterOf(current());
    }

    /**
     * Remembers the config for {@link ProtocolSelector}. Resolves a version when a channel,
     * a version, or source {@code cdn} or {@code any} is set. {@code source: local} with a
     * string {@code browser:} and no version does not probe and does not download.
     */
    public static void prepare(JsonNode configuration) {
        Block block = fromNode(configuration);
        current = block;
        if (needsResolve(block)) resolve(block);
    }

    public static Resolution resolve(Block block) {
        Block use = block == null ? Block.empty() : block;
        String source = sourceOf(use);
        String channel = channelForLookup(use);
        if ("safari".equals(channel)) {
            logResolve(use.version, source, "");
            return new Resolution("safari", blank(use.version) ? "" : use.version, source, Adapter.SELENIUM, null);
        }
        if ("unset".equals(source) && blank(use.channel) && blank(use.version)) {
            return new Resolution(channel, "", source, Adapter.UNCHANGED, null);
        }
        if ("local".equals(source) || "any".equals(source) || "unset".equals(source)) {
            Install local = best(localInstalls(channel), channel, use.version);
            if (local != null && ("local".equals(source) || "unset".equals(source) || matches(use.version, local.version))) {
                logResolve(use.version, source, local.version);
                return new Resolution(channel, shown(local.version), source, adapterOf(use), local.binary);
            }
            if ("local".equals(source) || "unset".equals(source)) {
                throw failed("error: no local " + channel + " browser"
                                + (blank(use.version) ? "" : " " + use.version.trim()),
                        "fix: install that browser, or set browser.source to cdn or any");
            }
        }
        return fromCdn(use, channel, source);
    }

    /**
     * Resolves, then connects when the adapter is Playwright. A connect failure throws
     * {@code error:} and {@code fix:} and does not call the Selenium factory. Safari selects
     * Selenium and does not start a process.
     */
    public static Resolution attach(Block block) {
        Resolution resolution = resolve(block);
        if (resolution.adapter == Adapter.PLAYWRIGHT) {
            PlaywrightConnect connect = playwrightConnect;
            try {
                if (connect == null) {
                    throw new IllegalStateException("Playwright connect is not available");
                }
                connect.connect(resolution);
            } catch (RuntimeException failure) {
                throw failed("error: Playwright connect failed",
                        "fix: the resolved adapter is Playwright and pickleball does not fall back to Selenium");
            }
            return resolution;
        }
        if (resolution.adapter == Adapter.SELENIUM && !"safari".equals(resolution.channel)) {
            SeleniumFactory factory = seleniumFactory;
            if (factory != null) factory.open(resolution);
        }
        return resolution;
    }

    /** {@code top} when the browser file omits {@code framePolicy} or names something else. */
    public static String framePolicy(Path project) {
        try {
            return policy(readBrowserFile(project).framePolicy);
        } catch (IOException failure) {
            return "top";
        }
    }

    /** True when the browser file sets {@code browser.selenium.bidi} to on. */
    public static boolean seleniumBidi(Path project) {
        Block block = current();
        if (block.seleniumBidi) return true;
        try {
            return readBrowserFile(project).seleniumBidi;
        } catch (IOException failure) {
            return false;
        }
    }

    /** Environment for Playwright create. Skip stays on unless source is {@code cdn} or {@code any}. */
    public static Map<String, String> playwrightEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        String source = sourceOf(current());
        boolean download = "cdn".equals(source) || "any".equals(source);
        if (!download) env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        if (download) {
            String host = BrowserProtocols.setting("pkb_browser_cdn_host");
            if (host != null) env.put("PLAYWRIGHT_DOWNLOAD_HOST", host);
        }
        return Map.copyOf(env);
    }

    static Adapter adapterOf(Block block) {
        if (block == null || blank(block.channel)) return Adapter.UNCHANGED;
        String channel = block.channel.trim().toLowerCase(Locale.ROOT);
        if ("selenium".equals(ProtocolSelector.configuredProtocol())) return Adapter.SELENIUM;
        if ("safari".equals(channel)) return Adapter.SELENIUM;
        if (PLAYWRIGHT_CHANNELS.contains(channel)) return Adapter.PLAYWRIGHT;
        return Adapter.UNCHANGED;
    }

    private static boolean needsResolve(Block block) {
        if (block == null) return false;
        if (!blank(block.channel)) return true;
        if (!blank(block.version)) return true;
        String source = sourceOf(block);
        return "cdn".equals(source) || "any".equals(source);
    }

    private static Resolution fromCdn(Block block, String channel, String source) {
        Install cached = best(cachedInstalls(channel), channel, block.version);
        if (cached != null) {
            logResolve(block.version, source, cached.version);
            return new Resolution(channel, shown(cached.version), source, adapterOf(block), cached.binary);
        }
        Install downloaded = null;
        RuntimeException downloadFailure = null;
        try {
            downloaded = download(channel, block.version);
        } catch (RuntimeException failure) {
            downloadFailure = failure;
        }
        if (downloaded != null) {
            logResolve(block.version, source, downloaded.version);
            return new Resolution(channel, shown(downloaded.version), source, adapterOf(block), downloaded.binary);
        }
        Install fallback = best(cachedInstalls(channel), channel, "");
        if (fallback != null) {
            BrowserLog.info("resolve", "resolve cache-fallback requested=" + shown(block.version)
                    + " source=" + source + " resolved=" + shown(fallback.version));
            return new Resolution(channel, shown(fallback.version), source, adapterOf(block), fallback.binary);
        }
        String detail = downloadFailure == null ? "no build" : "cdn unreachable";
        throw failed("error: no " + channel + " browser for source " + source + " (" + detail + ")",
                "fix: cache that browser or set browser.source: local");
    }

    private static Install download(String channel, String version) {
        Downloader hook = downloader;
        try {
            if (hook != null) return hook.fetch(channel, version);
            return liveDownload(channel, version);
        } catch (Exception failure) {
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }

    private static Install liveDownload(String channel, String version) throws Exception {
        String name = playwrightName(channel);
        List<String> command = new ArrayList<>();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        command.add(java);
        command.add("-cp");
        command.add(System.getProperty("java.class.path", "."));
        command.add("com.microsoft.playwright.CLI");
        command.add("install");
        command.add(name);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().remove("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD");
        String host = BrowserProtocols.setting("pkb_browser_cdn_host");
        if (host != null) builder.environment().put("PLAYWRIGHT_DOWNLOAD_HOST", host);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            throw new IOException("playwright install exited non-zero");
        }
        return new Install(channel, blank(version) ? "latest" : version.trim(), null);
    }

    private static String playwrightName(String channel) {
        if (channel == null) return "chromium";
        return switch (channel.toLowerCase(Locale.ROOT)) {
            case "firefox" -> "firefox";
            case "webkit" -> "webkit";
            default -> "chromium";
        };
    }

    private static List<Install> localInstalls(String channel) {
        Catalog hook = installs;
        if (hook != null) return hook.list(channel);
        return platform(channel);
    }

    private static List<Install> cachedInstalls(String channel) {
        Catalog hook = cache;
        if (hook != null) return hook.list(channel);
        return List.of();
    }

    private static List<Install> platform(String channel) {
        if (channel == null) return List.of();
        String name = channel.toLowerCase(Locale.ROOT);
        String path = null;
        if ("chrome".equals(name) || "chromium".equals(name)) path = BrowserSession.findChrome();
        else if ("msedge".equals(name) || "edge".equals(name)) path = BrowserSession.findEdge();
        if (path == null || path.isBlank()) return List.of();
        return List.of(new Install(name, "", Path.of(path)));
    }

    static Install best(List<Install> found, String channel, String requested) {
        if (found == null || found.isEmpty()) return null;
        List<Install> matched = new ArrayList<>();
        for (Install install : found) {
            if (install == null) continue;
            if (!blank(channel) && !blank(install.channel) && !channel.equalsIgnoreCase(install.channel)) continue;
            if (!matches(requested, install.version)) continue;
            matched.add(install);
        }
        if (matched.isEmpty()) return null;
        matched.sort(Comparator.comparing((Install install) -> parts(install.version), BrowserVersionResolver::compareParts).reversed());
        return matched.get(0);
    }

    static boolean matches(String requested, String candidate) {
        if (blank(requested) || "latest".equalsIgnoreCase(requested.trim())) return true;
        if (blank(candidate)) return false;
        int[] want = parts(requested);
        int[] have = parts(candidate);
        if (want.length == 0 || want.length > have.length) return false;
        for (int index = 0; index < want.length; index++) {
            if (want[index] != have[index]) return false;
        }
        return true;
    }

    private static int compareParts(int[] left, int[] right) {
        int count = Math.max(left.length, right.length);
        for (int index = 0; index < count; index++) {
            int a = index < left.length ? left[index] : 0;
            int b = index < right.length ? right[index] : 0;
            if (a != b) return Integer.compare(a, b);
        }
        return 0;
    }

    private static int[] parts(String version) {
        if (blank(version)) return new int[0];
        String[] tokens = version.trim().split("\\.");
        int[] values = new int[tokens.length];
        for (int index = 0; index < tokens.length; index++) {
            try {
                values[index] = Integer.parseInt(tokens[index]);
            } catch (NumberFormatException failure) {
                values[index] = 0;
            }
        }
        return values;
    }

    private static void logResolve(String requested, String source, String resolved) {
        BrowserLog.info("resolve", "requested=" + shown(requested) + " source=" + source + " resolved=" + shown(resolved));
    }

    public static Block fromNode(JsonNode configuration) {
        if (configuration == null || configuration.isMissingNode() || configuration.isNull()) return Block.empty();
        JsonNode browser = configuration.path("browser");
        String channel = null;
        String browserName = null;
        String version = "";
        String source = text(configuration, "source");
        String frame = text(configuration, "framePolicy");
        boolean bidi = on(text(configuration.path("selenium"), "bidi"));
        if (browser.isObject()) {
            channel = text(browser, "channel");
            version = text(browser, "version");
            String nested = text(browser, "source");
            if (!nested.isBlank()) source = nested;
            String nestedFrame = text(browser, "framePolicy");
            if (!nestedFrame.isBlank()) frame = nestedFrame;
            if (on(browser.path("selenium").path("bidi").asText(""))) bidi = true;
        } else if (browser.isTextual()) {
            browserName = browser.asText("").trim();
        }
        return new Block(blank(channel) ? null : channel, blank(browserName) ? null : browserName,
                version, source, policy(frame), bidi);
    }

    private static Block readBrowserFile(Path project) throws IOException {
        Path file = browserFile(project);
        if (file == null) return Block.empty();
        JsonNode node = YAML.readTree(Files.readString(file));
        return fromNode(node);
    }

    private static Path browserFile(Path project) {
        Path root = project == null ? Path.of("").toAbsolutePath() : project;
        String name = BrowserProtocols.setting("pkb_browser");
        if (name == null || name.isBlank()) name = "CHROME_HEADLESS";
        if (!name.endsWith(".yaml") && !name.endsWith(".yml")) name = name + ".yaml";
        String config = BrowserProtocols.setting("pkb_configpath");
        List<Path> candidates = new ArrayList<>();
        if (config != null && !config.isBlank()) candidates.add(Path.of(config).resolve(name));
        candidates.add(root.resolve("src").resolve("test").resolve("resources").resolve("configs").resolve(name));
        candidates.add(root.resolve("configs").resolve(name));
        candidates.add(root.resolve("maven-consumer-project").resolve("src").resolve("test").resolve("resources").resolve("configs").resolve(name));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static String channelForLookup(Block block) {
        if (!blank(block.channel)) return block.channel.trim().toLowerCase(Locale.ROOT);
        if (!blank(block.browserName)) return block.browserName.trim().toLowerCase(Locale.ROOT);
        return "";
    }

    private static String sourceOf(Block block) {
        if (block == null || blank(block.source)) return "unset";
        String value = block.source.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "local", "cdn", "any" -> value;
            default -> "unset";
        };
    }

    private static String policy(String raw) {
        if (blank(raw)) return "top";
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("iframe".equals(value) || "all".equals(value) || "top".equals(value)) return value;
        return "top";
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return "";
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) return "";
        return value.asText("").trim();
    }

    private static boolean on(String value) {
        if (value == null) return false;
        String text = value.trim().toLowerCase(Locale.ROOT);
        return "on".equals(text) || "true".equals(text) || "yes".equals(text) || "1".equals(text);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String shown(String value) {
        return blank(value) ? "" : value.trim();
    }

    private static IllegalStateException failed(String error, String fix) {
        return new IllegalStateException(error + "\n" + fix);
    }
}
