package dev.domengine.query;

import dev.domengine.cdp.CdpException;
import dev.domengine.image.TemplateMatch;
import dev.domengine.tree.DomNode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OCR for image, SVG, and icon elements. The cache key is the SHA-256 of the crop.
 * One process cache is shared by the human and full-access views. This build does
 * not download, execute, or bundle a runtime.
 */
public final class OcrHook {
    static final String PROPERTY = "pkb_ocr_runtime";
    /** Property or env value that selects {@link OnnxOcrRuntime}. It is not a file path. */
    static final String ONNX_SLOT = "onnx";
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger READS = new AtomicInteger();
    private static final ThreadLocal<Set<String>> VIEWS = new ThreadLocal<>();
    private static final ThreadLocal<String> VIEW = new ThreadLocal<>();

    private OcrHook() {
    }

    public record Result(boolean ok, String text, String error, String code, String hash, boolean fromCache) {
        static Result hit(String text, String hash, boolean fromCache) {
            return new Result(true, text == null ? "" : text, "", "", hash, fromCache);
        }

        static Result fail(String error, String code, String hash) {
            return new Result(false, "", error, code, hash == null ? "" : hash, false);
        }
    }

    /**
     * Image-like elements: tag {@code img} or {@code svg}, role {@code img}, {@code image},
     * {@code graphics-symbol}, {@code graphics-document}, or {@code icon}, an icon-font
     * {@code i} or {@code span}, and a desktop icon after {@code NativeDesktop.mapRole}
     * (role {@code icon} or {@code img}, platform role {@code AXImage}, {@code Image}, or {@code icon}).
     * A {@code use} inside an {@code svg} is not a second target; the svg is.
     */
    public static boolean target(DomNode node) {
        if (node == null || node.nodeType != 1) return false;
        String tag = tag(node);
        if ("use".equals(tag) && insideSvg(node)) return false;
        if ("img".equals(tag) || "svg".equals(tag)) return true;
        if (iconRole(Query.roleOf(node)) || iconRole(attr(node, "platform-role")) || iconRole(attr(node, "data-platform-role"))) {
            return true;
        }
        return ("i".equals(tag) || "span".equals(tag)) && iconClass(attr(node, "class"));
    }

    private static boolean iconRole(String role) {
        if (role == null || role.isBlank()) return false;
        return switch (role.trim().toLowerCase(Locale.ROOT)) {
            case "img", "image", "icon", "graphics-symbol", "graphics-document", "aximage" -> true;
            default -> false;
        };
    }

    /** Icon-font class tokens. A plain word such as {@code biography} does not match. */
    private static boolean iconClass(String className) {
        if (className == null || className.isBlank()) return false;
        for (String token : className.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            String value = token.toLowerCase(Locale.ROOT);
            if (value.startsWith("fa-") || "fa".equals(value) || "fas".equals(value) || "far".equals(value) || "fab".equals(value)) {
                return true;
            }
            if ("bi".equals(value) || value.startsWith("bi-")) return true;
            if (value.startsWith("material-icons") || value.startsWith("material-symbols")) return true;
            if (value.startsWith("glyphicon")) return true;
            if ("icon".equals(value) || value.startsWith("icon-") || value.endsWith("-icon")) return true;
        }
        return false;
    }

    private static boolean insideSvg(DomNode node) {
        for (DomNode parent = node.parent; parent != null; parent = parent.parent) {
            if (parent.nodeType == 1 && "svg".equals(tag(parent))) return true;
        }
        return false;
    }

    private static String tag(DomNode node) {
        return node.localName == null ? "" : node.localName.toLowerCase(Locale.ROOT);
    }

    private static String attr(DomNode node, String name) {
        String value = node.attributes.get(name);
        return value == null ? "" : value;
    }

    /**
     * Recognizes one element. Returns null when the element is not a target, has no crop,
     * or is outside the installed human mask. A null result is not a cache miss and does
     * not read the runtime. A cache hit does not read the runtime.
     */
    public static Result recognize(DomNode node, TemplateMatch.Gray crop) {
        if (!target(node) || crop == null || crop.pixels() == null || crop.pixels().length == 0) return null;
        if (!ViewMask.allows(node)) return null;
        String hash = cacheKey(crop);
        String cached = CACHE.get(hash);
        if (cached != null) return Result.hit(cached, hash, true);
        OcrRuntime runtime = select(System.getProperty(PROPERTY), System.getenv("PKB_OCR_RUNTIME"));
        if (runtime == null) return Result.fail("OCR runtime not installed", "ocr_runtime_missing", hash);
        if (runtime.opensRuntime()) READS.incrementAndGet();
        Result result = runtime.recognize(crop, hash);
        if (!result.ok()) return result;
        CACHE.put(hash, result.text());
        return Result.hit(result.text(), hash, false);
    }

