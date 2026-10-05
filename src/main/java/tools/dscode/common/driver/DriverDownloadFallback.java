package tools.dscode.common.driver;

import com.fasterxml.jackson.databind.JsonNode;

import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.reporting.logging.LogForwarder.logWarn;

/**
 * Best-effort Chrome for Testing and Microsoft Edge driver download.
 * Every failure here is a log line. The only thrown configuration error is an explicit
 * native-and-fallback disable with no {@code driverExecutable}.
 */
public final class DriverDownloadFallback {

    public static final String DIRECT = "direct";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TRANSFER_CAP = Duration.ofSeconds(20);
    private static final int MAX_BYTES = 80 * 1024 * 1024;
    private static final int RECENT_MAJORS = 8;
    private static final String CHROME_METADATA = "https://googlechromelabs.github.io/chrome-for-testing/";
    private static final String EDGE_METADATA = "https://msedgedriver.microsoft.com/";
    private static final Pattern VERSION = Pattern.compile("(\\d+\\.\\d+\\.\\d+\\.\\d+)");
    private static final Pattern SESSION_BROWSER = Pattern.compile(
            "Current browser version is\\s+(\\d+(?:\\.\\d+)*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DRIVER_URL_VERSION = Pattern.compile(
            "(?:chrome-for-testing-public|chrome/chrome-for-testing|msedgedriver\\.microsoft\\.com)/(\\d+\\.\\d+\\.\\d+\\.\\d+)");
    private static final Pattern UNTESTED = Pattern.compile(
            "has not been tested with.{0,40}?version\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Object DISCOVERY = new Object();
    private static final AtomicReference<ChosenProxy> CHOSEN = new AtomicReference<>();
    private static volatile Transfer transferOverride;
    private static volatile VersionLookup versionOverride;
    private static volatile ExecutorService fillExecutor;
    private static volatile boolean synchronousFill;

    @FunctionalInterface
    interface Transfer {
        byte[] get(List<String> urls) throws IOException;
    }

    @FunctionalInterface
    interface VersionLookup {
        String version(String browser, String binary);
    }

    public enum ProxyMode {
        DISCOVER,
        DISABLED,
        EXPLICIT
    }

    public record ProxyCandidate(String source, String value) {
    }

    private DriverDownloadFallback() {
    }

    public static ProxyMode proxyMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return ProxyMode.DISCOVER;
        }
        if ("false".equalsIgnoreCase(raw.trim())) {
            return ProxyMode.DISABLED;
        }
        return ProxyMode.EXPLICIT;
    }

    public static boolean nativeEnabled(String raw) {
        return raw == null || raw.isBlank() || !"false".equalsIgnoreCase(raw.trim());
    }

    public static boolean nativeDownloadsEnabled() {
        return nativeEnabled(property(PKB_props.PKB_DRIVER_DOWNLOAD_NATIVE));
    }

    public static String browserVersionFromSessionMessage(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = SESSION_BROWSER.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static String browserVersionFromSessionMessage(Throwable failure) {
        return browserVersionFromSessionMessage(messageChain(failure));
    }

    public static boolean isSessionVersionMismatch(Throwable failure) {
        return browserVersionFromSessionMessage(failure) != null;
    }

    public static String versionFromDriverDownloadUrl(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = DRIVER_URL_VERSION.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static String versionFromFailure(Throwable failure) {
        String text = messageChain(failure);
        String session = browserVersionFromSessionMessage(text);
        return session != null ? session : versionFromDriverDownloadUrl(text);
    }

    public static String decodeEdgeVersionText(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String decoded;
        if (body.length >= 2 && (body[0] & 0xFF) == 0xFF && (body[1] & 0xFF) == 0xFE) {
            decoded = new String(body, 2, body.length - 2, StandardCharsets.UTF_16LE);
        } else if (body.length >= 2 && (body[0] & 0xFF) == 0xFE && (body[1] & 0xFF) == 0xFF) {
            decoded = new String(body, 2, body.length - 2, StandardCharsets.UTF_16BE);
        } else if (looksLikeUtf16Le(body)) {
            decoded = new String(body, StandardCharsets.UTF_16LE);
        } else {
            decoded = new String(body, StandardCharsets.US_ASCII);
        }
        return decoded.replace("\uFEFF", "").replace("\r", "").replace("\n", "").trim();
    }

    public static List<String> chromeZipUrls(String version, String platform) {
        String ver = version.trim();
        String plat = platform.trim();
        String name = "chromedriver-" + plat + ".zip";
        return List.of(
                "https://commondatastorage.googleapis.com/chrome-for-testing-public/" + ver + "/" + plat + "/" + name,
                "https://storage.googleapis.com/chrome-for-testing-public/" + ver + "/" + plat + "/" + name,
                "https://edgedl.me.gvt1.com/edgedl/chrome/chrome-for-testing/" + ver + "/" + plat + "/" + name
        );
    }

    public static List<String> edgeZipUrls(String version, String zipLabel) {
        return List.of(EDGE_METADATA + version.trim() + "/edgedriver_" + zipLabel.trim() + ".zip");
    }

    public static String edgeMajorMetadataUrl(String major, String osToken) {
        return EDGE_METADATA + "LATEST_RELEASE_" + major + "_" + osToken;
    }

    public static List<ProxyCandidate> proxyCandidates(
            String pkbValue,
            Map<String, String> environment,
            List<String> osSystem,
            List<String> osLower
    ) {
        if (proxyMode(pkbValue) == ProxyMode.DISABLED) {
            return List.of();
        }
        List<ProxyCandidate> candidates = new ArrayList<>();
        if (proxyMode(pkbValue) == ProxyMode.EXPLICIT) {
            candidates.add(new ProxyCandidate(PKB_props.PKB_DRIVER_DOWNLOAD_PROXY, pkbValue.trim()));
        }
        Map<String, String> env = environment == null ? Map.of() : environment;
        for (String key : List.of("HTTPS_PROXY", "https_proxy", "SE_PROXY", "se_proxy")) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                candidates.add(new ProxyCandidate(key, value.trim()));
            }
        }
        addAll(candidates, "os-proxy", osSystem);
        addAll(candidates, "os-lower", osLower);
        candidates.add(new ProxyCandidate(DIRECT, DIRECT));
        return dedupe(candidates);
    }

    public static Path resolveCacheDirectory(
            Map<String, String> environment,
            String systemProperty,
            Path homeDirectory,
            String seConfigToml
    ) {
        String configured = firstNonBlank(systemProperty, environment == null ? null : environment.get("SE_CACHE_PATH"));
        if (configured == null && seConfigToml != null) {
            configured = cachePathFromToml(seConfigToml);
        }
        if (configured == null) {
            return homeDirectory.resolve(".cache").resolve("selenium");
        }
        String expanded = configured.startsWith("~")
                ? homeDirectory + configured.substring(1)
                : configured;
        return Path.of(expanded);
    }

    /**
     * Resolve a cached or downloaded executable before launch when Selenium Manager must not
     * touch the network. Never downloads when an executable is already set.
     */
    public static void prepareLaunch(String browser, Map<String, Object> service, String browserBinary) {
        if (service == null || text(service.get("driverExecutable")) != null) {
            return;
        }
        ProxyMode mode = proxyMode(property(PKB_props.PKB_DRIVER_DOWNLOAD_PROXY));
        boolean nativeOn = nativeDownloadsEnabled();
        if (!nativeOn && mode == ProxyMode.DISABLED) {
            throw new IllegalStateException(bothDisabledMessage(browser));
        }
        if (nativeOn || mode == ProxyMode.DISABLED) {
            return;
        }
        try {
            String executable = executableFor(browser, installedBrowserVersion(browser, browserBinary), true);
            if (executable != null) {
                service.put("driverExecutable", executable);
            }
        } catch (Throwable failure) {
            logFallback("Driver download fallback did not run before launch: " + safe(failure));
        }
    }

    /**
     * After a failed launch, try once to place a matching major on disk. Returns null instead of throwing.
     * A session that already started is left alone.
     */
    public static String recoverExecutable(String browser, Throwable failure, String browserBinary, boolean sessionStarted) {
        if (sessionStarted || proxyMode(property(PKB_props.PKB_DRIVER_DOWNLOAD_PROXY)) == ProxyMode.DISABLED) {
            return null;
        }
        try {
            String version = versionFromFailure(failure);
            boolean fromBrowser = false;
            if (version == null) {
                version = installedBrowserVersion(browser, browserBinary);
                fromBrowser = version != null;
            }
            return executableFor(browser, version, !fromBrowser && versionFromFailure(failure) == null);
        } catch (Throwable error) {
            logFallback("Driver download recovery failed: " + safe(error));
            return null;
        }
    }

    /** Cache a closer major for the next launch. Does not replace a driver that already started. */
    public static void noteUntestedBrowser(String browser, String driverLog) {
        try {
            if (driverLog == null || proxyMode(property(PKB_props.PKB_DRIVER_DOWNLOAD_PROXY)) == ProxyMode.DISABLED) {
                return;
            }
            Matcher matcher = UNTESTED.matcher(driverLog);
            if (!matcher.find()) {
                return;
            }
            String major = matcher.group(1);
            enqueueFill(() -> {
                try {
                    executableFor(browser, major, false);
                } catch (Throwable failure) {
                    logFallback("Could not cache the untested " + browser + " major: " + safe(failure));
                }
            });
        } catch (Throwable failure) {
            logFallback("Could not read the driver version warning: " + safe(failure));
        }
    }

    /**
     * The open session keeps {@code currentExecutable}. A "not tested with this browser" warning
     * only fills the cache for the next launch.
     */
    public static String keepOpenDriver(String currentExecutable, String browser, String driverLog) {
        noteUntestedBrowser(browser, driverLog);
        return currentExecutable;
    }

    /** A session-version mismatch is retried once. A second mismatch is not another retry. */
    public static boolean allowsAnotherSessionRetry(boolean sessionAlreadyRetried, Throwable failure) {
        return !sessionAlreadyRetried && isSessionVersionMismatch(failure);
    }

    public static String bothDisabledMessage(String browser) {
        return "Local " + browser + " launch has no driver.service.driverExecutable, "
                + PKB_props.PKB_DRIVER_DOWNLOAD_NATIVE + "=false, and "
                + PKB_props.PKB_DRIVER_DOWNLOAD_PROXY + "=false. "
                + "Selenium Manager will not contact the network, and the driver download fallback is disabled.";
    }

    static void resetForTests() {
        transferOverride = null;
        versionOverride = null;
        synchronousFill = false;
        CHOSEN.set(null);
        ExecutorService existing = fillExecutor;
        fillExecutor = null;
        if (existing != null) {
            existing.shutdownNow();
        }
    }

    static void useTransferForTests(Transfer transfer) {
        transferOverride = transfer;
    }

    static void useVersionForTests(VersionLookup lookup) {
        versionOverride = lookup;
    }

    static void useSynchronousFillForTests() {
        synchronousFill = true;
    }

    static Path driverCacheFile(String browser, String version) {
        String name = "edge".equals(browser) ? "msedgedriver" : "chromedriver";
        return cacheDirectory().resolve(driverFolder(browser)).resolve(cachePlatform(browser)).resolve(version).resolve(name);
    }

    /** Missing scutil, netsh, gsettings, or powershell returns null instead of failing the launch. */
    static String toolOutput(String... command) {
        return commandOutput(Duration.ofSeconds(3), command);
    }

    /** OS proxy discovery is optional. A missing tool is an empty contribution, not an error. */
    static List<String> optionalOsProxies() {
        List<String> found = new ArrayList<>();
        try {
            found.addAll(osSystemProxies());
        } catch (Throwable failure) {
            logFallback("OS proxy discovery skipped: " + safe(failure));
        }
        try {
            found.addAll(osLowerProxies());
        } catch (Throwable failure) {
            logFallback("Lower-level OS proxy discovery skipped: " + safe(failure));
        }
        return found;
    }

    private static String executableFor(String browser, String version, boolean allowStableWhenUnknown) {
        try {
            if (version == null || version.isBlank()) {
                return allowStableWhenUnknown ? stableExecutable(browser) : null;
            }
            String resolved = version.trim();
            if (majorOnly(resolved)) {
                Path cachedMajor = highestCached(browser, resolved);
                if (cachedMajor != null) {
                    return cachedMajor.toString();
                }
                String latest = resolveMajorVersion(browser, resolved);
                if (latest == null) {
                    return null;
                }
                resolved = latest;
            }
            Path exact = exactCached(browser, resolved);
            if (exact != null) {
                return exact.toString();
            }
            Path sameMajor = highestCached(browser, majorOf(resolved));
            try {
                String installed = install(browser, resolved);
                if (installed != null) {
                    return installed;
                }
            } catch (Throwable failure) {
                logFallback("Driver zip for " + browser + " " + resolved + " was not installed: " + safe(failure));
            }
            return sameMajor == null ? null : sameMajor.toString();
        } catch (Throwable failure) {
            logFallback("Driver download failed: " + safe(failure));
            return null;
        }
    }

    private static String stableExecutable(String browser) {
        String stable = resolveStableVersion(browser);
        if (stable == null) {
            return null;
        }
        String executable = executableFor(browser, stable, false);
        enqueueFill(() -> fillRecentMajors(browser, stable));
        return executable;
    }

    /**
     * Background cache fill for one browser. Chrome and Edge each get their own eight majors.
     * Nothing in here is the executable for the session that asked for Stable.
     */
    private static void fillRecentMajors(String browser, String stableVersion) {
        try {
            for (String version : recentMajors(browser, stableVersion)) {
                if (exactCached(browser, version) != null || highestCached(browser, majorOf(version)) != null) {
                    continue;
                }
                executableFor(browser, version, false);
            }
        } catch (Throwable failure) {
            logFallback("Driver cache fill failed: " + safe(failure));
        }
    }

    private static void enqueueFill(Runnable runnable) {
        if (synchronousFill) {
            try {
                runnable.run();
            } catch (Throwable failure) {
                logFallback("Driver cache fill failed: " + safe(failure));
            }
            return;
        }
        filler().execute(runnable);
    }

    private static List<String> recentMajors(String browser, String stableVersion) {
        if ("chrome".equals(browser)) {
            byte[] milestones = getQuiet(List.of(CHROME_METADATA + "latest-versions-per-milestone-with-downloads.json"));
            List<String> parsed = milestones == null ? List.of() : milestoneVersions(milestones);
            if (!parsed.isEmpty()) {
                return parsed.size() > RECENT_MAJORS ? parsed.subList(0, RECENT_MAJORS) : parsed;
            }
        }
        String majorText = majorOf(stableVersion);
        if (majorText == null) {
            return List.of();
        }
        int major = Integer.parseInt(majorText);
        List<String> versions = new ArrayList<>();
        for (int i = 0; i < RECENT_MAJORS && major - i > 0; i++) {
            String resolved = resolveMajorVersion(browser, Integer.toString(major - i));
            if (resolved != null) {
                versions.add(resolved);
            }
        }
        return versions;
    }

    private static List<String> milestoneVersions(byte[] json) {
        try {
            JsonNode milestones = MAPPER.readTree(json).path("milestones");
            List<String> versions = new ArrayList<>();
            if (milestones.isArray()) {
                for (JsonNode node : milestones) {
                    addVersion(versions, node.path("version").asText(null));
                }
            } else if (milestones.isObject()) {
                milestones.fields().forEachRemaining(entry -> {
                    JsonNode node = entry.getValue();
                    addVersion(versions, node.isTextual() ? node.asText() : node.path("version").asText(null));
                });
            }
            versions.sort(DriverDownloadFallback::compareVersions);
            java.util.Collections.reverse(versions);
            return versions;
        } catch (Exception failure) {
            logFallback("Could not read Chrome milestone metadata: " + safe(failure));
            return List.of();
        }
    }

    private static String resolveStableVersion(String browser) {
        if ("edge".equals(browser)) {
            return firstVersion(getQuiet(List.of(EDGE_METADATA + "LATEST_STABLE")));
        }
        String stable = firstVersion(getQuiet(List.of(CHROME_METADATA + "LATEST_RELEASE_STABLE")));
        if (stable != null) {
            return stable;
        }
        byte[] lastKnown = getQuiet(List.of(CHROME_METADATA + "last-known-good-versions-with-downloads.json"));
        stable = stableChannelVersion(lastKnown);
        if (stable != null) {
            return stable;
        }
        byte[] known = getQuiet(List.of(CHROME_METADATA + "known-good-versions-with-downloads.json"));
        return newestKnownVersion(known);
    }

    private static String resolveMajorVersion(String browser, String major) {
        if (major == null) {
            return null;
        }
        if ("edge".equals(browser)) {
            return firstVersion(getQuiet(List.of(edgeMajorMetadataUrl(major, edgeOsToken()))));
        }
        String released = firstVersion(getQuiet(List.of(CHROME_METADATA + "LATEST_RELEASE_" + major)));
        if (released != null) {
            return released;
        }
        byte[] milestones = getQuiet(List.of(CHROME_METADATA + "latest-versions-per-milestone-with-downloads.json"));
        for (String version : milestoneVersions(milestones == null ? new byte[0] : milestones)) {
            if (major.equals(majorOf(version))) {
                return version;
            }
        }
        byte[] perVersion = getQuiet(List.of(CHROME_METADATA + major + ".json"));
        return firstVersion(perVersion);
    }

    private static String stableChannelVersion(byte[] json) {
        if (json == null || json.length == 0) {
            return null;
        }
        try {
            JsonNode channels = MAPPER.readTree(json).path("channels");
            for (String name : List.of("Stable", "stable")) {
                String version = channels.path(name).path("version").asText(null);
                if (version != null && !version.isBlank()) {
                    return version.trim();
                }
            }
        } catch (Exception failure) {
            logFallback("Could not read Chrome stable metadata: " + safe(failure));
        }
        return null;
    }

    private static String newestKnownVersion(byte[] json) {
        if (json == null || json.length == 0) {
            return null;
        }
        try {
            JsonNode versions = MAPPER.readTree(json).path("versions");
            String newest = null;
            if (versions.isArray()) {
                for (JsonNode node : versions) {
                    String version = node.path("version").asText(null);
                    if (version != null && (newest == null || compareVersions(version, newest) > 0)) {
                        newest = version;
                    }
                }
            }
            return newest;
        } catch (Exception failure) {
            logFallback("Could not read Chrome known-good metadata: " + safe(failure));
            return null;
        }
    }

    private static String install(String browser, String version) throws IOException {
        Path cache = cacheDirectory();
        Path directory = cache.resolve(driverFolder(browser)).resolve(cachePlatform(browser)).resolve(version);
        Path binary = binaryIn(directory, browser);
        if (binary != null) {
            return binary.toString();
        }
        Path lockPath = cache.resolve(".pkb-locks").resolve(driverFolder(browser) + "-" + cachePlatform(browser) + "-" + version + ".lock");
        Files.createDirectories(lockPath.getParent());
        try (var channel = java.nio.channels.FileChannel.open(lockPath,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            long deadline = System.nanoTime() + TRANSFER_CAP.toNanos();
            java.nio.channels.FileLock lock = null;
            while (lock == null && System.nanoTime() < deadline) {
                binary = binaryIn(directory, browser);
                if (binary != null) {
                    return binary.toString();
                }
                try {
                    lock = channel.tryLock();
                } catch (IOException overlap) {
                    lock = null;
                }
                if (lock == null) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
            if (lock == null) {
                logFallback("Timed out waiting for the " + browser + " " + version + " driver cache lock");
                return null;
            }
            try {
                binary = binaryIn(directory, browser);
                if (binary != null) {
                    return binary.toString();
                }
                byte[] zip = transfer(zipUrls(browser, version));
                Path unzipped = Files.createTempDirectory(cache, "pkb-driver-");
                try {
                    unzip(zip, unzipped);
                    Path extracted = findBinary(unzipped, browser);
                    if (extracted == null) {
                        throw new IOException("driver binary missing from zip");
                    }
                    Files.createDirectories(directory);
                    Path destination = directory.resolve(extracted.getFileName());
                    Files.copy(extracted, destination, StandardCopyOption.REPLACE_EXISTING);
                    makeExecutable(destination);
                    return destination.toString();
                } finally {
                    deleteTree(unzipped);
                }
            } finally {
                lock.release();
            }
        }
    }

    private static List<String> zipUrls(String browser, String version) {
        if ("edge".equals(browser)) {
            return edgeZipUrls(version, edgeZipLabel());
        }
        return chromeZipUrls(version, chromePlatform());
    }

    private static byte[] getQuiet(List<String> urls) {
        try {
            return transfer(urls);
        } catch (Throwable failure) {
            logFallback("Driver metadata was not read from " + hostOf(urls.isEmpty() ? "" : urls.get(0)) + ": " + safe(failure));
            return null;
        }
    }

    private static byte[] transfer(List<String> urls) throws IOException {
        Transfer hook = transferOverride;
        if (hook != null) {
            return hook.get(urls);
        }
        return transferReal(urls);
    }

    private static byte[] transferReal(List<String> urls) throws IOException {
        if (urls == null || urls.isEmpty()) {
            throw new IOException("no driver download url");
        }
        Probe probe = ensureProxy(urls.get(0));
        if (probe.connectionFailed()) {
            throw new IOException("no usable driver-download connection");
        }
        if (probe.useful()) {
            return probe.body();
        }
        IOException last = new IOException("download failed");
        for (int i = probe.firstUrlUsed() ? 1 : 0; i < urls.size(); i++) {
            try {
                FetchResult result = fetch(CHOSEN.get(), urls.get(i));
                if (result.blocked()) {
                    last = new IOException("blocked host " + hostOf(urls.get(i)));
                    continue;
                }
                if (result.useful()) {
                    return result.body();
                }
                last = new IOException("HTTP " + result.status() + " from " + hostOf(urls.get(i)));
            } catch (IOException failure) {
                last = failure;
                if (connectionFailure(failure)) {
                    break;
                }
            }
        }
        throw last;
    }

    private static Probe ensureProxy(String probeUrl) {
        ChosenProxy existing = CHOSEN.get();
        if (existing != null) {
            return existing.unusable() ? Probe.none() : Probe.ready();
        }
        synchronized (DISCOVERY) {
            existing = CHOSEN.get();
            if (existing != null) {
                return existing.unusable() ? Probe.none() : Probe.ready();
            }
            List<ProxyCandidate> candidates = proxyCandidates(
                    property(PKB_props.PKB_DRIVER_DOWNLOAD_PROXY),
                    System.getenv(),
                    osSystemProxies(),
                    osLowerProxies()
            );
            for (ProxyCandidate candidate : candidates) {
                ChosenProxy chosen = ChosenProxy.from(candidate, corporateCa());
                if (chosen == null) {
                    logFallback("Skipped unreadable " + candidate.source() + " proxy");
                    continue;
                }
                try {
                    FetchResult result = fetch(chosen, probeUrl);
                    CHOSEN.set(chosen);
                    if (result.useful()) {
                        return Probe.hit(result.body());
                    }
                    return Probe.usedFirst();
                } catch (Exception failure) {
                    logFallback("Driver download connection " + candidate.source() + " failed: " + safe(failure));
                }
            }
            CHOSEN.set(ChosenProxy.none());
            return Probe.none();
        }
    }

    private static FetchResult fetch(ChosenProxy chosen, String url) throws IOException {
        if (chosen == null || chosen.unusable()) {
            throw new IOException("no driver download proxy");
        }
        if (chosen.integrated()) {
            return fetchIntegrated(chosen, url);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TRANSFER_CAP)
                .header("Accept", "*/*")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = chosen.client().send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted", interrupted);
        }
        int status = response.statusCode();
        if (status == 407 && windows() && chosen.endpoint() != null && chosen.endpoint().user() == null) {
            closeQuietly(response.body());
            FetchResult integrated = fetchIntegrated(chosen, url);
            if (integrated.useful()) {
                chosen.markIntegrated();
                return integrated;
            }
            throw new IOException("proxy login failed");
        }
        byte[] body = readCapped(response.body());
        if (status == 403 || status == 401 || blockPage(status, body)) {
            return FetchResult.blocked(status);
        }
        return new FetchResult(status, body, false);
    }

    private static FetchResult fetchIntegrated(ChosenProxy chosen, String url) throws IOException {
        if (!windows() || chosen.endpoint() == null || chosen.endpoint().isDirect()) {
            throw new IOException("integrated proxy login is only used for a Windows proxy");
        }
        Path out = Files.createTempFile("pkb-driver-", ".bin");
        try {
            String proxy = "http://" + chosen.endpoint().host() + ":" + chosen.endpoint().port();
            String script = "$ProgressPreference='SilentlyContinue';"
                    + "$req=[System.Net.HttpWebRequest]::Create('" + ps(url) + "');"
                    + "$req.Timeout=20000;$req.ReadWriteTimeout=20000;"
                    + "$req.Proxy=New-Object System.Net.WebProxy('" + ps(proxy) + "',$true);"
                    + "$req.Proxy.Credentials=[System.Net.CredentialCache]::DefaultNetworkCredentials;"
                    + "$resp=$req.GetResponse();"
                    + "$out=[System.IO.File]::Create('" + ps(out.toString()) + "');"
                    + "$resp.GetResponseStream().CopyTo($out);$out.Close();$resp.Close();";
            Integer exit = runCommand(TRANSFER_CAP, "powershell", "-NoProfile", "-NonInteractive", "-Command", script);
            if (exit == null || exit != 0 || !Files.isRegularFile(out) || Files.size(out) == 0) {
                throw new IOException("Windows current-user proxy download failed");
            }
            byte[] body = Files.readAllBytes(out);
            if (body.length > MAX_BYTES) {
                throw new IOException("download exceeded size cap");
            }
            return new FetchResult(200, body, false);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    private static byte[] readCapped(InputStream input) throws IOException {
        long deadline = System.nanoTime() + TRANSFER_CAP.toNanos();
        Thread reader = Thread.currentThread();
        Thread cap = new Thread(() -> {
            try {
                Thread.sleep(TRANSFER_CAP.toMillis());
            } catch (InterruptedException ignored) {
                return;
            }
            closeQuietly(input);
            reader.interrupt();
        }, "pkb-driver-download-cap");
        cap.setDaemon(true);
        cap.start();
        try (input) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (true) {
                if (System.nanoTime() > deadline) {
                    throw new IOException("download exceeded 20s cap");
                }
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                if (out.size() + read > MAX_BYTES) {
                    throw new IOException("download exceeded size cap");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException failure) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
                throw new IOException("download exceeded 20s cap", failure);
            }
            throw failure;
        } finally {
            cap.interrupt();
            Thread.interrupted();
        }
    }

    private static boolean blockPage(int status, byte[] body) {
        if (status == 403 || status == 401) {
            return true;
        }
        if (status < 200 || status >= 300 || body == null || body.length == 0 || looksLikeZip(body)) {
            return false;
        }
        String prefix = decodeEdgeVersionText(body.length > 512 ? java.util.Arrays.copyOf(body, 512) : body)
                .toLowerCase(Locale.ROOT);
        return prefix.contains("<html") || prefix.contains("<!doctype") || prefix.contains("access denied")
                || prefix.contains("request blocked");
    }

    private static boolean looksLikeZip(byte[] body) {
        return body != null && body.length >= 4
                && body[0] == 'P' && body[1] == 'K' && body[2] == 3 && body[3] == 4;
    }

    private static boolean connectionFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof HttpConnectTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof javax.net.ssl.SSLException
                    || current instanceof CertificateException) {
                return true;
            }
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("proxy login") || message.contains("timed out") || message.contains("certificate")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static List<String> osSystemProxies() {
        try {
            if (windows()) {
                return windowsInternetProxy();
            }
            if (mac()) {
                return scutilProxy();
            }
            return linuxEnvironmentProxies();
        } catch (Throwable failure) {
            logFallback("OS proxy discovery skipped: " + safe(failure));
            return List.of();
        }
    }

    private static List<String> osLowerProxies() {
        try {
            if (windows()) {
                return netshProxy();
            }
            if (mac()) {
                return networkSetupProxy();
            }
            return gnomeProxy();
        } catch (Throwable failure) {
            logFallback("Lower-level OS proxy discovery skipped: " + safe(failure));
            return List.of();
        }
    }

    private static List<String> linuxEnvironmentProxies() {
        List<String> found = new ArrayList<>();
        for (String key : List.of("http_proxy", "HTTP_PROXY", "ALL_PROXY", "all_proxy")) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                found.add(value.trim());
            }
        }
        return found;
    }

    private static List<String> windowsInternetProxy() {
        String enabled = reg("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings", "ProxyEnable");
        if (enabled == null || enabled.endsWith("0x0") || enabled.endsWith("0")) {
            return List.of();
        }
        String server = reg("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings", "ProxyServer");
        String value = registryValue(server);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String https = proxyToken(value, "https");
        return List.of(https == null ? value : https);
    }

    private static List<String> netshProxy() {
        String output = commandOutput(Duration.ofSeconds(3), "netsh", "winhttp", "show", "proxy");
        if (output == null || output.toLowerCase(Locale.ROOT).contains("direct access")) {
            return List.of();
        }
        Matcher matcher = Pattern.compile("(?i)proxy server\\(s\\)\\s*:\\s*(\\S+)").matcher(output);
        if (!matcher.find()) {
            return List.of();
        }
        String token = matcher.group(1);
        String https = proxyToken(token, "https");
        return List.of(https == null ? token : https);
    }

    private static List<String> scutilProxy() {
        String output = commandOutput(Duration.ofSeconds(3), "scutil", "--proxy");
        if (output == null) {
            return List.of();
        }
        String host = scutilValue(output, "HTTPSProxy");
        String port = scutilValue(output, "HTTPSPort");
        String enabled = scutilValue(output, "HTTPSEnable");
        if (!"1".equals(enabled) || host == null || port == null) {
            host = scutilValue(output, "HTTPProxy");
            port = scutilValue(output, "HTTPPort");
            enabled = scutilValue(output, "HTTPEnable");
        }
        if (!"1".equals(enabled) || host == null || host.isBlank() || port == null) {
            return List.of();
        }
        return List.of("http://" + host + ":" + port);
    }

    private static List<String> networkSetupProxy() {
        String services = commandOutput(Duration.ofSeconds(3), "networksetup", "-listallnetworkservices");
        if (services == null) {
            return List.of();
        }
        for (String service : services.split("\n")) {
            String name = service.trim();
            if (name.isEmpty() || name.startsWith("An asterisk") || name.startsWith("*")) {
                continue;
            }
            String output = commandOutput(Duration.ofSeconds(3), "networksetup", "-getsecurewebproxy", name);
            if (output == null || output.toLowerCase(Locale.ROOT).contains("enabled: no")) {
                continue;
            }
            Matcher host = Pattern.compile("(?i)server:\\s*(\\S+)").matcher(output);
            Matcher port = Pattern.compile("(?i)port:\\s*(\\d+)").matcher(output);
            if (host.find() && port.find()) {
                return List.of("http://" + host.group(1) + ":" + port.group(1));
            }
        }
        return List.of();
    }

    private static List<String> gnomeProxy() {
        String mode = commandOutput(Duration.ofSeconds(3), "gsettings", "get", "org.gnome.system.proxy", "mode");
        if (mode == null || !mode.contains("manual")) {
            return List.of();
        }
        String host = gsettings("org.gnome.system.proxy.https", "host");
        String port = gsettings("org.gnome.system.proxy.https", "port");
        if (host == null || host.isBlank() || port == null || "0".equals(port)) {
            return List.of();
        }
        return List.of("http://" + host + ":" + port);
    }

    private static String gsettings(String schema, String key) {
        String value = commandOutput(Duration.ofSeconds(3), "gsettings", "get", schema, key);
        if (value == null) {
            return null;
        }
        return value.trim().replace("'", "");
    }

    private static String scutilValue(String output, String key) {
        Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*:\\s*(\\S+)").matcher(output);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String reg(String key, String value) {
        return commandOutput(Duration.ofSeconds(3), "reg", "query", key, "/v", value);
    }

    private static String registryValue(String output) {
        if (output == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("REG_\\w+\\s+(.+)$", Pattern.MULTILINE).matcher(output);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String proxyToken(String value, String scheme) {
        for (String part : value.split("[;\\s]+")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equalsIgnoreCase(scheme)) {
                return part.substring(eq + 1);
            }
        }
        return value.contains("=") ? null : value;
    }

    private static String installedBrowserVersion(String browser, String binary) {
        VersionLookup hook = versionOverride;
        if (hook != null) {
            return hook.version(browser, binary);
        }
        try {
            if (windows()) {
                String exe = binary != null ? binary : findBrowser(browser);
                String fileVersion = exe == null ? null : windowsFileVersion(exe);
                if (fileVersion != null) {
                    return fileVersion;
                }
                String registered = registryBrowserVersion(browser);
                if (registered != null) {
                    return registered;
                }
            }
            String exe = binary != null ? binary : findBrowser(browser);
            if (exe == null) {
                return null;
            }
            return firstVersion(commandOutput(Duration.ofSeconds(5), exe, "--version"));
        } catch (Throwable failure) {
            logFallback("Installed " + browser + " version was not read: " + safe(failure));
            return null;
        }
    }

    private static String windowsFileVersion(String exe) {
        String output = commandOutput(Duration.ofSeconds(5), "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-Item -LiteralPath '" + ps(exe) + "').VersionInfo.FileVersion");
        return firstVersion(output);
    }

    private static String registryBrowserVersion(String browser) {
        String relative = "edge".equals(browser)
                ? "Microsoft\\Edge\\BLBeacon"
                : "Google\\Chrome\\BLBeacon";
        for (String key : List.of(
                "HKCU\\Software\\" + relative,
                "HKLM\\SOFTWARE\\" + relative,
                "HKLM\\SOFTWARE\\WOW6432Node\\" + relative
        )) {
            String version = firstVersion(reg(key, "version"));
            if (version != null) {
                return version;
            }
        }
        return null;
    }

    private static String findBrowser(String browser) {
        boolean edge = "edge".equals(browser);
        List<String> candidates = new ArrayList<>();
        if (windows()) {
            String programFiles = System.getenv("PROGRAMFILES");
            String programFiles86 = System.getenv("PROGRAMFILES(X86)");
            String local = System.getenv("LOCALAPPDATA");
            if (edge) {
                addPath(candidates, programFiles, "Microsoft\\Edge\\Application\\msedge.exe");
                addPath(candidates, programFiles86, "Microsoft\\Edge\\Application\\msedge.exe");
            } else {
                addPath(candidates, programFiles, "Google\\Chrome\\Application\\chrome.exe");
                addPath(candidates, programFiles86, "Google\\Chrome\\Application\\chrome.exe");
                addPath(candidates, local, "Google\\Chrome\\Application\\chrome.exe");
            }
        } else if (mac()) {
            candidates.add(edge
                    ? "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
                    : "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        } else if (edge) {
            candidates.addAll(List.of("microsoft-edge", "microsoft-edge-stable"));
        } else {
            candidates.addAll(List.of("google-chrome", "google-chrome-stable", "chromium", "chromium-browser"));
        }
        for (String candidate : candidates) {
            if (candidate.contains("/") || candidate.contains("\\")) {
                if (Files.isRegularFile(Path.of(candidate))) {
                    return candidate;
                }
            } else {
                String resolved = commandOutput(Duration.ofSeconds(2), "which", candidate);
                if (resolved != null && !resolved.isBlank()) {
                    return resolved.lines().findFirst().orElse("").trim();
                }
            }
        }
        return null;
    }

    private static void addPath(List<String> candidates, String root, String child) {
        if (root != null && !root.isBlank()) {
            candidates.add(Path.of(root, child.split("\\\\")).toString());
        }
    }

    private static Path cacheDirectory() {
        String toml = null;
        String system = System.getProperty("SE_CACHE_PATH");
        String env = System.getenv("SE_CACHE_PATH");
        Path home = Path.of(System.getProperty("user.home", "."));
        if (firstNonBlank(system, env) == null) {
            Path config = home.resolve(".cache").resolve("selenium").resolve("se-config.toml");
            try {
                if (Files.isRegularFile(config)) {
                    toml = Files.readString(config);
                }
            } catch (IOException failure) {
                logFallback("Could not read se-config.toml: " + safe(failure));
            }
        }
        return resolveCacheDirectory(System.getenv(), system, home, toml);
    }

    private static Path exactCached(String browser, String version) {
        if (version == null) {
            return null;
        }
        try {
            return binaryIn(cacheDirectory().resolve(driverFolder(browser)).resolve(cachePlatform(browser)).resolve(version), browser);
        } catch (Throwable failure) {
            return null;
        }
    }

    private static Path highestCached(String browser, String major) {
        if (major == null) {
            return null;
        }
        try {
            Path root = cacheDirectory().resolve(driverFolder(browser)).resolve(cachePlatform(browser));
            if (!Files.isDirectory(root)) {
                return null;
            }
            String best = null;
            Path bestBinary = null;
            try (var paths = Files.list(root)) {
                for (Path path : paths.toList()) {
                    String name = path.getFileName().toString();
                    if (!name.startsWith(major + ".")) {
                        continue;
                    }
                    Path binary = binaryIn(path, browser);
                    if (binary != null && (best == null || compareVersions(name, best) > 0)) {
                        best = name;
                        bestBinary = binary;
                    }
                }
            }
            return bestBinary;
        } catch (Throwable failure) {
            return null;
        }
    }

    private static Path binaryIn(Path directory, String browser) {
        if (directory == null || !Files.isDirectory(directory)) {
            return null;
        }
        String name = "edge".equals(browser) ? "msedgedriver" : "chromedriver";
        Path exe = directory.resolve(name + (windows() ? ".exe" : ""));
        if (Files.isRegularFile(exe) && size(exe) > 0) {
            return exe;
        }
        Path plain = directory.resolve(name);
        if (Files.isRegularFile(plain) && size(plain) > 0) {
            return plain;
        }
        return null;
    }

    private static Path findBinary(Path root, String browser) throws IOException {
        String name = "edge".equals(browser) ? "msedgedriver" : "chromedriver";
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> {
                        String file = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return file.equals(name) || file.equals(name + ".exe");
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void unzip(byte[] zip, Path destination) throws IOException {
        if (!looksLikeZip(zip)) {
            throw new IOException("download was not a zip");
        }
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path target = destination.resolve(entry.getName()).normalize();
                if (!target.startsWith(destination)) {
                    throw new IOException("zip entry escaped the cache directory");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void makeExecutable(Path binary) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(binary);
            permissions.add(PosixFilePermission.OWNER_READ);
            permissions.add(PosixFilePermission.OWNER_WRITE);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(binary, permissions);
        } catch (Exception ignored) {
            // Windows and non-POSIX volumes have no mode bits.
        }
        try {
            if (mac()) {
                commandOutput(Duration.ofSeconds(3), "xattr", "-d", "com.apple.quarantine", binary.toString());
            } else if (windows()) {
                commandOutput(Duration.ofSeconds(3), "powershell", "-NoProfile", "-NonInteractive", "-Command",
                        "Unblock-File -LiteralPath '" + ps(binary.toString()) + "'");
            }
        } catch (Exception ignored) {
            // Quarantine clearing is best-effort.
        }
    }

    private static String driverFolder(String browser) {
        return "edge".equals(browser) ? "msedgedriver" : "chromedriver";
    }

    private static String chromePlatform() {
        if (windows()) {
            return "win64";
        }
        if (mac()) {
            return arm() ? "mac-arm64" : "mac-x64";
        }
        return "linux64";
    }

    private static String cachePlatform(String browser) {
        if (!"edge".equals(browser)) {
            return chromePlatform();
        }
        if (windows()) {
            return "win64";
        }
        if (mac()) {
            return arm() ? "mac-arm64" : "mac64";
        }
        return "linux64";
    }

    private static String edgeZipLabel() {
        if (windows()) {
            return "win64";
        }
        if (mac()) {
            return arm() ? "mac64_m1" : "mac64";
        }
        return "linux64";
    }

    private static String edgeOsToken() {
        if (windows()) {
            return "WINDOWS";
        }
        if (mac()) {
            return "MACOS";
        }
        return "LINUX";
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean mac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static boolean arm() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm");
    }

    private static boolean majorOnly(String version) {
        return version != null && version.indexOf('.') < 0;
    }

    private static String majorOf(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String major = version.split("\\.")[0];
        for (int i = 0; i < major.length(); i++) {
            if (!Character.isDigit(major.charAt(i))) {
                return null;
            }
        }
        return major.isEmpty() ? null : major;
    }

    private static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int av = i < a.length ? parseInt(a[i]) : 0;
            int bv = i < b.length ? parseInt(b[i]) : 0;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return 0;
    }

    private static int parseInt(String text) {
        String digits = text.replaceAll("\\D.*", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException failure) {
            return 0;
        }
    }

    private static String firstVersion(byte[] body) {
        return firstVersion(body == null ? null : decodeEdgeVersionText(body));
    }

    private static String firstVersion(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = VERSION.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void addVersion(List<String> versions, String version) {
        if (version != null && VERSION.matcher(version).find()) {
            versions.add(version.trim());
        }
    }

    private static String property(String key) {
        try {
            PickleballRunner runner = PickleballRunner.rawInstance();
            if (runner != null) {
                String resolved = runner.get(key);
                if (resolved != null) {
                    return resolved;
                }
            }
        } catch (Throwable ignored) {
            // Driver launch can happen without a runner, and a runner failure must not fail the test.
        }
        return System.getProperty(key);
    }

    private static Path corporateCa() {
        String configured = text(property(PKB_props.PKB_DRIVER_DOWNLOAD_CA));
        if (configured == null) {
            return null;
        }
        Path path = Path.of(configured);
        return Files.isRegularFile(path) ? path : null;
    }

    private static String cachePathFromToml(String toml) {
        for (String line : toml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.startsWith("cache-path")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String value = unquoteToml(trimmed.substring(eq + 1).trim());
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String unquoteToml(String raw) {
        String value = raw;
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        return value.replace("\\\\", "\\");
    }

    private static SSLContext sslContext(Path caFile) {
        if (caFile == null) {
            return null;
        }
        try {
            TrustManagerFactory defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            defaults.init((KeyStore) null);
            KeyStore extraStore = KeyStore.getInstance(KeyStore.getDefaultType());
            extraStore.load(null, null);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            int index = 0;
            try (InputStream input = Files.newInputStream(caFile)) {
                for (Certificate certificate : factory.generateCertificates(input)) {
                    extraStore.setCertificateEntry("pkb-driver-ca-" + index++, certificate);
                }
            }
            if (index == 0) {
                return null;
            }
            TrustManagerFactory extra = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            extra.init(extraStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new CombinedTrust(defaults.getTrustManagers(), extra.getTrustManagers())}, null);
            return context;
        } catch (Exception failure) {
            logFallback("Ignoring pkb_driver_download_ca: " + safe(failure));
            return null;
        }
    }

    private static String commandOutput(Duration timeout, String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            byte[] output = process.getInputStream().readAllBytes();
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            try {
                return decoder.decode(java.nio.ByteBuffer.wrap(output)).toString();
            } catch (CharacterCodingException failure) {
                return new String(output, StandardCharsets.ISO_8859_1);
            }
        } catch (Exception failure) {
            return null;
        }
    }

    private static Integer runCommand(Duration timeout, String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue();
        } catch (Exception failure) {
            return null;
        }
    }

    private static void deleteTree(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Antivirus may still be scanning the extracted binary.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException failure) {
            return 0;
        }
    }

    private static void closeQuietly(InputStream input) {
        try {
            if (input != null) {
                input.close();
            }
        } catch (IOException ignored) {
            // Closing a blocked download is the cap.
        }
    }

    private static ExecutorService filler() {
        ExecutorService existing = fillExecutor;
        if (existing != null) {
            return existing;
        }
        synchronized (DISCOVERY) {
            if (fillExecutor == null) {
                fillExecutor = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "pkb-driver-cache-fill");
                    thread.setDaemon(true);
                    thread.setUncaughtExceptionHandler((ignored, failure) ->
                            logFallback("Driver cache fill failed: " + safe(failure)));
                    return thread;
                });
            }
            return fillExecutor;
        }
    }

    private static void addAll(List<ProxyCandidate> candidates, String source, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                candidates.add(new ProxyCandidate(source, value.trim()));
            }
        }
    }

    private static List<ProxyCandidate> dedupe(List<ProxyCandidate> candidates) {
        Map<String, ProxyCandidate> unique = new LinkedHashMap<>();
        for (ProxyCandidate candidate : candidates) {
            unique.putIfAbsent(candidate.value(), candidate);
        }
        return List.copyOf(unique.values());
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "download" : host;
        } catch (Exception failure) {
            return "download";
        }
    }

    private static String messageChain(Throwable failure) {
        StringBuilder out = new StringBuilder();
        Throwable current = failure;
        while (current != null && out.length() < 8000) {
            if (current.getMessage() != null) {
                out.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return out.toString();
    }

    private static String ps(String value) {
        return value.replace("'", "''");
    }

    private static boolean looksLikeUtf16Le(byte[] body) {
        int zeros = 0;
        int samples = Math.min(body.length, 32);
        for (int i = 1; i < samples; i += 2) {
            if (body[i] == 0) {
                zeros++;
            }
        }
        return samples > 8 && zeros > samples / 4;
    }

    private static String safe(Throwable failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return redactSecrets(message);
    }

    static String redactSecrets(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("(?i)(://)[^/\\s@]+@", "$1<redacted>@");
    }

    private static void logFallback(String message) {
        try {
            logWarn(redactSecrets(message));
        } catch (Throwable ignored) {
            // A logging failure must not become a test failure.
        }
    }

    private record FetchResult(int status, byte[] body, boolean blocked) {
        static FetchResult blocked(int status) {
            return new FetchResult(status, null, true);
        }

        boolean useful() {
            return !blocked && status >= 200 && status < 300 && body != null && body.length > 0;
        }
    }

    private record Probe(boolean connectionFailed, boolean firstUrlUsed, byte[] payload) {
        static Probe none() {
            return new Probe(true, false, null);
        }

        static Probe hit(byte[] body) {
            return new Probe(false, true, body);
        }

        static Probe usedFirst() {
            return new Probe(false, true, null);
        }

        static Probe ready() {
            return new Probe(false, false, null);
        }

        boolean useful() {
            return payload != null && payload.length > 0;
        }

        byte[] body() {
            return payload;
        }
    }

    private static final class ChosenProxy {
        private final ProxyEndpoint endpoint;
        private final HttpClient client;
        private final boolean failed;
        private volatile boolean integrated;

        private ChosenProxy(ProxyEndpoint endpoint, HttpClient client, boolean failed) {
            this.endpoint = endpoint;
            this.client = client;
            this.failed = failed;
        }

        static ChosenProxy none() {
            return new ChosenProxy(null, null, true);
        }

        static ChosenProxy from(ProxyCandidate candidate, Path caFile) {
            if (DIRECT.equals(candidate.value())) {
                return new ChosenProxy(ProxyEndpoint.direct(), client(null, null, null, caFile), false);
            }
            ProxyEndpoint endpoint = ProxyEndpoint.parse(candidate.value());
            if (endpoint == null) {
                return null;
            }
            return new ChosenProxy(endpoint, client(endpoint.host(), endpoint.port(), endpoint, caFile), false);
        }

        ProxyEndpoint endpoint() {
            return endpoint;
        }

        HttpClient client() {
            return client;
        }

        boolean unusable() {
            return failed;
        }

        boolean integrated() {
            return integrated;
        }

        void markIntegrated() {
            integrated = true;
        }

        private static HttpClient client(String host, Integer port, ProxyEndpoint endpoint, Path caFile) {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .proxy(proxySelector(host, port));
            SSLContext context = sslContext(caFile);
            if (context != null) {
                builder.sslContext(context);
            }
            if (endpoint != null && endpoint.user() != null) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        if (getRequestorType() == RequestorType.PROXY) {
                            String password = endpoint.password() == null ? "" : endpoint.password();
                            return new PasswordAuthentication(endpoint.user(), password.toCharArray());
                        }
                        return null;
                    }
                });
            }
            return builder.build();
        }

        private static ProxySelector proxySelector(String host, Integer port) {
            if (host == null || port == null) {
                return new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        return List.of(Proxy.NO_PROXY);
                    }

                    @Override
                    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                    }
                };
            }
            return ProxySelector.of(new InetSocketAddress(host, port));
        }
    }

    private record ProxyEndpoint(String host, int port, String user, String password, boolean isDirect) {
        static ProxyEndpoint direct() {
            return new ProxyEndpoint(null, 0, null, null, true);
        }

        static ProxyEndpoint parse(String raw) {
            if (raw == null || raw.isBlank() || DIRECT.equalsIgnoreCase(raw.trim())) {
                return direct();
            }
            String value = raw.trim();
            if (!value.contains("://")) {
                value = "http://" + value;
            }
            try {
                URI uri = URI.create(value);
                if (uri.getHost() == null) {
                    return null;
                }
                String userInfo = uri.getUserInfo();
                String user = null;
                String password = null;
                if (userInfo != null) {
                    int colon = userInfo.indexOf(':');
                    if (colon >= 0) {
                        user = decode(userInfo.substring(0, colon));
                        password = decode(userInfo.substring(colon + 1));
                    } else {
                        user = decode(userInfo);
                    }
                }
                int port = uri.getPort() > 0 ? uri.getPort() : 80;
                return new ProxyEndpoint(uri.getHost(), port, user, password, false);
            } catch (IllegalArgumentException failure) {
                return null;
            }
        }

        private static String decode(String value) {
            try {
                return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException failure) {
                return value;
            }
        }
    }

    private static final class CombinedTrust implements X509TrustManager {
        private final List<X509TrustManager> managers = new ArrayList<>();

        CombinedTrust(TrustManager[] defaults, TrustManager[] extra) {
            add(defaults);
            add(extra);
        }

        private void add(TrustManager[] managers) {
            if (managers == null) {
                return;
            }
            for (TrustManager manager : managers) {
                if (manager instanceof X509TrustManager x509) {
                    this.managers.add(x509);
                }
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager manager : managers) {
                try {
                    manager.checkClientTrusted(chain, authType);
                    return;
                } catch (CertificateException failure) {
                    last = failure;
                }
            }
            if (last != null) {
                throw last;
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager manager : managers) {
                try {
                    manager.checkServerTrusted(chain, authType);
                    return;
                } catch (CertificateException failure) {
                    last = failure;
                }
            }
            if (last != null) {
                throw last;
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> issuers = new ArrayList<>();
            for (X509TrustManager manager : managers) {
                X509Certificate[] accepted = manager.getAcceptedIssuers();
                if (accepted != null) {
                    issuers.addAll(List.of(accepted));
                }
            }
            return issuers.toArray(X509Certificate[]::new);
        }
    }
}
