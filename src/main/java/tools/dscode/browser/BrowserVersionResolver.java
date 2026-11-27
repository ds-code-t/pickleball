package tools.dscode.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.domengine.session.BrowserSession;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
            Install installed = installedAfterDownload(channel, block.version, downloaded);
            logResolve(block.version, source, installed.version);
            return new Resolution(channel, shown(installed.version), source, adapterOf(block), installed.binary);
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

    /**
     * A finished download is read back from the Playwright cache.
     * The returned install keeps the real version and binary. A cache miss keeps the download result.
     */
    private static Install installedAfterDownload(String channel, String requested, Install downloaded) {
        Install cached = best(cachedInstalls(channel), channel, requested);
        if (cached == null || cached.binary == null) {
            Install any = best(cachedInstalls(channel), channel, "");
            if (any != null && any.binary != null) cached = any;
        }
        if (cached != null && cached.binary != null) return cached;
        return downloaded;
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
        return playwrightCache(channel);
    }

    /**
     * Playwright's browser cache. {@code PLAYWRIGHT_BROWSERS_PATH} (JVM property, then the
     * environment variable) wins. Otherwise Windows uses {@code %LOCALAPPDATA%\ms-playwright}.
     * A version comes from Playwright {@code browsers.json} metadata when that file is in the
     * cache, then from a version directory or the browser binary's file version.
     * {@code local} does not call this.
     */
    private static List<Install> playwrightCache(String channel) {
        if (channel == null || channel.isBlank()) return List.of();
        Path root = playwrightCacheRoot();
        if (root == null || !Files.isDirectory(root)) return List.of();
        String folder = playwrightFolder(channel);
        if (folder == null) return List.of();
        String label = channel.toLowerCase(Locale.ROOT);
        Map<String, String> metadata = browserMetadata(root);
        List<Install> found = new ArrayList<>();
        try (var listed = Files.list(root)) {
            for (Path dir : listed.toList()) {
                if (!Files.isDirectory(dir)) continue;
                String name = dir.getFileName().toString();
                int dash = name.lastIndexOf('-');
                if (dash <= 0) continue;
                String browser = name.substring(0, dash);
                if (!folder.equals(browser)) continue;
                String version = metadata.getOrDefault(name, "");
                if (version.isBlank()) version = versionBeside(dir);
                Path binary = browserBinary(dir);
                if (version.isBlank() && binary != null) version = fileVersion(binary);
                found.add(new Install(label, version, binary == null ? dir : binary));
            }
        } catch (IOException failure) {
            return List.of();
        }
        return List.copyOf(found);
    }

    private static Path playwrightCacheRoot() {
        String pinned = firstSetting("PLAYWRIGHT_BROWSERS_PATH");
        // Playwright treats 0 as "use the default cache", not as a path and not as disabled.
        if (pinned != null && !"0".equals(pinned)) return Path.of(pinned);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            if (local == null || local.isBlank()) return null;
            return Path.of(local, "ms-playwright");
        }
        String home = System.getProperty("user.home", "");
        if (home.isBlank()) return null;
        if (os.contains("mac")) return Path.of(home, "Library", "Caches", "ms-playwright");
        return Path.of(home, ".cache", "ms-playwright");
    }

    /** Chromium cache folders serve chrome, msedge, and chromium. Firefox and webkit stay on their own names. */
    private static String playwrightFolder(String channel) {
        return switch (channel.toLowerCase(Locale.ROOT)) {
            case "chrome", "chromium", "msedge", "edge" -> "chromium";
            case "firefox" -> "firefox";
            case "webkit" -> "webkit";
            default -> null;
        };
    }

    private static Map<String, String> browserMetadata(Path root) {
        Path file = root.resolve("browsers.json");
        if (!Files.isRegularFile(file)) return Map.of();
        try {
            JsonNode node = new ObjectMapper().readTree(Files.readString(file));
            JsonNode browsers = node.path("browsers");
            if (!browsers.isArray()) return Map.of();
            Map<String, String> versions = new LinkedHashMap<>();
            for (JsonNode browser : browsers) {
                String name = browser.path("name").asText("").trim();
                String revision = browser.path("revision").asText("").trim();
                String version = browser.path("browserVersion").asText("").trim();
                if (!name.isEmpty() && !revision.isEmpty() && !version.isEmpty()) {
                    versions.put(name + "-" + revision, version);
                }
            }
            return versions;
        } catch (IOException | RuntimeException failure) {
            return Map.of();
        }
    }

    private static Path browserBinary(Path dir) {
        List<String> names = List.of("chrome.exe", "chrome", "msedge.exe", "firefox.exe", "firefox", "Playwright.exe");
        try (var walk = Files.walk(dir, 3)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                String name = file.getFileName().toString();
                for (String expected : names) {
                    if (expected.equalsIgnoreCase(name)) return file;
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static List<Install> platform(String channel) {
        if (channel == null) return List.of();
        String name = channel.toLowerCase(Locale.ROOT);
        String path = locatedBinary(name);
        if (path == null || path.isBlank()) return List.of();
        Path binary = Path.of(path);
        List<String> versions = installVersions(binary);
        if (versions.isEmpty()) return List.of(new Install(name, "", binary));
        List<Install> found = new ArrayList<>();
        for (String version : versions) found.add(new Install(name, version, binary));
        return List.copyOf(found);
    }

    /**
     * An explicit {@code DOMENGINE_CHROME} or {@code DOMENGINE_EDGE} JVM property is that binary.
     * A property that does not name a file is a miss and does not fall through to the machine install.
     * With no property, the locations are {@link BrowserSession#findChrome()} and {@link BrowserSession#findEdge()}.
     */
    private static String locatedBinary(String channel) {
        boolean edge = "msedge".equals(channel) || "edge".equals(channel);
        boolean chrome = "chrome".equals(channel) || "chromium".equals(channel);
        if (!edge && !chrome) return null;
        String key = edge ? "DOMENGINE_EDGE" : "DOMENGINE_CHROME";
        String property = System.getProperty(key);
        if (property != null) {
            String trimmed = property.trim();
            if (!trimmed.isEmpty() && Files.isRegularFile(Path.of(trimmed))) return trimmed;
            return null;
        }
        return edge ? BrowserSession.findEdge() : BrowserSession.findChrome();
    }

    /** Version directory names beside the executable, or the parent folder when the executable lives inside one. */
    private static List<String> installVersions(Path binary) {
        List<String> versions = new ArrayList<>();
        Path parent = binary.getParent();
        if (parent != null) {
            String parentName = parent.getFileName() == null ? "" : parent.getFileName().toString();
            if (isVersion(parentName)) versions.add(parentName);
            try (var listed = Files.list(parent)) {
                for (Path child : listed.toList()) {
                    if (!Files.isDirectory(child)) continue;
                    String name = child.getFileName().toString();
                    if (isVersion(name)) versions.add(name);
                }
            } catch (IOException ignored) {
                // A directory that cannot be listed still tries the file version below.
            }
        }
        if (versions.isEmpty()) {
            String fromFile = fileVersion(binary);
            if (!fromFile.isBlank()) versions.add(fromFile);
        }
        return versions;
    }

    private static String versionBeside(Path dir) {
        try (var listed = Files.list(dir)) {
            String best = "";
            for (Path child : listed.toList()) {
                if (!Files.isDirectory(child)) continue;
                String name = child.getFileName().toString();
                if (!isVersion(name)) continue;
                if (best.isEmpty() || compareParts(parts(name), parts(best)) > 0) best = name;
            }
            return best;
        } catch (IOException failure) {
            return "";
        }
    }

    private static boolean isVersion(String name) {
        if (name == null || name.isBlank()) return false;
        String[] tokens = name.split("\\.");
        if (tokens.length < 2 || tokens.length > 4) return false;
        for (String token : tokens) {
            if (token.isEmpty()) return false;
            for (int index = 0; index < token.length(); index++) {
                if (!Character.isDigit(token.charAt(index))) return false;
            }
        }
        return true;
    }

    /** Windows file version. A file that is not a PE image returns blank. The browser is not started. */
    private static String fileVersion(Path binary) {
        if (binary == null || !Files.isRegularFile(binary)) return "";
        try (InputStream input = Files.newInputStream(binary)) {
            byte[] header = input.readNBytes(64);
            if (header.length < 64 || header[0] != 'M' || header[1] != 'Z') return "";
            int pe = ByteBuffer.wrap(header, 0x3C, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (pe < 0 || pe > 1_048_576) return "";
            long skip = pe - 64L;
            while (skip > 0) {
                long read = input.skip(skip);
                if (read <= 0) return "";
                skip -= read;
            }
            byte[] peHeader = input.readNBytes(24);
            if (peHeader.length < 24 || peHeader[0] != 'P' || peHeader[1] != 'E') return "";
            int optionalSize = ByteBuffer.wrap(peHeader, 20, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
            if (optionalSize < 96) return "";
            byte[] optional = input.readNBytes(optionalSize);
            if (optional.length < optionalSize) return "";
            int magic = ByteBuffer.wrap(optional, 0, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
            int dataDirectory = magic == 0x20B ? 112 : 96;
            if (optional.length < dataDirectory + 16) return "";
            int resourceRva = ByteBuffer.wrap(optional, dataDirectory + 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (resourceRva == 0) return "";
            int sectionCount = ByteBuffer.wrap(peHeader, 6, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
            byte[] sections = input.readNBytes(sectionCount * 40);
            if (sections.length < sectionCount * 40) return "";
            long fileOffset = -1;
            for (int index = 0; index < sectionCount; index++) {
                int base = index * 40;
                int virtual = ByteBuffer.wrap(sections, base + 12, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                int rawSize = ByteBuffer.wrap(sections, base + 16, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                int raw = ByteBuffer.wrap(sections, base + 20, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (resourceRva >= virtual && resourceRva < virtual + Math.max(rawSize, 1)) {
                    fileOffset = raw + (resourceRva - virtual);
                    break;
                }
            }
            if (fileOffset < 0) return "";
            byte[] resource = readRange(binary, fileOffset, 65536);
            if (resource.length < 16) return "";
            return versionFromResource(resource);
        } catch (IOException | RuntimeException failure) {
            return "";
        }
    }

    private static byte[] readRange(Path file, long offset, int length) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            long skip = offset;
            while (skip > 0) {
                long read = input.skip(skip);
                if (read <= 0) return new byte[0];
                skip -= read;
            }
            return input.readNBytes(length);
        }
    }

    /** Walks one resource directory level for RT_VERSION (16) and reads VS_FIXEDFILEINFO. */
    private static String versionFromResource(byte[] resource) {
        int bytes = Math.min(resource.length, 65536);
        for (int index = 0; index + 16 <= bytes; index++) {
            if ((resource[index] & 0xFF) != 0xBD || (resource[index + 1] & 0xFF) != 0x04
                    || (resource[index + 2] & 0xFF) != 0xEF || (resource[index + 3] & 0xFF) != 0xFE) {
                continue;
            }
            if (index + 20 > bytes) return "";
            ByteBuffer fixed = ByteBuffer.wrap(resource, index, 20).order(ByteOrder.LITTLE_ENDIAN);
            fixed.getInt();
            int ms = fixed.getInt(8);
            int ls = fixed.getInt(12);
            int major = (ms >>> 16) & 0xFFFF;
            int minor = ms & 0xFFFF;
            int patch = (ls >>> 16) & 0xFFFF;
            int build = ls & 0xFFFF;
            return major + "." + minor + "." + patch + "." + build;
        }
        return "";
    }

    private static String firstSetting(String name) {
        String property = System.getProperty(name);
        if (property != null && !property.isBlank()) return property.trim();
        String env = System.getenv(name);
        if (env != null && !env.isBlank()) return env.trim();
        return null;
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