    /** SHA-256 hex of the crop width, height, and pixels. */
    public static String cacheKey(TemplateMatch.Gray crop) {
        byte[] bytes = bytes(crop);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value));
            return hex.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256", failure);
        }
    }

    public static int runtimeReads() {
        return READS.get();
    }

    public static void resetForTests() {
        CACHE.clear();
        READS.set(0);
        VIEWS.remove();
        VIEW.remove();
    }

    /**
     * Arms the per-view OCR gate for this query. The active view is {@code human}
     * when {@code exposure=human} and the mask is on, otherwise {@code full}.
     * {@code ocr-views} is a comma list of {@code human} and {@code full}. Blank, or a
     * value with no tokens, means both. A token other than those two throws
     * {@code invalid ocr-views: <token>}. A view that is not listed is not recognized:
     * the OCR stage returns zero hits and does not read the runtime. The shared hash
     * cache is left as it is.
     */
    public static void armViews(Map<String, String> flags) {
        if (flags == null) {
            VIEWS.remove();
            VIEW.remove();
            return;
        }
        VIEWS.set(parseViews(flags.get("ocr-views")));
        VIEW.set(ViewMask.active(flags) ? "human" : "full");
    }

    /**
     * Rejects an unknown {@code ocr-views} token before the query installs viewport
     * or mask state. Blank and a token-less list are both views.
     */
    static void requireViews(Map<String, String> flags) {
        if (flags == null) return;
        parseViews(flags.get("ocr-views"));
    }

    /** False when this query armed a view list that omits the active view. Unarmed queries allow both. */
    public static boolean viewEnabled() {
        Set<String> views = VIEWS.get();
        if (views == null) return true;
        return views.contains(activeView());
    }

    public static String activeView() {
        String view = VIEW.get();
        return view == null || view.isBlank() ? "full" : view;
    }

    private static Set<String> parseViews(String raw) {
        if (raw == null || raw.isBlank()) return Set.of("human", "full");
        Set<String> views = new LinkedHashSet<>();
        List<String> bad = new ArrayList<>();
        for (String part : raw.split(",", -1)) {
            String token = part.trim().toLowerCase(Locale.ROOT);
            if (token.isEmpty()) continue;
            if ("human".equals(token) || "full".equals(token)) views.add(token);
            else bad.add(token);
        }
        if (!bad.isEmpty()) {
            throw new CdpException("invalid ocr-views: " + String.join(", ", bad));
        }
        if (views.isEmpty()) return Set.of("human", "full");
        return Set.copyOf(views);
    }

    /**
     * Property, then environment. {@code onnx} selects {@link OnnxOcrRuntime} and does not
     * open a file. A blank property falls through. A set property that is not an existing
     * file does not fall through. A directory is not a file. Null means no runtime is installed.
     */
    static OcrRuntime select(String property, String environment) {
        String chosen = blank(property) ? environment : property;
        if (blank(chosen)) return null;
        if (ONNX_SLOT.equalsIgnoreCase(chosen.trim())) return OnnxOcrRuntime.INSTANCE;
        Path path = runtime(property, environment);
        return path == null ? null : new StubOcrRuntime(path);
    }

    /**
     * Property, then environment. A blank property falls through. A set property that is
     * not an existing file does not fall through. A directory is not a file.
     */
    static Path runtime(String property, String environment) {
        String chosen = blank(property) ? environment : property;
        if (blank(chosen)) return null;
        Path path = Path.of(chosen.trim());
        return Files.isRegularFile(path) ? path : null;
    }

    private static byte[] bytes(TemplateMatch.Gray crop) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + crop.pixels().length).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(crop.width());
        buffer.putInt(crop.height());
        for (int pixel : crop.pixels()) buffer.put((byte) (pixel & 0xff));
        return buffer.array();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
