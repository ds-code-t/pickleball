package tools.dscode.browser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One copy of the page-surface HTML, CSS, and JavaScript.
 * PageSurfaces reads the scripts from here. A popup loads the same kind assets.
 */
public final class SurfaceAssets {
    public static final String HTML_SENTENCE = "agent-injected HTML; a person did not author this node";
    public static final String OVERLAY_SENTENCE = "agent-injected overlay; a person did not author this UI";
    public static final String STYLE_SENTENCE = "agent style; a person did not author this highlight";

    private static final String ROOT = "/tools/dscode/browser/surface/";
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private SurfaceAssets() {
    }

    /** Host markup for {@code html}, {@code overlay}, {@code highlight}, {@code instructions}, or {@code diagram}. */
    public static String html(String kind) {
        return load(asset(kind, "html"));
    }

    /** Stylesheet for the same kinds. {@code instructions} and {@code diagram} are the html stylesheet. */
    public static String css(String kind) {
        return load(asset(kind, "css"));
    }

    /**
     * Script for the same kinds. {@code html}, {@code overlay}, {@code instructions}, and {@code diagram}
     * share the surface event script. {@code highlight} does not install a listener.
     */
    public static String js(String kind) {
        return load(asset(kind, "js"));
    }

    /**
     * Page-injection source. Names: {@code live-helper}, {@code bridge-mark}, {@code frame-mark}, {@code frame-restore}.
     */
    public static String script(String name) {
        String file = switch (scriptName(name)) {
            case "live-helper" -> "live-helper.js";
            case "bridge-mark" -> "bridge-mark.js";
            case "frame-mark" -> "frame-mark.js";
            case "frame-restore" -> "frame-restore.js";
            default -> throw unknownScript(name);
        };
        return load(file);
    }

    /** Label sentence for a kind. {@code style} and {@code highlight} are the same sentence. */
    public static String sentence(String kind) {
        return switch (canonical(kind)) {
            case "html" -> HTML_SENTENCE;
            case "overlay" -> OVERLAY_SENTENCE;
            case "highlight" -> STYLE_SENTENCE;
            default -> throw unknownKind(kind);
        };
    }

    private static String asset(String kind, String extension) {
        String canon = canonical(kind);
        if ("js".equals(extension) && ("html".equals(canon) || "overlay".equals(canon))) {
            return "surface-events.js";
        }
        return canon + "." + extension;
    }

    private static String canonical(String kind) {
        String key = token(kind, unknownKind(kind));
        return switch (key) {
            case "html", "instructions", "diagram" -> "html";
            case "overlay" -> "overlay";
            case "highlight", "style" -> "highlight";
            default -> throw unknownKind(kind);
        };
    }

    private static String scriptName(String name) {
        String key = token(name, unknownScript(name));
        return switch (key) {
            case "live-helper", "bridge-mark", "frame-mark", "frame-restore" -> key;
            default -> throw unknownScript(name);
        };
    }

    private static String token(String value, IllegalArgumentException blank) {
        if (value == null || value.isBlank()) throw blank;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static IllegalArgumentException unknownKind(String kind) {
        return new IllegalArgumentException(
                "error: unknown surface kind: " + kind
                        + "\nfix: Use html, overlay, highlight, instructions, or diagram.");
    }

    private static IllegalArgumentException unknownScript(String name) {
        return new IllegalArgumentException(
                "error: unknown surface script: " + name
                        + "\nfix: Use live-helper, bridge-mark, frame-mark, or frame-restore.");
    }

    private static String load(String file) {
        return CACHE.computeIfAbsent(file, SurfaceAssets::read);
    }

    private static String read(String file) {
        String path = ROOT + file;
        try (InputStream in = SurfaceAssets.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(
                        "error: missing surface asset " + path
                                + "\nfix: Ship it under src/main/resources" + path + ".");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "error: could not read surface asset " + path
                            + "\nfix: Check the classpath resource.",
                    failure);
        }
    }
}
