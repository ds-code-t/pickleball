package tools.dscode.common.driver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.SessionNotCreatedException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverDownloadFallbackTest {

    @AfterEach
    void reset() {
        DriverDownloadFallback.resetForTests();
        System.clearProperty("pkb_driver_download_proxy");
        System.clearProperty("pkb_driver_download_native");
        System.clearProperty("pkb_driver_download_ca");
        System.clearProperty("SE_CACHE_PATH");
    }

    @Test
    void proxyPropertyParsesEmptyFalseAndUrl() {
        assertEquals(DriverDownloadFallback.ProxyMode.DISCOVER, DriverDownloadFallback.proxyMode(null));
        assertEquals(DriverDownloadFallback.ProxyMode.DISCOVER, DriverDownloadFallback.proxyMode("  "));
        assertEquals(DriverDownloadFallback.ProxyMode.DISABLED, DriverDownloadFallback.proxyMode("false"));
        assertEquals(DriverDownloadFallback.ProxyMode.DISABLED, DriverDownloadFallback.proxyMode(" FALSE "));
        assertEquals(DriverDownloadFallback.ProxyMode.EXPLICIT,
                DriverDownloadFallback.proxyMode("http://user:p%40ss@proxy.example:8080"));
        assertEquals(DriverDownloadFallback.ProxyMode.EXPLICIT, DriverDownloadFallback.proxyMode("http://false:8080"));

        assertTrue(DriverDownloadFallback.nativeEnabled(null));
        assertTrue(DriverDownloadFallback.nativeEnabled(""));
        assertTrue(DriverDownloadFallback.nativeEnabled("true"));
        assertFalse(DriverDownloadFallback.nativeEnabled("false"));
    }

    @Test
    void falseIsNotAProxyHostAndDiscoveryOrderEndsAtDirect() {
        Map<String, String> environment = Map.of(
                "HTTPS_PROXY", "http://env-proxy:8080",
                "SE_PROXY", "http://se-proxy:8080"
        );
        assertTrue(DriverDownloadFallback.proxyCandidates(
                "false", environment, List.of("http://os:8080"), List.of("http://lower:8080")).isEmpty());

        List<DriverDownloadFallback.ProxyCandidate> discovered = DriverDownloadFallback.proxyCandidates(
                "  ", environment, List.of("http://os:8080"), List.of("http://lower:8080"));
        assertEquals(List.of(
                "HTTPS_PROXY",
                "SE_PROXY",
                "os-proxy",
                "os-lower",
                DriverDownloadFallback.DIRECT
        ), discovered.stream().map(DriverDownloadFallback.ProxyCandidate::source).toList());
        assertEquals(DriverDownloadFallback.DIRECT, discovered.get(discovered.size() - 1).value());

        List<DriverDownloadFallback.ProxyCandidate> explicit = DriverDownloadFallback.proxyCandidates(
                "http://user:secret@pkb:8080", environment, List.of(), List.of());
        assertEquals("pkb_driver_download_proxy", explicit.get(0).source());
        assertEquals("http://user:secret@pkb:8080", explicit.get(0).value());
        assertEquals(DriverDownloadFallback.DIRECT, explicit.get(explicit.size() - 1).value());
    }

    @Test
    void chromeZipHostsStayInTheVerifiedOrder() {
        List<String> urls = DriverDownloadFallback.chromeZipUrls("131.0.6778.85", "linux64");
        assertEquals(List.of(
                "https://commondatastorage.googleapis.com/chrome-for-testing-public/131.0.6778.85/linux64/chromedriver-linux64.zip",
                "https://storage.googleapis.com/chrome-for-testing-public/131.0.6778.85/linux64/chromedriver-linux64.zip",
                "https://edgedl.me.gvt1.com/edgedl/chrome/chrome-for-testing/131.0.6778.85/linux64/chromedriver-linux64.zip"
        ), urls);
        String joined = String.join("\n", urls);
        assertFalse(joined.contains("chromedriver.storage.googleapis.com"));
        assertFalse(joined.contains("msedgedriver.azureedge.net"));
        assertFalse(joined.contains("blob.core.windows.net"));
    }

    @Test
    void edgeMetadataUsesAnUnderscoreAndTheLiveHost() {
        assertEquals(
                "https://msedgedriver.microsoft.com/LATEST_RELEASE_154_LINUX",
                DriverDownloadFallback.edgeMajorMetadataUrl("154", "LINUX"));
        List<String> zips = DriverDownloadFallback.edgeZipUrls("154.0.4258.62", "linux64");
        assertEquals(List.of("https://msedgedriver.microsoft.com/154.0.4258.62/edgedriver_linux64.zip"), zips);
        String joined = zips.get(0) + DriverDownloadFallback.edgeMajorMetadataUrl("154", "WINDOWS");
        assertFalse(joined.contains("LATEST_RELEASE_154/"));
        assertFalse(joined.contains("LATEST_RELEASE_154WINDOWS"));
        assertFalse(joined.contains("azureedge.net"));
        assertFalse(joined.contains("blob.core.windows.net"));
    }

    @Test
    void edgeVersionTextIsUtf16LeWithBomAndCrlf() {
        byte[] encoded = "131.0.2903.51\r\n".getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[encoded.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(encoded, 0, withBom, 2, encoded.length);
        assertEquals("131.0.2903.51", DriverDownloadFallback.decodeEdgeVersionText(withBom));
        assertEquals("131.0.6778.85", DriverDownloadFallback.decodeEdgeVersionText(
                "131.0.6778.85\n".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void sessionNotCreatedExceptionExposesTheCurrentBrowserVersion() {
        SessionNotCreatedException failure = new SessionNotCreatedException(
                "session not created: This version of ChromeDriver only supports Chrome version 114\n"
                        + "Current browser version is 132.0.6834.83 with binary path /opt/google/chrome/chrome");
        assertEquals("132.0.6834.83", DriverDownloadFallback.browserVersionFromSessionMessage(failure));
        assertTrue(DriverDownloadFallback.isSessionVersionMismatch(failure));
        assertEquals("131.0.6778.204", DriverDownloadFallback.versionFromDriverDownloadUrl(
                "error downloading https://storage.googleapis.com/chrome-for-testing-public/131.0.6778.204/linux64/chromedriver-linux64.zip"));
        RuntimeException wrapped = new RuntimeException("launch failed", failure);
        assertEquals("132.0.6834.83", DriverDownloadFallback.versionFromFailure(wrapped));
    }

    @Test
    void cachePathPrefersTheSeleniumManagerLocation() throws IOException {
        Path home = Files.createTempDirectory("pkb-cache-home");
        assertEquals(Path.of("/custom/selenium"), DriverDownloadFallback.resolveCacheDirectory(
                Map.of("SE_CACHE_PATH", "/custom/selenium"),
                null,
                home,
                "cache-path = \"/from/toml\""));
        assertEquals(Path.of("/from/property"), DriverDownloadFallback.resolveCacheDirectory(
                Map.of("SE_CACHE_PATH", "/from/env"),
                "/from/property",
                home,
                "cache-path = \"/from/toml\""));
        assertEquals(Path.of("/from/toml"), DriverDownloadFallback.resolveCacheDirectory(
                Map.of(),
                " ",
                home,
                "cache-path = \"/from/toml\"\n"));
        assertEquals(home.resolve(".cache").resolve("selenium"), DriverDownloadFallback.resolveCacheDirectory(
                Map.of(), null, home, "# no cache\n"));
        assertEquals(home.resolve(".cache").resolve("selenium"), DriverDownloadFallback.resolveCacheDirectory(
                Map.of(), null, home, "cache-path = \"~/.cache/selenium\""));
    }

    @Test
    void downloadAndAntivirusIoExceptionsDoNotEscape() throws IOException {
        Path cache = Files.createTempDirectory("pkb-driver-cache");
        System.setProperty("SE_CACHE_PATH", cache.toString());
        System.setProperty("pkb_driver_download_native", "false");
        System.setProperty("pkb_driver_download_proxy", "http://127.0.0.1:9");
        DriverDownloadFallback.useVersionForTests((browser, binary) -> "120.0.6099.109");
        DriverDownloadFallback.useTransferForTests(urls -> {
            throw new IOException("Operation did not complete successfully because the file contains a virus or potentially unwanted software");
        });
        Map<String, Object> service = new LinkedHashMap<>();
        assertDoesNotThrow(() -> DriverDownloadFallback.prepareLaunch("chrome", service, null));
        assertFalse(service.containsKey("driverExecutable"));

        DriverDownloadFallback.useTransferForTests(urls -> "not-a-zip".getBytes(StandardCharsets.US_ASCII));
        assertDoesNotThrow(() -> DriverDownloadFallback.prepareLaunch("edge", service, null));
        assertNull(service.get("driverExecutable"));
    }

    @Test
    void bothDisabledWithoutAnExecutableFailsClosedAndSaysWhy() {
        System.setProperty("pkb_driver_download_native", "false");
        System.setProperty("pkb_driver_download_proxy", "false");
        Map<String, Object> service = new LinkedHashMap<>();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> DriverDownloadFallback.prepareLaunch("chrome", service, null));
        assertTrue(failure.getMessage().contains("driver.service.driverExecutable"));
        assertTrue(failure.getMessage().contains("pkb_driver_download_native=false"));
        assertTrue(failure.getMessage().contains("pkb_driver_download_proxy=false"));

        service.put("driverExecutable", "/opt/drivers/chromedriver");
        assertDoesNotThrow(() -> DriverDownloadFallback.prepareLaunch("chrome", service, null));
        assertEquals("/opt/drivers/chromedriver", service.get("driverExecutable"));
    }

    @Test
    void loggedProxySecretsAreRedacted() {
        assertEquals("connecting to http://<redacted>@proxy:8080",
                DriverDownloadFallback.redactSecrets("connecting to http://user:secret@proxy:8080"));
        assertFalse(DriverDownloadFallback.redactSecrets("http://user:p%40ss@proxy:9").contains("p%40ss"));
    }

    @Test
    void knownMajorIsNotAnArbitraryCachedNeighbor() throws IOException {
        isolatedCache();
        for (int major = 120; major <= 127; major++) {
            seed("chrome", major + ".0.1.1");
        }
        seed("chrome", "131.0.1.1");
        System.setProperty("pkb_driver_download_native", "false");
        System.setProperty("pkb_driver_download_proxy", "http://127.0.0.1:9");
        DriverDownloadFallback.useVersionForTests((browser, binary) -> "131.0.6778.85");
        DriverDownloadFallback.useTransferForTests(urls -> {
            throw new IOException("neighbor majors must not be downloaded for this launch");
        });
        Map<String, Object> service = new LinkedHashMap<>();
        assertDoesNotThrow(() -> DriverDownloadFallback.prepareLaunch("chrome", service, null));
        String executable = String.valueOf(service.get("driverExecutable"));
        assertTrue(executable.contains("131.0.1.1"), executable);
        assertFalse(executable.contains("127.0.1.1"), executable);
        assertFalse(executable.contains("120.0.1.1"), executable);

        service.clear();
        DriverDownloadFallback.useVersionForTests((browser, binary) -> "132.0.6834.83");
        assertDoesNotThrow(() -> DriverDownloadFallback.prepareLaunch("chrome", service, null));
        assertFalse(service.containsKey("driverExecutable"));
    }

    @Test
    void unknownVersionLaunchesStableOnlyAndFillsEightMajorsPerBrowser() throws IOException {
        isolatedCache();
        seed("chrome", "130.0.1.1");
        System.setProperty("pkb_driver_download_native", "false");
        System.setProperty("pkb_driver_download_proxy", "http://127.0.0.1:9");
        DriverDownloadFallback.useSynchronousFillForTests();
        DriverDownloadFallback.useVersionForTests((browser, binary) -> null);
        List<String> zips = new ArrayList<>();
        DriverDownloadFallback.useTransferForTests(urls -> chromePayload(urls, zips));
        Map<String, Object> service = new LinkedHashMap<>();
        assertDoesNotThrow(() -> DriverDownloadFallback.prepareLaunch("chrome", service, null));
        String executable = String.valueOf(service.get("driverExecutable"));
        assertTrue(executable.contains("131.0.6778.204"), executable);
        assertTrue(executable.contains("chromedriver"), executable);
        assertFalse(executable.contains("130.0.1.1"), executable);
        assertFalse(executable.contains("124.0.6400.0"), executable);
        String joined = String.join("\n", zips);
        assertTrue(joined.contains("/131.0.6778.204/"), joined);
        assertTrue(joined.contains("/129.0.6400.0/"), joined);
        assertTrue(joined.contains("/124.0.6400.0/"), joined);
        assertFalse(joined.contains("/130.0.6400.0/"), joined);
        assertFalse(joined.contains("/123."), joined);
        assertFalse(joined.contains("/120."), joined);
        assertFalse(joined.contains("edgedriver"), joined);
        assertFalse(joined.contains("chromedriver.storage.googleapis.com"), joined);
    }

    @Test
    void edgeFillDoesNotReuseChromeMajors() throws IOException {
        isolatedCache();
        seed("chrome", "120.0.1.1");
        System.setProperty("pkb_driver_download_native", "false");
        System.setProperty("pkb_driver_download_proxy", "http://127.0.0.1:9");
        DriverDownloadFallback.useSynchronousFillForTests();
        DriverDownloadFallback.useVersionForTests((browser, binary) -> null);
        List<String> seen = new ArrayList<>();
        DriverDownloadFallback.useTransferForTests(urls -> edgePayload(urls, seen));
        Map<String, Object> service = new LinkedHashMap<>();
        assertDoesNotThrow(() -> DriverDownloadFallback.prepareLaunch("edge", service, null));
        String executable = String.valueOf(service.get("driverExecutable"));
        assertTrue(executable.contains("msedgedriver"), executable);
        assertTrue(executable.contains("131.0.2903.51"), executable);
        assertFalse(executable.contains("120.0.1.1"), executable);
        String joined = String.join("\n", seen);
        assertTrue(joined.contains("LATEST_RELEASE_124_"), joined);
        assertFalse(joined.contains("LATEST_RELEASE_123_"), joined);
        assertFalse(joined.contains("LATEST_RELEASE_131/"), joined);
        assertFalse(joined.contains("LATEST_RELEASE_131WINDOWS"), joined);
        assertFalse(joined.contains("chromedriver"), joined);
        assertFalse(joined.contains("azureedge.net"), joined);
        assertTrue(joined.contains("/131.0.2903.51/edgedriver_"), joined);
        assertTrue(joined.contains("/124.0.6400.0/edgedriver_"), joined);
        assertFalse(joined.contains("/131.0.6400.0/"), joined);
    }

    @Test
    void untestedWarningDoesNotReplaceTheOpenExecutable() throws IOException {
        isolatedCache();
        System.setProperty("pkb_driver_download_native", "false");
        System.setProperty("pkb_driver_download_proxy", "http://127.0.0.1:9");
        DriverDownloadFallback.useSynchronousFillForTests();
        List<String> zips = new ArrayList<>();
        DriverDownloadFallback.useTransferForTests(urls -> {
            String url = urls.get(0);
            if (url.contains("LATEST_RELEASE_131_")) {
                return "131.0.6778.85".getBytes(StandardCharsets.US_ASCII);
            }
            if (url.contains("LATEST_RELEASE_131")) {
                return "131.0.6778.85".getBytes(StandardCharsets.US_ASCII);
            }
            if (url.contains(".zip")) {
                zips.add(url);
                return zipWith(url.contains("edgedriver") ? "msedgedriver" : "chromedriver");
            }
            throw new IOException("warning fill should not sweep other majors: " + url);
        });
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("driverExecutable", "/opt/running/chromedriver");
        String kept = DriverDownloadFallback.keepOpenDriver(
                "/opt/running/chromedriver",
                "chrome",
                "This version of ChromeDriver has not been tested with Chrome version 131.");
        assertEquals("/opt/running/chromedriver", kept);
        assertEquals("/opt/running/chromedriver", service.get("driverExecutable"));
        assertEquals(1, zips.size(), zips.toString());
        assertTrue(zips.get(0).contains("/131.0.6778.85/"), zips.toString());
        assertTrue(Files.isRegularFile(DriverDownloadFallback.driverCacheFile("chrome", "131.0.6778.85")));
    }

    @Test
    void openSessionDoesNotDownloadStable() {
        AtomicInteger calls = new AtomicInteger();
        DriverDownloadFallback.useTransferForTests(urls -> {
            calls.incrementAndGet();
            throw new IOException("session already started");
        });
        assertNull(DriverDownloadFallback.recoverExecutable(
                "chrome",
                new SessionNotCreatedException("Current browser version is 131.0.6778.85"),
                null,
                true));
        assertEquals(0, calls.get());
    }

    @Test
    void versionedZipFailureRetriesTheOtherHosts() throws IOException {
        isolatedCache();
        AtomicReference<List<String>> seen = new AtomicReference<>();
        DriverDownloadFallback.useTransferForTests(urls -> {
            if (urls.get(0).contains(".zip")) {
                seen.set(List.copyOf(urls));
                return zipWith("chromedriver");
            }
            throw new IOException("metadata was not requested");
        });
        String path = DriverDownloadFallback.recoverExecutable(
                "chrome",
                new RuntimeException("error downloading https://storage.googleapis.com/chrome-for-testing-public/"
                        + "131.0.6778.204/linux64/chromedriver-linux64.zip"),
                null,
                false);
        assertNotNull(path);
        assertTrue(path.contains("131.0.6778.204"), path);
        List<String> urls = seen.get();
        assertEquals(List.of(
                "https://commondatastorage.googleapis.com/chrome-for-testing-public/131.0.6778.204/"
                        + platformOf(urls.get(0)) + "/chromedriver-" + platformOf(urls.get(0)) + ".zip",
                "https://storage.googleapis.com/chrome-for-testing-public/131.0.6778.204/"
                        + platformOf(urls.get(0)) + "/chromedriver-" + platformOf(urls.get(0)) + ".zip",
                "https://edgedl.me.gvt1.com/edgedl/chrome/chrome-for-testing/131.0.6778.204/"
                        + platformOf(urls.get(0)) + "/chromedriver-" + platformOf(urls.get(0)) + ".zip"
        ), urls);
        assertFalse(String.join("\n", urls).contains("chromedriver.storage.googleapis.com"));
    }

    @Test
    void sessionMismatchIsRetriedOnlyOnce() {
        SessionNotCreatedException mismatch = new SessionNotCreatedException(
                "Current browser version is 132.0.6834.83");
        assertTrue(DriverDownloadFallback.allowsAnotherSessionRetry(false, mismatch));
        assertFalse(DriverDownloadFallback.allowsAnotherSessionRetry(true, mismatch));
        assertFalse(DriverDownloadFallback.allowsAnotherSessionRetry(false, new RuntimeException("timed out")));
    }

    @Test
    void missingProxyToolsAreSkipped() throws IOException {
        isolatedCache();
        assertNull(DriverDownloadFallback.toolOutput("pkb-missing-scutil"));
        assertNull(DriverDownloadFallback.toolOutput("pkb-missing-netsh"));
        assertNull(DriverDownloadFallback.toolOutput("pkb-missing-powershell"));
        assertDoesNotThrow(DriverDownloadFallback::optionalOsProxies);
        System.setProperty("pkb_driver_download_native", "false");
        System.setProperty("pkb_driver_download_proxy", "http://user:s3cret@127.0.0.1:9");
        DriverDownloadFallback.useVersionForTests((browser, binary) -> "120.0.6099.109");
        DriverDownloadFallback.useTransferForTests(urls -> {
            throw new IOException("powershell was not found; DefaultNetworkCredentials were not sent");
        });
        Map<String, Object> service = new LinkedHashMap<>();
        assertDoesNotThrow(() -> DriverDownloadFallback.prepareLaunch("chrome", service, null));
        assertFalse(service.containsKey("driverExecutable"));
        assertNull(System.getProperty("http.proxyHost"));
        assertNull(System.getProperty("https.proxyHost"));
    }

    private static Path isolatedCache() throws IOException {
        Path cache = Files.createTempDirectory("pkb-driver-cache");
        System.setProperty("SE_CACHE_PATH", cache.toString());
        return cache;
    }

    private static void seed(String browser, String version) throws IOException {
        Path binary = DriverDownloadFallback.driverCacheFile(browser, version);
        Files.createDirectories(binary.getParent());
        Files.write(binary, new byte[]{1, 2, 3});
    }

    private static byte[] chromePayload(List<String> urls, List<String> zips) throws IOException {
        String url = urls.get(0);
        if (url.contains("LATEST_RELEASE_STABLE")) {
            return "131.0.6778.204\n".getBytes(StandardCharsets.US_ASCII);
        }
        if (url.contains("latest-versions-per-milestone")) {
            return milestoneJson(131, 120);
        }
        if (url.contains(".zip")) {
            zips.add(url);
            return zipWith("chromedriver");
        }
        throw new IOException("unexpected chrome url " + url);
    }

    private static byte[] edgePayload(List<String> urls, List<String> seen) throws IOException {
        String url = urls.get(0);
        seen.add(url);
        if (url.contains("LATEST_STABLE")) {
            return "131.0.2903.51".getBytes(StandardCharsets.US_ASCII);
        }
        if (url.contains("LATEST_RELEASE_")) {
            int marker = url.indexOf("LATEST_RELEASE_") + "LATEST_RELEASE_".length();
            int end = marker;
            while (end < url.length() && Character.isDigit(url.charAt(end))) {
                end++;
            }
            return (url.substring(marker, end) + ".0.6400.0").getBytes(StandardCharsets.US_ASCII);
        }
        if (url.contains(".zip")) {
            return zipWith("msedgedriver");
        }
        throw new IOException("unexpected edge url " + url);
    }

    private static byte[] milestoneJson(int newest, int oldest) {
        StringBuilder json = new StringBuilder("{\"milestones\":{");
        for (int major = oldest; major <= newest; major++) {
            if (major != oldest) {
                json.append(',');
            }
            json.append('"').append(major).append("\":{\"version\":\"")
                    .append(major).append(".0.6400.0\"}");
        }
        json.append("}}");
        return json.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] zipWith(String binaryName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("driver/" + binaryName));
            zip.write(new byte[]{1, 2, 3, 4});
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static String platformOf(String url) {
        String marker = "/131.0.6778.204/";
        int start = url.indexOf(marker) + marker.length();
        int end = url.indexOf('/', start);
        return url.substring(start, end);
    }
}
