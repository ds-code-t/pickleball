package dev.domengine.query;

import dev.domengine.image.TemplateMatch;
import dev.domengine.log.EngineLog;
import dev.domengine.tree.DomNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cheap-first perception once an element's box is known: in viewport, then a multi-point hit test against later
 * opaque paint, then clip, size, and contrast, then OCR on the crop only when still undecided. The first stage that
 * settles the answer is the reported stage ({@code perceived=hit-test}). Decisions are cached by region (the node box
 * plus the boxes and paint order of what can cover it); OCR keeps its own crop-hash cache in {@link OcrHook}. This
 * feeds the human mask only; outside human mode nothing here filters a query, so it is never a test-failure fence.
 */
public final class Perception {
    public static final String VIEWPORT = "viewport";
    public static final String HIT_TEST = "hit-test";
    public static final String CLIP = "clip";
    public static final String CONTRAST = "contrast";
    public static final String OCR = "ocr";

    /** One answer; {@code path} lists the stages that ran, cheapest first. */
    public record Decision(boolean perceived, String stage, String path, boolean cached) {
    }

    /** A crop source for the OCR stage; null when no pixels are available. */
    public interface Crops {
        TemplateMatch.Gray crop(DomNode node);
    }

    static final int CACHE_LIMIT = 512;
    private static final Map<String, Decision> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Decision> eldest) {
            return size() > CACHE_LIMIT;
        }
    };
    private static final AtomicInteger EVALUATIONS = new AtomicInteger();
    private static final AtomicInteger HITS = new AtomicInteger();
    /** Inset of the corner probes, as a share of width and height. */
    private static final double INSET = 0.15;

    private Perception() {
    }

    /**
     * Decides whether a person can perceive {@code node}. {@code viewport} is {x, y, width, height} or null when the
     * caller already checked it; {@code crops} may be null.
     */
    public static Decision decide(DomNode node, List<DomNode> elements, double[] viewport, Crops crops) {
        String key = regionKey(node, elements, viewport);
        synchronized (CACHE) {
            Decision cached = CACHE.get(key);
            if (cached != null) {
                HITS.incrementAndGet();
                return new Decision(cached.perceived(), cached.stage(), cached.path(), true);
            }
        }
        EVALUATIONS.incrementAndGet();
        Decision decision = evaluate(node, elements, viewport, crops);
        synchronized (CACHE) {
            CACHE.put(key, decision);
        }
        EngineLog.info("perception", node.id() + " perceived=" + decision.stage() + " " + decision.perceived());
        return decision;
    }

    private static Decision evaluate(DomNode node, List<DomNode> elements, double[] viewport, Crops crops) {
        StringBuilder path = new StringBuilder(VIEWPORT);
        if (!node.hasBox || node.width <= 0 || node.height <= 0) return new Decision(false, VIEWPORT, path.toString(), false);
        if (viewport != null && !intersects(node, viewport)) return new Decision(false, VIEWPORT, path.toString(), false);
        path.append('>').append(HIT_TEST);
        double[][] points = probes(node);
        int covered = 0;
        for (double[] point : points) if (coveredAt(node, point[0], point[1], elements)) covered++;
        if (covered == points.length) return new Decision(false, HIT_TEST, path.toString(), false);
        boolean partial = covered > 0;
        boolean lowContrast = lowContrast(node, elements);
        if (!partial && !lowContrast && !clipped(node)) return new Decision(true, HIT_TEST, path.toString(), false);
        path.append('>').append(CLIP);
        if (clipped(node) || node.width < 2 || node.height < 2) return new Decision(false, CLIP, path.toString(), false);
        if (!lowContrast) return new Decision(true, CLIP, path.toString(), false);
        path.append('>').append(CONTRAST);
        TemplateMatch.Gray crop = crops == null || !OcrHook.target(node) ? null : crops.crop(node);
        if (crop == null) return new Decision(false, CONTRAST, path.toString(), false);
        path.append('>').append(OCR);
        OcrHook.Result read = OcrHook.recognize(node, crop);
        boolean text = read != null && read.ok() && read.text() != null && !read.text().isBlank();
        return new Decision(text, OCR, path.toString(), false);
    }

    /** Center plus four inset corners. */
    static double[][] probes(DomNode node) {
        double dx = node.width * INSET;
        double dy = node.height * INSET;
        return new double[][]{
                {node.x + node.width / 2, node.y + node.height / 2},
                {node.x + dx, node.y + dy},
                {node.x + node.width - dx, node.y + dy},
                {node.x + dx, node.y + node.height - dy},
                {node.x + node.width - dx, node.y + node.height - dy}
        };
    }

    private static boolean coveredAt(DomNode node, double x, double y, List<DomNode> elements) {
        Integer order = paintOrder(node);
        int mine = order == null ? 0 : order;
        for (DomNode other : elements) {
            if (other == node || !other.hasBox || descends(other, node)) continue;
            Integer later = paintOrder(other);
            if (later == null || later <= mine || !"true".equals(other.attributes.get("data-opaque"))) continue;
            if (x >= other.x && y >= other.y && x <= other.x + other.width && y <= other.y + other.height) return true;
        }
        return false;
    }

    private static boolean descends(DomNode candidate, DomNode ancestor) {
        for (DomNode up = candidate.parent; up != null; up = up.parent) if (up == ancestor) return true;
        return false;
    }

    private static boolean intersects(DomNode node, double[] viewport) {
        return node.x < viewport[0] + viewport[2] && node.x + node.width > viewport[0]
                && node.y < viewport[1] + viewport[3] && node.y + node.height > viewport[1];
    }

    private static boolean clipped(DomNode node) {
        String clip = style(node, "clip-path");
        if (clip.startsWith("inset(50%") || "circle(0)".equals(clip) || "circle(0px)".equals(clip)) return true;
        String old = style(node, "clip");
        return old.startsWith("rect(0") && old.replace(" ", "").matches("rect\\(0(px)?,?0(px)?,?0(px)?,?0(px)?\\)");
    }

    /** Text color equal (or nearly) to the nearest background behind it. */
    static boolean lowContrast(DomNode node, List<DomNode> elements) {
        int[] fg = rgb(style(node, "color"));
        if (fg == null) return false;
        int[] bg = null;
        for (DomNode up = node; up != null && bg == null; up = up.parent) {
            int[] own = rgb(style(up, "background-color"));
            if (own != null && (own.length < 4 || own[3] > 0)) bg = own;
        }
        if (bg == null) return false;
        return ratio(fg, bg) < 1.5;
    }

    private static double ratio(int[] a, int[] b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int[] c) {
        double[] v = new double[3];
        for (int i = 0; i < 3; i++) {
            double s = c[i] / 255.0;
            v[i] = s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    static int[] rgb(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim().toLowerCase(Locale.ROOT);
        if ("white".equals(text)) return new int[]{255, 255, 255, 1};
        if ("black".equals(text)) return new int[]{0, 0, 0, 1};
        if (text.startsWith("#") && (text.length() == 7 || text.length() == 4)) {
            String hex = text.length() == 4
                    ? "" + text.charAt(1) + text.charAt(1) + text.charAt(2) + text.charAt(2) + text.charAt(3) + text.charAt(3)
                    : text.substring(1);
            try {
                return new int[]{Integer.parseInt(hex.substring(0, 2), 16), Integer.parseInt(hex.substring(2, 4), 16),
                        Integer.parseInt(hex.substring(4, 6), 16), 1};
            } catch (NumberFormatException bad) {
                return null;
            }
        }
        if (text.startsWith("rgb")) {
            String[] parts = text.replaceAll("[^0-9.,]", " ").trim().split("[\\s,]+");
            if (parts.length < 3) return null;
            try {
                int alpha = parts.length > 3 && Double.parseDouble(parts[3]) == 0 ? 0 : 1;
                return new int[]{(int) Double.parseDouble(parts[0]), (int) Double.parseDouble(parts[1]), (int) Double.parseDouble(parts[2]), alpha};
            } catch (NumberFormatException bad) {
                return null;
            }
        }
        return null;
    }

    private static String regionKey(DomNode node, List<DomNode> elements, double[] viewport) {
        StringBuilder key = new StringBuilder(node.id()).append('|').append(box(node)).append('|')
                .append(style(node, "color")).append('/').append(style(node, "background-color"));
        if (viewport != null) key.append("|vp").append(viewport[0]).append(',').append(viewport[1]).append(',').append(viewport[2]).append(',').append(viewport[3]);
        Integer order = paintOrder(node);
        for (DomNode other : elements) {
            if (other == node || !other.hasBox) continue;
            Integer later = paintOrder(other);
            if (later == null || (order != null && later <= order) || !"true".equals(other.attributes.get("data-opaque"))) continue;
            if (other.x > node.x + node.width || other.x + other.width < node.x || other.y > node.y + node.height || other.y + other.height < node.y) continue;
            key.append('|').append(later).append('@').append(box(other));
        }
        return key.toString();
    }

    private static String box(DomNode node) {
        return Math.round(node.x) + "," + Math.round(node.y) + "," + Math.round(node.width) + "," + Math.round(node.height);
    }

    private static Integer paintOrder(DomNode node) {
        String raw = node.attributes.get("data-paint-order");
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static String style(DomNode node, String property) {
        if (node.computed.containsKey(property)) return node.computed.get(property).trim().toLowerCase(Locale.ROOT);
        String style = node.attributes.get("style");
        if (style == null) return "";
        for (String part : style.split(";")) {
            int colon = part.indexOf(':');
            if (colon < 0) continue;
            if (part.substring(0, colon).trim().equalsIgnoreCase(property)) return part.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
        }
        return "";
    }

    public static int evaluations() {
        return EVALUATIONS.get();
    }

    public static int cacheHits() {
        return HITS.get();
    }

    public static void resetForTests() {
        synchronized (CACHE) {
            CACHE.clear();
        }
        EVALUATIONS.set(0);
        HITS.set(0);
    }
}
