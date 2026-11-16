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
    public static final String LABEL_SENTENCE = "agent-injected label; a person did not author this label";
    public static final String BORDER_SENTENCE = "agent-injected border; a person did not author this border";
    public static final String ARROW_SENTENCE = "agent-injected arrow; a person did not author this arrow";
    public static final String STEP_SENTENCE = "agent-injected step badge; a person did not author this badge";
    public static final String MAGNIFIER_SENTENCE = "agent-injected magnifier; a person did not author this magnifier";
    public static final String SPOTLIGHT_SENTENCE = "agent-injected spotlight; a person did not author this dim";

    private static final String ROOT = "/tools/dscode/browser/surface/";
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private SurfaceAssets() {
    }

    /** Host markup for a surface kind. {@code instructions} and {@code diagram} use the html host. */
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
     * {@code label}, {@code border}, {@code arrow}, {@code step}, {@code magnifier}, and {@code spotlight}
     * share that same event script.
     */
    public static String js(String kind) {
        return load(asset(kind, "js"));
    }

    /**
     * Page-injection source. Names: {@code live-helper}, {@code bridge-mark}, {@code frame-mark}, {@code frame-restore},
     * {@code pkb-runtime} (the tracker that keeps placements on their targets).
     */
    public static String script(String name) {
        String file = switch (scriptName(name)) {
            case "live-helper" -> "live-helper.js";
            case "bridge-mark" -> "bridge-mark.js";
            case "frame-mark" -> "frame-mark.js";
            case "frame-restore" -> "frame-restore.js";
            case "pkb-runtime" -> "pkb-runtime.js";
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
            case "label" -> LABEL_SENTENCE;
            case "border" -> BORDER_SENTENCE;
            case "arrow" -> ARROW_SENTENCE;
            case "step" -> STEP_SENTENCE;
            case "magnifier" -> MAGNIFIER_SENTENCE;
            case "spotlight" -> SPOTLIGHT_SENTENCE;
            default -> throw unknownKind(kind);
        };
    }

    private static String asset(String kind, String extension) {
        String canon = canonical(kind);
        if ("js".equals(extension) && ("html".equals(canon) || "overlay".equals(canon) || visual(canon))) {
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
            case "label" -> "label";
            case "border" -> "border";
            case "arrow" -> "arrow";
            case "step", "step-badge" -> "step";
            case "magnifier" -> "magnifier";
            case "spotlight" -> "spotlight";
            default -> throw unknownKind(kind);
        };
    }

    private static boolean visual(String canon) {
        return switch (canon) {
            case "label", "border", "arrow", "step", "magnifier", "spotlight" -> true;
            default -> false;
        };
    }

    private static String scriptName(String name) {
        String key = token(name, unknownScript(name));
        return switch (key) {
            case "live-helper", "bridge-mark", "frame-mark", "frame-restore", "pkb-runtime" -> key;
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
                        + "\nfix: Use html, overlay, highlight, label, border, arrow, step, magnifier, spotlight, instructions, or diagram.");
    }

    private static IllegalArgumentException unknownScript(String name) {
        return new IllegalArgumentException(
                "error: unknown surface script: " + name
                        + "\nfix: Use live-helper, bridge-mark, frame-mark, frame-restore, or pkb-runtime.");
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
