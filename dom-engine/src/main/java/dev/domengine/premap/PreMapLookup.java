package dev.domengine.premap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.desktop.DesktopTree;
import dev.domengine.image.TemplateMatch;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.session.BrowserSession;
import dev.domengine.session.SessionRegistry;
import dev.domengine.snapshot.CdpSnapshotProducer;
import dev.domengine.snapshot.SnapshotContext;
import dev.domengine.snapshot.SnapshotOptions;
import dev.domengine.snapshot.SnapshotWarnings;
import dev.domengine.tree.DomNode;
import dev.domengine.tree.LiveTree;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Offline lookups read a tree pre-map. {@link #installed()} is {@link #PASS_THROUGH}:
 * with no live session it still answers nothing, which is what a file query and the
 * hook tests lock. A live browser or desktop session is cached here by
 * {@code session + doc_version}. {@code store} does not satisfy {@code current}.
 */
public final class PreMapLookup {
    public static final PreMapLookup PASS_THROUGH = new PreMapLookup(true, null);
    private static final IdentityHashMap<DomNode, PreMap> TREES = new IdentityHashMap<>();
    private static final ConcurrentHashMap<String, PreMap> SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static final ThreadLocal<PreMap> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<String> EXPLAIN = ThreadLocal.withInitial(() -> "none");
    private static final ThreadLocal<Map<String, String>> QUERY = new ThreadLocal<>();
    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicInteger REBUILDS = new AtomicInteger();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final boolean passThrough;
    private final PreMap bound;

    private PreMapLookup(boolean passThrough, PreMap bound) {
        this.passThrough = passThrough;
        this.bound = bound;
    }

    public static PreMapLookup installed() {
        return PASS_THROUGH;
    }

    /** Query flags for this thread, so a rebuild can see an image or look clause. */
    public static void noteQuery(Map<String, String> flags) {
        QUERY.set(flags);
    }

    /** {@code hit} or {@code rebuilt <ms>} after {@link #current(String, String)}. Otherwise {@code none}. */
    public static String statusForExplain() {
        String value = EXPLAIN.get();
        return value == null || value.isBlank() ? "none" : value;
    }

    public static int hitsForTests() {
        return HITS.get();
    }

    public static int rebuildsForTests() {
        return REBUILDS.get();
    }

    public static void resetForTests() {
        synchronized (TREES) {
            TREES.clear();
        }
        SESSIONS.clear();
        CACHE.clear();
        ACTIVE.remove();
        EXPLAIN.remove();
        QUERY.remove();
        HITS.set(0);
        REBUILDS.set(0);
    }

    public static void store(String sessionId, PreMap map) {
        if (sessionId == null || sessionId.isBlank() || map == null) return;
        SESSIONS.put(sessionId, map);
    }

    /** Caches an offline tree by identity and fills indexes on first use. */
    public static PreMapLookup forTree(DomNode root) {
        if (root == null) return PASS_THROUGH;
        PreMap map;
        synchronized (TREES) {
            map = TREES.computeIfAbsent(root, key -> PreMapBuilder.build(
                    key, SnapshotContext.offline(key, new SnapshotOptions(), "")));
        }
        return new PreMapLookup(false, map);
    }

    public Optional<PreMap> current(String sessionId, String docVersion) {
        if (!passThrough) return Optional.empty();
        ACTIVE.remove();
        EXPLAIN.set("none");
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();
        try {
            BrowserSession browser = openBrowser(sessionId);
            if (browser != null) return remember(browserCurrent(browser, docVersion));
            DesktopTree.Session desktop = DesktopTree.open(sessionId);
            if (desktop != null) return remember(desktopCurrent(desktop, docVersion));
        } catch (RuntimeException failure) {
            SnapshotWarnings.once("premap-rebuild " + oneLine(failure));
        }
        return Optional.empty();
    }

    public Optional<List<String>> candidates(String clauseKind, String value) {
        PreMap map = passThrough ? ACTIVE.get() : bound;
        if (map == null || !indexed(map)) return Optional.empty();
        String kind = clauseKind == null ? "" : clauseKind.trim().toLowerCase(Locale.ROOT);
        String needle = value == null ? "" : value;
        return switch (kind) {
            case "role" -> listed(map.byRole().get(norm(needle)));
            case "role-name" -> listed(map.byRoleName().get(roleName(needle)));
            case "text" -> listed(map.byTextToken().get(norm(needle)));
            case "text-exact" -> listed(map.byTextToken().get("\u0002" + norm(needle)));
            case "tag" -> listed(map.byTag().get(norm(needle)));
            case "id" -> listed(map.byId().get(needle.trim()));
            case "class" -> listed(map.byClassName().get(norm(needle)));
            case "xpath-abs" -> listed(map.byXpath().get(needle.trim()));
            case "label" -> Optional.of(labels(map, needle));
            case "section" -> Optional.of(section(map, needle));
            case "css-simple" -> css(map, needle);
            case "image" -> Optional.of(image(map, needle));
            default -> Optional.empty();
        };
    }

    public boolean passThrough() {
        return passThrough;
    }

    static PreMapLookup bound(PreMap map) {
        return new PreMapLookup(false, map);
    }

    private static Optional<PreMap> remember(Built built) {
        if (built == null || built.map == null) return Optional.empty();
        ACTIVE.set(built.map);
        EXPLAIN.set(built.explain);
        return Optional.of(built.map);
    }

    private static BrowserSession openBrowser(String id) {
        try {
            BrowserSession session = SessionRegistry.get().require(id);
            if (session == null || session.isClosed() || session.connection() == null) return null;
            return session;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static Built browserCurrent(BrowserSession session, String docVersion) {
        boolean image = needsImage();
        String version = docVersion == null || docVersion.isBlank() ? readVersion(session.connection()) : docVersion.trim();
        String key = session.id + "\u0000" + version;
        Cached cached = CACHE.get(key);
        if (cached != null && (!image || cached.imaged)) return hit(cached);
        long started = System.nanoTime();
        LiveTree tree = LiveTree.read(session.connection());
        LiveTree.Layout layout = tree.readLayout(session.connection());
        if (version.isBlank()) version = DocVersion.of(tree.loaderId, 0);
        byte[] png = image ? screenshot(session.connection()) : null;
        Map<String, String> flags = new LinkedHashMap<>();
        Map<String, String> query = QUERY.get();
        if (query != null) flags.putAll(query);
        flags.put("session", session.id);
        SnapshotContext context = SnapshotContext.captured(
                SnapshotOptions.fromFlags(flags),
                platform(session.connection()),
                "cdp",
                version,
                "",
                png,
                layout.cssWidth(),
                layout.cssHeight(),
                layout.devicePixelRatio(),
                layout.scrollX(),
                layout.scrollY(),
                layout.offsetX(),
                layout.offsetY(),
                layout.scale()
        );
        PreMap map = PreMapBuilder.build(tree.root, context);
        int nodes = tree.root == null ? 0 : tree.root.count();
        Cached fresh = new Cached(map, nodes, image);
        CACHE.put(key, fresh);
        return rebuild(fresh, started);
    }

    private static Built desktopCurrent(DesktopTree.Session session, String docVersion) {
        boolean image = needsImage();
        String version = docVersion == null || docVersion.isBlank()
                ? "desktop:" + session.sequence()
                : docVersion.trim();
        String key = session.id() + "\u0000" + version;
        Cached cached = CACHE.get(key);
        if (cached != null && (!image || cached.imaged)) return hit(cached);
        long started = System.nanoTime();
        Map<String, String> flags = new LinkedHashMap<>();
        Map<String, String> query = QUERY.get();
        if (query != null) flags.putAll(query);
        flags.put("session", session.id());
        flags.put("doc-version", version);
        if (image && session.live() && (flags.get("screenshot") == null || flags.get("screenshot").isBlank())) {
            flags.put("screenshot", captureDesktop(session));
        }
        SnapshotContext context = SnapshotContext.desktop(
                session.root(),
                SnapshotOptions.fromFlags(flags),
                "",
                session.live() ? session.platform() : "linux-atspi",
                session.live() ? "atspi" : "fixture",
                version
        );
        PreMap map = PreMapBuilder.build(session.root(), context);
        int nodes = session.root() == null ? 0 : session.root().count();
        Cached fresh = new Cached(map, nodes, image && flags.get("screenshot") != null && !flags.get("screenshot").isBlank());
        CACHE.put(key, fresh);
        return rebuild(fresh, started);
    }

    private static Built hit(Cached cached) {
        HITS.incrementAndGet();
        EngineLog.info("premap", "hit nodes=" + cached.nodes + " ms=0");
        return new Built(cached.map, "hit");
    }

    private static Built rebuild(Cached cached, long started) {
        REBUILDS.incrementAndGet();
        long ms = Math.max(0, (System.nanoTime() - started) / 1_000_000L);
        EngineLog.info("premap", "rebuild nodes=" + cached.nodes + " ms=" + ms);
        return new Built(cached.map, "rebuilt " + ms);
    }

    private static String readVersion(CdpConnection connection) {
        String loader = "";
        String frame = "";
        try {
            JsonNode tree = connection.call("Page.getFrameTree", Jsons.object(), TIMEOUT);
            JsonNode top = tree.path("frameTree").path("frame");
            loader = top.path("loaderId").asText("");
            frame = top.path("id").asText("");
        } catch (RuntimeException failure) {
            SnapshotWarnings.once("doc-version " + oneLine(failure));
        }
        return DocVersion.read(connection, frame, loader);
    }

    private static String platform(CdpConnection connection) {
        try {
            JsonNode version = connection.call("Browser.getVersion", Jsons.object(), TIMEOUT);
            String product = version.path("product").asText("");
            if (product.isBlank()) product = version.path("userAgent").asText("");
            return CdpSnapshotProducer.platformOf(product);
        } catch (RuntimeException failure) {
            return "chrome";
        }
    }

    private static byte[] screenshot(CdpConnection connection) {
        try {
            ObjectNode params = Jsons.object();
            params.put("format", "png");
            params.put("fromSurface", true);
            JsonNode shot = connection.call("Page.captureScreenshot", params, TIMEOUT);
            String data = shot.path("data").asText("");
            if (data.isBlank()) return null;
            return Base64.getDecoder().decode(data);
        } catch (RuntimeException failure) {
            SnapshotWarnings.once("premap-screenshot " + oneLine(failure));
            return null;
        }
    }

    private static String captureDesktop(DesktopTree.Session session) {
        try {
            dev.domengine.desktop.DesktopScreenshot.Rect rect = new dev.domengine.desktop.DesktopScreenshot.Rect(0, 0, 1, 1);
            DomNode root = session.root();
            if (root != null && root.hasBox && root.width > 0 && root.height > 0) {
                rect = new dev.domengine.desktop.DesktopScreenshot.Rect(
                        (int) Math.floor(root.x),
                        (int) Math.floor(root.y),
                        Math.max(1, (int) Math.ceil(root.width)),
                        Math.max(1, (int) Math.ceil(root.height))
                );
            }
            dev.domengine.desktop.DesktopScreenshot.Shot shot = dev.domengine.desktop.DesktopScreenshot.capture(rect);
            java.nio.file.Path file = java.nio.file.Files.createTempFile("premap-desk-", ".png");
            java.nio.file.Files.write(file, shot.png());
            return file.toString();
        } catch (RuntimeException | java.io.IOException failure) {
            SnapshotWarnings.once("premap-screenshot " + oneLine(failure instanceof RuntimeException re ? re : new RuntimeException(failure)));
            return "";
        }
    }

    private static boolean needsImage() {
        Map<String, String> flags = QUERY.get();
        if (flags == null) return false;
        for (String key : List.of("pipeline", "selector", "css", "text")) {
            String value = flags.get(key);
            if (value == null) continue;
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.contains("image=") || lower.contains("look=") || lower.contains(" look")) return true;
        }
        return flags.containsKey("look");
    }

    private static String oneLine(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
        int newline = message.indexOf('\n');
        return newline >= 0 ? message.substring(0, newline) : message;
    }

    private static boolean indexed(PreMap map) {
        return !map.byRole().isEmpty() || !map.byTag().isEmpty();
    }

    private static Optional<List<String>> listed(List<String> handles) {
        return Optional.of(handles == null ? List.of() : List.copyOf(handles));
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String roleName(String value) {
        int split = value.indexOf('\t');
        if (split < 0) return norm(value);
        return norm(value.substring(0, split)) + "\t" + norm(value.substring(split + 1));
    }

    private static List<String> labels(PreMap map, String needle) {
        Set<String> targets = new LinkedHashSet<>();
        String exact = "\u0002" + norm(needle);
        List<String> textHits = map.byTextToken().get(exact);
        for (PreMap.LabelRef label : map.labels()) {
            if (label.label().equals(needle.trim()) || (textHits != null && textHits.contains(label.label()))) {
                if (!label.target().isBlank()) targets.add(label.target());
            }
        }
        return List.copyOf(targets);
    }

    private static List<String> section(PreMap map, String needle) {
        Set<String> handles = new LinkedHashSet<>();
        addAll(handles, map.bySectionTitle().get("#" + needle.trim()));
        addAll(handles, map.bySectionTitle().get(norm(needle)));
        return List.copyOf(handles);
    }

    private static Optional<List<String>> css(PreMap map, String selector) {
        Simple simple = Simple.parse(selector);
        if (simple == null) return Optional.empty();
        List<String> hits = null;
        if (!simple.tag.isBlank() && !"*".equals(simple.tag)) hits = intersect(hits, map.byTag().get(simple.tag));
        if (!simple.id.isBlank()) hits = intersect(hits, map.byId().get(simple.id));
        for (String token : simple.classes) hits = intersect(hits, map.byClassName().get(token));
        if (hits == null) return Optional.of(List.of());
        return Optional.of(List.copyOf(hits));
    }

    private static List<String> image(PreMap map, String needle) {
        ParsedImage parsed = ParsedImage.parse(needle);
        if (parsed == null) return List.of();
        TemplateMatch.Features template = new TemplateMatch.Features(parsed.dhash, parsed.mean, 0);
        List<String> hits = new ArrayList<>();
        JsonNode nodes = map.image().path("nodes");
        if (!nodes.isObject()) return hits;
        nodes.fields().forEachRemaining(entry -> {
            JsonNode one = entry.getValue();
            long dhash = hex(one.path("dhash").asText("0"));
            double mean = one.path("mean").asDouble();
            if (TemplateMatch.prefilter(new TemplateMatch.Features(dhash, mean, one.path("stddev").asDouble()), template)) {
                hits.add(entry.getKey());
            }
        });
        return hits;
    }

    private static List<String> intersect(List<String> left, List<String> right) {
        if (right == null) return List.of();
        if (left == null) return right;
        List<String> hits = new ArrayList<>();
        for (String handle : left) if (right.contains(handle)) hits.add(handle);
        return hits;
    }

    private static void addAll(Set<String> into, List<String> handles) {
        if (handles != null) into.addAll(handles);
    }

    private static long hex(String value) {
        try {
            return Long.parseUnsignedLong(value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value, 16);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private record Cached(PreMap map, int nodes, boolean imaged) {
    }

    private record Built(PreMap map, String explain) {
    }

    private record Simple(String tag, String id, List<String> classes) {
        static Simple parse(String selector) {
            if (selector == null) return null;
            String raw = selector.trim();
            if (raw.isEmpty() || raw.indexOf(' ') >= 0 || raw.indexOf('>') >= 0 || raw.indexOf('+') >= 0
                    || raw.indexOf('~') >= 0 || raw.indexOf(',') >= 0 || raw.indexOf(':') >= 0 || raw.indexOf('[') >= 0) {
                return null;
            }
            String tag = "";
            String id = "";
            List<String> classes = new ArrayList<>();
            int i = 0;
            if (Character.isLetter(raw.charAt(0)) || raw.charAt(0) == '*') {
                int j = 1;
                while (j < raw.length()) {
                    char ch = raw.charAt(j);
                    if (!Character.isLetterOrDigit(ch) && ch != '-' && ch != '_') break;
                    j++;
                }
                tag = raw.substring(0, j).toLowerCase(Locale.ROOT);
                i = j;
            }
            while (i < raw.length()) {
                char mark = raw.charAt(i);
                if (mark != '#' && mark != '.') return null;
                int j = i + 1;
                while (j < raw.length()) {
                    char ch = raw.charAt(j);
                    if (!Character.isLetterOrDigit(ch) && ch != '-' && ch != '_') break;
                    j++;
                }
                if (j == i + 1) return null;
                String token = raw.substring(i + 1, j);
                if (mark == '#') id = token;
                else classes.add(token.toLowerCase(Locale.ROOT));
                i = j;
            }
            if (tag.isEmpty() && id.isEmpty() && classes.isEmpty()) return null;
            return new Simple(tag, id, classes);
        }
    }

    private record ParsedImage(long dhash, double mean) {
        static ParsedImage parse(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String[] parts = raw.trim().split("@", 2);
            try {
                long dhash = Long.parseUnsignedLong(parts[0].startsWith("0x") ? parts[0].substring(2) : parts[0], 16);
                double mean = parts.length == 2 ? Double.parseDouble(parts[1]) : 0;
                return new ParsedImage(dhash, mean);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}
