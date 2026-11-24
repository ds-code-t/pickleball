package tools.dscode.optional;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E12 unit: install plan, integrity, cache dir and lock file with an injected fetcher. No network. */
class OptionalAddonsTest {
    @TempDir
    Path temp;
    final List<String> fetched = new ArrayList<>();

    @BeforeEach
    void cache() {
        System.setProperty("pkb_optional_dir", temp.resolve("cache").toString());
    }

    @AfterEach
    void clear() {
        System.clearProperty("pkb_optional_dir");
        OptionalAddons.fetcher(null);
    }

    /** A minimal ustar .tgz of path → bytes. */
    static byte[] tgz(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(raw)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                byte[] h = new byte[512];
                byte[] name = file.getKey().getBytes(StandardCharsets.UTF_8);
                System.arraycopy(name, 0, h, 0, name.length);
                put(h, 100, String.format("%07o", 0644));
                put(h, 108, "0000000");
                put(h, 116, "0000000");
                put(h, 124, String.format("%011o", file.getValue().length));
                put(h, 136, "00000000000");
                for (int i = 148; i < 156; i++) h[i] = ' ';
                h[156] = '0';
                put(h, 257, "ustar");
                int sum = 0;
                for (byte x : h) sum += x & 0xff;
                put(h, 148, String.format("%06o", sum));
                h[154] = 0;
                gz.write(h);
                gz.write(file.getValue());
                gz.write(new byte[(512 - file.getValue().length % 512) % 512]);
            }
            gz.write(new byte[1024]);
        }
        return raw.toByteArray();
    }

    private static void put(byte[] h, int off, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, off, b.length);
    }

    /** Serves registry metadata and the tarball for axe-core; {@code integrity} may be wrong on purpose. */
    void registry(byte[] tarball, String integrity) {
        OptionalAddons.fetcher(url -> {
            fetched.add(url);
            if (url.equals(OptionalAddons.REGISTRY + "axe-core/4.99.0")) {
                return ("{\"name\":\"axe-core\",\"version\":\"4.99.0\",\"license\":\"MPL-2.0\",\"dist\":{\"tarball\":\"" + OptionalAddons.REGISTRY
                        + "axe-core/-/axe-core-4.99.0.tgz\",\"integrity\":\"" + integrity + "\",\"unpackedSize\":123}}").getBytes(StandardCharsets.UTF_8);
            }
            if (url.endsWith("axe-core-4.99.0.tgz")) return tarball;
            throw new IOException("unexpected " + url);
        });
    }

    static byte[] axePackage() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("package/package.json", "{\"name\":\"axe-core\",\"version\":\"4.99.0\"}".getBytes(StandardCharsets.UTF_8));
        files.put("package/axe.min.js", "window.axe={run:async()=>({violations:[]})};".getBytes(StandardCharsets.UTF_8));
        return tgz(files);
    }

    @Test
    void planWithoutConfirmDownloadsNothing() throws Exception {
        registry(axePackage(), "sha512-x");
        int before = OptionalAddons.FETCHES.get();
        ObjectNode plan = OptionalAddons.install("axe", null, null, false);
        assertEquals("plan", plan.path("mode").asText());
        assertEquals("4.14.1", plan.path("version").asText(), "the pinned version");
        assertTrue(plan.path("pinned").asBoolean());
        assertEquals("sha512-FHfa+lmNjQZfJz1q8wKqIDwC6DExcHt/pkjNQb09a3NPjCKg2rhu5qb2S4r76ASyFNNtuFooJpZe37OgudVGtQ==", plan.path("integrity").asText());
        assertEquals(OptionalAddons.REGISTRY + "axe-core/-/axe-core-4.14.1.tgz", plan.path("tarball").asText());
        assertEquals(3_437_349, plan.path("unpackedBytes").asLong());
        assertEquals("axe-core", plan.path("package").asText());
        assertEquals("MPL-2.0", plan.path("license").asText());
        assertTrue(plan.path("licenseNote").asText().contains("never shaded"), plan.toString());
        assertEquals(OptionalAddons.REGISTRY + "axe-core/4.14.1", plan.path("metadata").asText());
        assertEquals(temp.resolve("cache").resolve("axe").toString(), plan.path("dir").asText());
        assertEquals("optional-install axe --confirm", plan.path("next").asText());
        assertEquals(before, OptionalAddons.FETCHES.get());
        assertTrue(fetched.isEmpty());
    }

    @Test
    void confirmVerifiesIntegrityUnpacksAndLocks() throws Exception {
        byte[] tarball = axePackage();
        registry(tarball, OptionalAddons.integrity(tarball));
        IllegalArgumentException unpinned = assertThrows(IllegalArgumentException.class, () -> OptionalAddons.install("axe", "4.99.0", null, true));
        assertTrue(unpinned.getMessage().contains("--integrity=sha512-"), unpinned.getMessage());
        assertTrue(fetched.isEmpty(), "an unpinned version without --integrity fetches nothing");
        ObjectNode done = OptionalAddons.install("axe", "4.99.0", OptionalAddons.integrity(tarball), true);
        assertTrue(done.path("installed").asBoolean(), done.toString());
        assertEquals("4.99.0", done.path("version").asText());
        assertEquals(List.of(OptionalAddons.REGISTRY + "axe-core/4.99.0", OptionalAddons.REGISTRY + "axe-core/-/axe-core-4.99.0.tgz"), fetched);
        Path axe = OptionalAddons.require("axe");
        assertTrue(Files.readString(axe).contains("window.axe"));
        assertTrue(axe.startsWith(temp.resolve("cache")), "the cache dir, not the repo");
        String lock = Files.readString(temp.resolve("cache").resolve("optional.lock.json"));
        assertTrue(lock.contains("\"version\" : \"4.99.0\"") && lock.contains(OptionalAddons.integrity(tarball)), lock);
        assertEquals("4.99.0", OptionalAddons.installed("axe"));
        assertTrue(OptionalAddons.doctorLine().contains("axe:4.99.0"));
        assertTrue(OptionalAddons.uninstall("axe").path("removed").asBoolean());
        assertNull(OptionalAddons.installed("axe"));
    }

    @Test
    void tamperedTarballInstallsNothing() throws Exception {
        byte[] tarball = axePackage();
        String other = OptionalAddons.integrity("other".getBytes(StandardCharsets.UTF_8));
        registry(tarball, other);
        // The registry and --integrity agree, but the tarball's own SHA-512 does not.
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> OptionalAddons.install("axe", "4.99.0", other, true));
        assertTrue(failure.getMessage().contains("integrity mismatch"), failure.getMessage());
        // The registry metadata disagrees with the expected integrity: nothing past the metadata is fetched.
        fetched.clear();
        IllegalStateException metadata = assertThrows(IllegalStateException.class,
                () -> OptionalAddons.install("axe", "4.99.0", OptionalAddons.integrity(tarball), true));
        assertTrue(metadata.getMessage().contains("integrity mismatch") && metadata.getMessage().contains("registry"), metadata.getMessage());
        assertEquals(1, fetched.size(), fetched.toString());
        assertNull(OptionalAddons.installed("axe"));
        assertFalse(Files.exists(temp.resolve("cache").resolve("axe")));
    }

    @Test
    void onlyTheNpmRegistryIsFetchedAndTarEntriesStayInside() throws Exception {
        registry(new byte[0], "sha512-x");
        assertThrows(IOException.class, () -> OptionalAddons.fetch("https://example.com/x.tgz"));
        Map<String, byte[]> evil = new LinkedHashMap<>();
        evil.put("../escape.js", new byte[]{1});
        assertThrows(IOException.class, () -> OptionalAddons.untar(tgz(evil), temp.resolve("x")));
        assertFalse(Files.exists(temp.resolve("escape.js")));
    }

    @Test
    void notInstalledErrorsNameTheFix() {
        for (String name : List.of("axe", "lighthouse", "devtools-mcp")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> OptionalAddons.require(name));
            assertTrue(failure.getMessage().startsWith("error: " + name + " is not installed"), failure.getMessage());
            assertTrue(failure.getMessage().contains("\nfix: pickleball optional-install " + name), failure.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> OptionalAddons.addon("leftpad"));
    }

    @Test
    void lighthouseWithDependenciesNeedsNpmAndSaysSo() throws Exception {
        OptionalAddons.fetcher(url -> {
            fetched.add(url);
            return ("{\"version\":\"13.6.0\",\"dependencies\":{\"a\":\"1\"},\"dist\":{\"tarball\":\"" + OptionalAddons.REGISTRY
                    + "lighthouse/-/lighthouse-13.6.0.tgz\",\"integrity\":\"" + OptionalAddons.addon("lighthouse").integrity() + "\"}}").getBytes(StandardCharsets.UTF_8);
        });
        System.setProperty("pkb_optional_npm", temp.resolve("no-npm-here").toString());
        try {
            // An npm path that does not exist fails at npm, before anything lands in the cache dir.
            assertThrows(Exception.class, () -> OptionalAddons.install("lighthouse", null, null, true));
        } finally {
            System.clearProperty("pkb_optional_npm");
        }
        assertEquals(List.of(OptionalAddons.REGISTRY + "lighthouse/13.6.0"), fetched, "metadata only; no tarball without npm");
        assertNull(OptionalAddons.installed("lighthouse"));
    }

    @Test
    void devtoolsMcpArgumentsAlwaysTurnOffCruxAndUsageStatistics() {
        List<String> args = OptionalAddons.devtoolsMcpArgs(Path.of("/n/node"), Path.of("/c/mcp.js"), "http://127.0.0.1:9222");
        assertEquals(List.of("/n/node", "/c/mcp.js", "--browserUrl=http://127.0.0.1:9222", "--no-performance-crux", "--no-usage-statistics"), args);
        assertTrue(OptionalAddons.devtoolsMcpArgs(Path.of("n"), Path.of("e"), "ws://127.0.0.1:9222/devtools/browser/x").contains("--wsEndpoint=ws://127.0.0.1:9222/devtools/browser/x"));
        assertTrue(OptionalAddons.loopback("ws://localhost:1/x"));
        assertFalse(OptionalAddons.loopback("ws://10.0.0.5:9222/x"));
    }

    @Test
    void noBuildTaskOrDefaultPathDownloads() throws Exception {
        String gradle = Files.readString(Path.of("build.gradle"));
        assertFalse(gradle.contains("optional-install") || gradle.contains("OptionalAddons") || gradle.contains("registry.npmjs.org"), "no build task installs add-ons");
        int before = OptionalAddons.FETCHES.get();
        OptionalAddons.list();
        OptionalAddons.doctorLine();
        OptionalAddons.install("axe", null, null, false);
        assertThrows(IllegalStateException.class, () -> OptionalAddons.require("axe"));
        assertEquals(before, OptionalAddons.FETCHES.get(), "list, doctor, plans and not-installed checks never fetch");
        // The fetch call sites are only inside install(), after the confirm check.
        String source = Files.readString(Path.of("src/main/java/tools/dscode/optional/OptionalAddons.java"));
        int install = source.indexOf("public static ObjectNode install(");
        int confirm = source.indexOf("if (!confirm)", install);
        int end = source.indexOf("public static ObjectNode uninstall(");
        int first = source.indexOf("fetch(REGISTRY");
        assertTrue(first > confirm && first < end, "the metadata fetch follows the confirm check");
        assertTrue(source.indexOf("fetch(addon.tarball(") > confirm && source.indexOf("fetch(addon.tarball(") < end);
        assertEquals(source.lastIndexOf("fetch(REGISTRY"), first, "one metadata fetch site");
    }
}
