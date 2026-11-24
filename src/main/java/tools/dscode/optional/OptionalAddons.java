package tools.dscode.optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.browser.BrowserProtocols;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * E12: opt-in OSS add-ons (axe-core, Lighthouse, chrome-devtools-mcp). Nothing here runs at build, test or default
 * runtime: the only network call is {@link #install} with {@code confirm=true}, from the explicit
 * {@code optional-install <name> --confirm} command. Packages come from the npm registry, are checked against the
 * registry's SHA-512 integrity, unpacked into the user cache dir (never the repo or the jar) and recorded in a lock
 * file. They are run with the Node in Playwright's driver bundle, so no user Node install is needed.
 */
public final class OptionalAddons {
    static final ObjectMapper JSON = new ObjectMapper();
    public static final String REGISTRY = "https://registry.npmjs.org/";

    /** One add-on: npm package, the file pickleball uses, license and how it is distributed. */
    public record Addon(String name, String pkg, String entry, String license, String licenseNote, String source, boolean needsDependencies,
                        String version, String integrity, long unpackedBytes) {
        public String tarball(String v) {
            String base = pkg.contains("/") ? pkg.substring(pkg.indexOf('/') + 1) : pkg;
            return REGISTRY + pkg + "/-/" + base + "-" + v + ".tgz";
        }
    }

    public static final Map<String, Addon> ADDONS = new LinkedHashMap<>();

    static {
        ADDONS.put("axe", new Addon("axe", "axe-core", "axe.min.js", "MPL-2.0",
                "weak copyleft: used unmodified as a separate file, never shaded or bundled; source https://github.com/dequelabs/axe-core",
                "https://github.com/dequelabs/axe-core", false, "4.14.1",
                "sha512-FHfa+lmNjQZfJz1q8wKqIDwC6DExcHt/pkjNQb09a3NPjCKg2rhu5qb2S4r76ASyFNNtuFooJpZe37OgudVGtQ==", 3_437_349));
        ADDONS.put("lighthouse", new Addon("lighthouse", "lighthouse", "cli/index.js", "Apache-2.0",
                "user-installed, not distributed with pickleball", "https://github.com/GoogleChrome/lighthouse", true, "13.6.0",
                "sha512-2/HUPUpQyGm01VEgYCUjSlNZY7Zkhk1v3QI4c7UTbnaoRB7HF0qYMPzzaiidNf5iqw5wnmtm9LpGawKKqCfLmg==", 19_351_495));
        ADDONS.put("devtools-mcp", new Addon("devtools-mcp", "chrome-devtools-mcp", "out/Default/gen/src/bin/chrome-devtools-mcp.js", "Apache-2.0",
                "user-installed, not distributed with pickleball", "https://github.com/ChromeDevTools/chrome-devtools-mcp", false, "1.13.0",
                "sha512-Yf4qhS0+2oo7c+gOySMDHQt4ZL/yKXG2Mxxzjk0gcArINtcQ3tLiQVzAW6g2nsyQzAGhkcLwWWBAtlx3Bg+Spg==", 16_448_686));
    }

    /** Fetches a URL. Tests inject one; the default is only reached from {@link #install} with confirm. */
    public interface Fetcher {
        byte[] get(String url) throws IOException;
    }

    /** Every call of the real or injected fetcher (the no-download assertions read it). */
    public static final AtomicInteger FETCHES = new AtomicInteger();
    static volatile Fetcher fetcher = OptionalAddons::httpGet;

    private OptionalAddons() {
    }

    public static void fetcher(Fetcher next) {
        fetcher = next == null ? OptionalAddons::httpGet : next;
    }

    static byte[] fetch(String url) throws IOException {
        if (!url.startsWith(REGISTRY)) throw new IOException("only " + REGISTRY + " is used, not " + url);
        FETCHES.incrementAndGet();
        return fetcher.get(url);
    }

    static byte[] httpGet(String url) throws IOException {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " for " + url);
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", interrupted);
        }
    }

    // ---- locations ----

    /** {@code pkb_optional_dir}, else the user cache dir: LOCALAPPDATA on Windows, XDG_CACHE_HOME, else ~/.cache. */
    public static Path cacheDir() {
        String custom = BrowserProtocols.setting("pkb_optional_dir");
        if (custom != null) return Path.of(custom).toAbsolutePath();
        String local = System.getenv("LOCALAPPDATA");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") && local != null) return Path.of(local, "pickleball", "optional");
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("pickleball").resolve("optional");
    }

    static Path lockFile() {
        return cacheDir().resolve("optional.lock.json");
    }

    public static Addon addon(String name) {
        Addon addon = ADDONS.get(name == null ? "" : name.trim());
        if (addon == null) throw new IllegalArgumentException("error: unknown add-on " + name + "\nfix: use one of " + String.join(", ", ADDONS.keySet()));
        return addon;
    }

    static ObjectNode lock() {
        try {
            Path file = lockFile();
            return Files.exists(file) ? (ObjectNode) JSON.readTree(file.toFile()) : JSON.createObjectNode();
        } catch (IOException | ClassCastException broken) {
            return JSON.createObjectNode();
        }
    }

    /** The installed version, or null. */
    public static String installed(String name) {
        JsonNode entry = lock().path(name);
        if (entry.isMissingNode()) return null;
        Addon addon = addon(name);
        return Files.exists(packageDir(addon).resolve(addon.entry())) ? entry.path("version").asText(null) : null;
    }

    static Path packageDir(Addon addon) {
        Path dir = cacheDir().resolve(addon.name());
        return addon.needsDependencies() ? dir.resolve("node_modules").resolve(addon.pkg()) : dir.resolve("package");
    }

    /** The add-on's entry file, or an {@code error:}/{@code fix:} that says how to install it. */
    public static Path require(String name) {
        Addon addon = addon(name);
        if (installed(name) == null) {
            throw new IllegalStateException("error: " + name + " is not installed (optional, user-installed, " + addon.license() + ")\n"
                    + "fix: pickleball optional-install " + name + " (shows the download plan; add --confirm to install)");
        }
        return packageDir(addon).resolve(addon.entry());
    }

    // ---- node ----

    /** Node from Playwright's driver bundle ({@code PLAYWRIGHT_NODEJS_PATH} wins); never downloads browsers. */
    public static Path node() {
        String custom = System.getenv("PLAYWRIGHT_NODEJS_PATH");
        if (custom != null && !custom.isBlank()) return Path.of(custom);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        Path dir = com.microsoft.playwright.impl.driver.Driver.ensureDriverInstalled(env, false).driverDir();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return dir.resolve(windows ? "node.exe" : "node");
    }

    /** npm: {@code pkb_optional_npm}, else an npm beside the bundled Node (Playwright ships none today), else null. */
    static Path npm(Path node) {
        String custom = BrowserProtocols.setting("pkb_optional_npm");
        if (custom != null) return Path.of(custom);
        if (node == null) return null;
        Path cli = node.getParent().resolve("node_modules").resolve("npm").resolve("bin").resolve("npm-cli.js");
        return Files.exists(cli) ? cli : null;
    }

    // ---- verbs ----

    /** The plan: what would be downloaded, from where, its size and license, and where it goes. No network. */
    public static ObjectNode plan(String name, String version, String integrity) {
        Addon addon = addon(name);
        String v = version == null || version.isBlank() ? addon.version() : version;
        String expected = expected(addon, v, integrity);
        ObjectNode out = JSON.createObjectNode().put("op", "optional-install").put("name", name).put("package", addon.pkg()).put("version", v);
        out.put("license", addon.license()).put("licenseNote", addon.licenseNote()).put("source", addon.source());
        out.put("metadata", REGISTRY + addon.pkg() + "/" + v).put("tarball", addon.tarball(v)).put("integrity", expected);
        if (v.equals(addon.version())) out.put("unpackedBytes", addon.unpackedBytes());
        out.put("pinned", v.equals(addon.version()));
        if (addon.needsDependencies()) out.put("needs", "npm (its npm dependencies are installed with --ignore-scripts); pkb_optional_npm when Playwright's bundle has none");
        out.put("dir", cacheDir().resolve(addon.name()).toString()).put("lock", lockFile().toString());
        out.put("external", "external download from the npm registry; never at build, test or default runtime");
        return out;
    }

    /** The pinned integrity, or for another version the one the user passed with --integrity. */
    static String expected(Addon addon, String version, String integrity) {
        if (version.equals(addon.version())) {
            if (integrity != null && !integrity.equals(addon.integrity())) throw new IllegalArgumentException("error: --integrity differs from the pinned " + addon.pkg() + "@" + version + "\nfix: drop --integrity");
            return addon.integrity();
        }
        if (integrity == null || !integrity.startsWith("sha512-")) {
            throw new IllegalArgumentException("error: " + addon.pkg() + "@" + version + " is not the pinned " + addon.version() + "\nfix: pass --integrity=sha512-... from the npm registry for that version, or omit --version");
        }
        return integrity;
    }

    /** {@code optional-install}: without confirm, prints the plan and downloads nothing. */
    public static ObjectNode install(String name, String version, String integrity, boolean confirm) throws IOException {
        Addon addon = addon(name);
        ObjectNode out = plan(name, version, integrity);
        String v = out.path("version").asText();
        String expected = out.path("integrity").asText();
        if (!confirm) {
            out.put("mode", "plan").put("installed", installed(name) != null);
            out.put("next", "optional-install " + name + (v.equals(addon.version()) ? "" : " --version=" + v + " --integrity=" + expected) + " --confirm");
            return out.put("ok", true);
        }
        out.put("mode", "install");
        JsonNode meta = JSON.readTree(fetch(REGISTRY + addon.pkg() + "/" + v));
        String integrity0 = meta.path("dist").path("integrity").asText("");
        if (!expected.equals(integrity0)) {
            throw new IllegalStateException("error: integrity mismatch for " + addon.pkg() + "@" + v + " (registry " + integrity0 + ", pinned " + expected + ")\nfix: rerun; nothing was installed");
        }
        String integrity1 = expected;
        Path dir = cacheDir().resolve(addon.name());
        Path staging = cacheDir().resolve("." + addon.name() + "-staging");
        deleteTree(staging);
        if (addon.needsDependencies() && meta.path("dependencies").size() > 0) {
            Path node = node();
            Path npm = npm(node);
            if (npm == null) {
                throw new IllegalStateException("error: " + name + " has " + meta.path("dependencies").size() + " npm dependencies and needs npm; Playwright's driver bundle has none\n"
                        + "fix: set pkb_optional_npm=<path to npm-cli.js or npm> and rerun optional-install " + name + " --confirm");
            }
            Files.createDirectories(staging);
            List<String> command = new ArrayList<>();
            if (npm.toString().endsWith(".js")) command.add(node.toString());
            command.add(npm.toString());
            command.addAll(List.of("install", "--prefix", staging.toString(), "--ignore-scripts", "--no-audit", "--no-fund", "--omit=dev",
                    addon.pkg() + "@" + v));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (process.waitFor() != 0) throw new IllegalStateException("error: npm install failed: " + firstLine(log) + "\nfix: rerun with network access, or check pkb_optional_npm");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", interrupted);
            }
            JsonNode lockJson = JSON.readTree(staging.resolve("package-lock.json").toFile());
            String locked = lockJson.path("packages").path("node_modules/" + addon.pkg()).path("integrity").asText("");
            if (!integrity1.equals(locked)) throw new IllegalStateException("error: integrity mismatch for " + addon.pkg() + " (npm lock " + locked + ")\nfix: rerun; nothing was installed");
        } else {
            byte[] tarball = fetch(addon.tarball(v));
            verify(tarball, integrity1, addon.pkg());
            untar(tarball, staging);
        }
        deleteTree(dir);
        Files.createDirectories(dir.getParent());
        Files.move(staging, dir);
        ObjectNode lock = lock();
        ObjectNode entry = lock.putObject(name);
        entry.put("package", addon.pkg()).put("version", v).put("integrity", integrity1)
                .put("tarball", addon.tarball(v)).put("license", addon.license()).put("installedAt", OffsetDateTime.now().toString());
        Files.createDirectories(lockFile().getParent());
        Files.writeString(lockFile(), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(lock));
        if (installed(name) == null) throw new IllegalStateException("error: " + addon.entry() + " is missing from " + addon.pkg() + "\nfix: try another --version");
        return out.put("installed", true).put("ok", true);
    }

    public static ObjectNode uninstall(String name) throws IOException {
        Addon addon = addon(name);
        Path dir = cacheDir().resolve(addon.name());
        boolean had = Files.exists(dir);
        deleteTree(dir);
        ObjectNode lock = lock();
        boolean listed = lock.remove(name) != null;
        if (Files.exists(lockFile())) Files.writeString(lockFile(), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(lock));
        return JSON.createObjectNode().put("ok", true).put("op", "optional-uninstall").put("name", name).put("removed", had || listed).put("dir", dir.toString());
    }

    public static ObjectNode list() {
        ObjectNode out = JSON.createObjectNode().put("ok", true).put("op", "optional-list").put("dir", cacheDir().toString());
        ArrayNode items = out.putArray("addons");
        for (Addon addon : ADDONS.values()) {
            String version = installed(addon.name());
            ObjectNode item = items.addObject().put("name", addon.name()).put("package", addon.pkg()).put("license", addon.license())
                    .put("installed", version != null).put("external", "external download on install");
            if (version != null) item.put("version", version);
        }
        return out;
    }

    /** One doctor line: each add-on installed (with version) or not, and that installing downloads. */
    public static String doctorLine() {
        StringBuilder out = new StringBuilder("optional=");
        for (Addon addon : ADDONS.values()) {
            String version = installed(addon.name());
            out.append(addon.name()).append(':').append(version == null ? "not-installed" : version).append(' ');
        }
        return out.append("(external download on install: optional-install <name> --confirm)").toString();
    }

    // ---- devtools-mcp bridge ----

    /** The chrome-devtools-mcp command line: always without CrUX lookups and usage statistics. */
    public static List<String> devtoolsMcpArgs(Path node, Path entry, String endpoint) {
        List<String> args = new ArrayList<>(List.of(node.toString(), entry.toString()));
        args.add(endpoint.startsWith("ws") ? "--wsEndpoint=" + endpoint : "--browserUrl=" + endpoint);
        args.add("--no-performance-crux");
        args.add("--no-usage-statistics");
        return args;
    }

    public static boolean loopback(String endpoint) {
        try {
            String host = URI.create(endpoint).getHost();
            return host != null && (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1"));
        } catch (RuntimeException bad) {
            return false;
        }
    }

    // ---- tarballs ----

    static void verify(byte[] data, String integrity, String pkg) {
        try {
            String actual = "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(data));
            if (!actual.equals(integrity)) {
                throw new IllegalStateException("error: integrity mismatch for " + pkg + " (expected " + integrity + ")\nfix: rerun; nothing was installed");
            }
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Unpacks a .tgz (ustar, with pax path records) under {@code dir}; refuses entries that leave it. */
    static void untar(byte[] tgz, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path root = dir.toAbsolutePath().normalize();
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(tgz))) {
            byte[] header = new byte[512];
            String paxPath = null;
            while (in.readNBytes(header, 0, 512) == 512) {
                if (header[0] == 0) break;
                String name = cstring(header, 0, 100);
                String prefix = cstring(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                long size = Long.parseLong(cstring(header, 124, 12).trim().isEmpty() ? "0" : cstring(header, 124, 12).trim(), 8);
                char type = (char) header[156];
                byte[] body = in.readNBytes((int) size);
                long pad = (512 - size % 512) % 512;
                in.readNBytes((int) pad);
                if (type == 'x') {
                    for (String line : new String(body, StandardCharsets.UTF_8).split("\n")) {
                        int eq = line.indexOf(" path=");
                        if (eq > 0) paxPath = line.substring(eq + 6);
                    }
                    continue;
                }
                if (type == 'g') continue;
                if (paxPath != null) {
                    name = paxPath;
                    paxPath = null;
                }
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root)) throw new IOException("refused tar entry outside the add-on dir: " + name);
                if (type == '5') {
                    Files.createDirectories(target);
                } else if (type == '0' || type == 0) {
                    Files.createDirectories(target.getParent());
                    Files.write(target, body);
                }
            }
        }
    }

    static String cstring(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    static String firstLine(String text) {
        String t = text == null ? "" : text.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl);
    }

    public static String integrity(byte[] data) throws Exception {
        return "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(data));
    }
}
