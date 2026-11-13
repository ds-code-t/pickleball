package dev.domengine.premap;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.affinity.AffinityScorer;
import dev.domengine.affinity.AffinityWeights;
import dev.domengine.image.TemplateMatch;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.query.HtmlTree;
import dev.domengine.query.Query;
import dev.domengine.snapshot.SnapshotContext;
import dev.domengine.snapshot.XPathIndex;
import dev.domengine.tree.DomNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Fills the pre-map. Every snapshot build runs this. There is no flag to turn it off. */
public final class PreMapBuilder {
    private PreMapBuilder() {
    }

    public static PreMap build(DomNode root, SnapshotContext context) {
        long started = System.nanoTime();
        SnapshotContext ctx = context == null ? SnapshotContext.empty() : context;
        if (root != null) root.linkParents();
        XPathIndex.assign(root);
        List<Section> sections = SectionDetector.detect(root, ctx.platform());
        int rejected = applyCaps(root, ctx);
        AffinityScorer.Bindings bindings = root == null
                ? new AffinityScorer.Bindings(List.of(), List.of())
                : AffinityScorer.bind(root, weights(ctx), ctx.platform());
        List<PreMap.LabelRef> labels = new ArrayList<>();
        for (AffinityScorer.Binding binding : bindings.labels()) {
            labels.add(new PreMap.LabelRef(
                    binding.label().id(),
                    binding.target().id(),
                    binding.score().total(),
                    binding.score().parts(),
                    binding.score().reasons()
            ));
        }
        List<String> unlabeled = new ArrayList<>();
        for (DomNode node : bindings.unlabeled()) unlabeled.add(node.id());
        Map<String, String> boxes = new LinkedHashMap<>();
        BoxCaps caps = ctx.caps();
        boxes.put("max_area", cap(caps.maxAreaPx(), caps.maxAreaRatio()));
        boxes.put("max_width", cap(caps.maxWidthPx(), caps.maxWidthRatio()));
        boxes.put("max_height", cap(caps.maxHeightPx(), caps.maxHeightRatio()));
        boxes.put("rejected", Integer.toString(rejected));
        int nodes = count(root);
        Index index = index(root, ctx, sections, labels);
        long ms = (System.nanoTime() - started) / 1_000_000L;
        EngineLog.info("premap", "premap build nodes=" + nodes + " ms=" + ms);
        PreMap.Builder builder = PreMap.builder(ctx.sessionId(), ctx.docVersion())
                .sections(sections)
                .labels(labels)
                .unlabeled(unlabeled)
                .boxes(boxes)
                .indexes(
                        index.byRole, index.byRoleName, index.byTextToken, index.byTag,
                        index.byId, index.byClass, index.byXpath, index.bySectionTitle,
                        index.interactables, index.inViewport
                )
                .image(index.image);
        index.colours.forEach(builder::colour);
        index.viewport.forEach(builder::viewport);
        return builder.build();
    }

    private static AffinityWeights weights(SnapshotContext ctx) {
        String spec = ctx.flags().get("affinity-weights");
        if (spec == null || spec.isBlank()) return AffinityWeights.fromEnv();
        return AffinityWeights.parse(spec);
    }

    private static int applyCaps(DomNode node, SnapshotContext ctx) {
        if (node == null) return 0;
        int rejected = 0;
        if (node.nodeType == 1 && !BoxCaps.alwaysExcluded(node)) {
            BoxCaps.Decision decision = ctx.caps().assess(node, ctx.viewportWidth(), ctx.viewportHeight(), false);
            node.capped = decision.capped();
            node.capReason = decision.reason();
            if (decision.capped()) rejected++;
        }
        for (DomNode child : node.children) rejected += applyCaps(child, ctx);
        for (DomNode shadow : node.shadowRoots) rejected += applyCaps(shadow, ctx);
        return rejected;
    }

    private static int count(DomNode node) {
        if (node == null) return 0;
        int total = node.nodeType == 1 ? 1 : 0;
        for (DomNode child : node.children) total += count(child);
        for (DomNode shadow : node.shadowRoots) total += count(shadow);
        return total;
    }

    private static String cap(double pixels, double ratio) {
        if (pixels > 0) return String.format(Locale.ROOT, "%.0fpx", pixels);
        return String.format(Locale.ROOT, "%.2f", ratio);
    }

    private static Index index(DomNode root, SnapshotContext ctx, List<Section> sections, List<PreMap.LabelRef> labels) {
        Index index = new Index();
        Map<String, String> labelText = new LinkedHashMap<>();
        if (root != null) {
            for (PreMap.LabelRef label : labels) {
                DomNode node = find(root, label.label());
                if (node != null) labelText.put(label.target(), text(node));
            }
        }
        BufferedImage shot = screenshot(ctx);
        double scale = ctx.imageScale();
        boolean screen = "true".equals(ctx.flags().get("screen-space"));
        double originX = screen ? number(ctx.flags().get("screen-origin-x")) : 0;
        double originY = screen ? number(ctx.flags().get("screen-origin-y")) : 0;
        if (shot != null) index.image.set("grid", grid(shot));
        else index.image.putArray("grid");
        index.image.putObject("nodes");
        walk(root, ctx, sections, labelText, index, shot, scale, screen, originX, originY);
        return index;
    }

    private static void walk(
            DomNode node,
            SnapshotContext ctx,
            List<Section> sections,
            Map<String, String> labelText,
            Index index,
            BufferedImage shot,
            double scale,
            boolean screen,
            double originX,
            double originY
    ) {
        if (node == null) return;
        if (node.nodeType == 1) {
            String handle = node.id();
            String role = Query.roleOf(node).toLowerCase(Locale.ROOT);
            String name = nameOf(node, labelText.get(handle));
            index.add(index.byRole, role, handle);
            if (!name.isBlank()) index.add(index.byRoleName, role + "\t" + name, handle);
            String tag = node.tag();
            if (!tag.isBlank()) index.add(index.byTag, tag, handle);
            String id = attr(node, "id");
            if (!id.isBlank()) index.add(index.byId, id, handle);
            for (String token : classes(node)) index.add(index.byClass, token, handle);
            if (node.xpath != null && !node.xpath.isBlank()) index.add(index.byXpath, node.xpath, handle);
            String body = text(node).toLowerCase(Locale.ROOT);
            for (String token : tokens(body)) index.add(index.byTextToken, token, handle);
            if (!body.isBlank()) index.add(index.byTextToken, "\u0002" + body, handle);
            String accessible = node.accessibleName == null ? "" : node.accessibleName.trim().toLowerCase(Locale.ROOT);
            if (!accessible.isBlank() && !accessible.equals(body)) index.add(index.byTextToken, "\u0002" + accessible, handle);
            if (node.sectionPath != null && !node.sectionPath.isBlank()) {
                for (String sectionId : node.sectionPath.split("/")) {
                    if (sectionId.isBlank()) continue;
                    index.add(index.bySectionTitle, "#" + sectionId, handle);
                    for (Section section : sections) {
                        if (sectionId.equals(section.id()) && !section.title().isBlank()) {
                            index.add(index.bySectionTitle, section.title().toLowerCase(Locale.ROOT), handle);
                        }
                    }
                }
            }
            if (AffinityScorer.interactable(node)) index.interactables.add(handle);
            if (inside(node, ctx)) index.inViewport.add(handle);
            Box view = viewBox(node, screen, scale, originX, originY, shot != null);
            if (view != null) index.viewport.put(handle, view.toJson());
            paint(node, index);
            if (shot != null && view != null && (AffinityScorer.interactable(node) || AffinityScorer.labelLike(node))) {
                sample(node, shot, view, index);
            }
        }
        for (DomNode child : node.children) walk(child, ctx, sections, labelText, index, shot, scale, screen, originX, originY);
        for (DomNode shadow : node.shadowRoots) walk(shadow, ctx, sections, labelText, index, shot, scale, screen, originX, originY);
    }

    private static void paint(DomNode node, Index index) {
        String fg = colour(node, true);
        String bg = colour(node, false);
        double contrast = contrast(fg, bg);
        DomNode.Paint paint = node.paint == null ? new DomNode.Paint() : node.paint;
        paint.color = fg;
        paint.background = bg;
        paint.contrast = contrast;
        node.paint = paint;
        ObjectNode colour = Jsons.object();
        colour.put("fg", fg);
        colour.put("bg", bg);
        if (Double.isNaN(contrast)) colour.putNull("contrast");
        else colour.put("contrast", contrast);
        index.colours.put(node.id(), colour);
    }

    private static void sample(DomNode node, BufferedImage shot, Box imageBox, Index index) {
        int x = (int) Math.floor(imageBox.x());
        int y = (int) Math.floor(imageBox.y());
        int w = Math.max(1, (int) Math.round(imageBox.width()));
        int h = Math.max(1, (int) Math.round(imageBox.height()));
        int[] pixels = new int[w * h];
        long red = 0;
        long green = 0;
        long blue = 0;
        int count = 0;
        for (int row = 0; row < h; row++) {
            int sy = Math.min(shot.getHeight() - 1, Math.max(0, y + row));
            for (int col = 0; col < w; col++) {
                int sx = Math.min(shot.getWidth() - 1, Math.max(0, x + col));
                int rgb = shot.getRGB(sx, sy);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                pixels[count] = (r * 54 + g * 183 + b * 19) >> 8;
                red += r;
                green += g;
                blue += b;
                count++;
            }
        }
        if (count == 0) return;
        TemplateMatch.Gray gray = new TemplateMatch.Gray(w, h, pixels);
        TemplateMatch.Features features = TemplateMatch.features(gray);
        DomNode.Paint paint = node.paint == null ? new DomNode.Paint() : node.paint;
        paint.meanRgb = (red / count) + "," + (green / count) + "," + (blue / count);
        paint.dhash = features.dhash();
        paint.hasDhash = true;
        node.paint = paint;
        ObjectNode one = Jsons.object();
        one.put("dhash", Long.toHexString(features.dhash()));
        one.put("mean", features.mean());
        one.put("stddev", features.stddev());
        one.put("mean_rgb", paint.meanRgb);
        ((ObjectNode) index.image.get("nodes")).set(node.id(), one);
    }

    private static ArrayNode grid(BufferedImage shot) {
        ArrayNode grid = Jsons.MAPPER.createArrayNode();
        for (int y = 0; y < 16; y++) {
            int sy = Math.min(shot.getHeight() - 1, y * shot.getHeight() / 16);
            for (int x = 0; x < 16; x++) {
                int sx = Math.min(shot.getWidth() - 1, x * shot.getWidth() / 16);
                int rgb = shot.getRGB(sx, sy);
                int lum = (((rgb >> 16) & 0xff) * 54 + ((rgb >> 8) & 0xff) * 183 + (rgb & 0xff) * 19) >> 8;
                grid.add(lum);
            }
        }
        return grid;
    }

    private static Box viewBox(DomNode node, boolean screen, double scale, double originX, double originY, boolean image) {
        if (!node.hasBox && !node.hasVisual) return null;
        if (image) {
            double x = node.hasVisual ? node.visualX : node.x;
            double y = node.hasVisual ? node.visualY : node.y;
            if (screen && !node.hasVisual) {
                x -= originX;
                y -= originY;
            }
            return new Box(x * scale, y * scale, node.width * scale, node.height * scale);
        }
        if (node.hasVisual) return new Box(node.visualX, node.visualY, node.width, node.height);
        return new Box(node.x, node.y, node.width, node.height);
    }

    private static boolean inside(DomNode node, SnapshotContext ctx) {
        if (!node.hasBox || node.width <= 0 || node.height <= 0) return false;
        return node.x < ctx.viewportWidth() && node.y < ctx.viewportHeight()
                && node.x + node.width > 0 && node.y + node.height > 0;
    }

    private static BufferedImage screenshot(SnapshotContext ctx) {
        byte[] png = ctx.screenshotPng();
        if (png.length < 8) return null;
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (Exception failure) {
            return null;
        }
    }

    private static String nameOf(DomNode node, String label) {
        String name = node.accessibleName == null ? "" : node.accessibleName.trim().toLowerCase(Locale.ROOT);
        if (!name.isBlank()) return name;
        if (label != null && !label.isBlank()) return label.trim().toLowerCase(Locale.ROOT);
        return "";
    }

    private static String text(DomNode node) {
        String value = HtmlTree.textOf(node);
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder one = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch)) one.append(ch);
            else if (one.length() > 0) {
                tokens.add(one.toString());
                one.setLength(0);
            }
        }
        if (one.length() > 0) tokens.add(one.toString());
        return tokens;
    }

    private static List<String> classes(DomNode node) {
        String raw = attr(node, "class");
        List<String> tokens = new ArrayList<>();
        if (raw.isBlank()) return tokens;
        for (String token : raw.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!token.isBlank()) tokens.add(token);
        }
        return tokens;
    }

    private static String attr(DomNode node, String name) {
        String value = node.attributes.get(name);
        return value == null ? "" : value.trim();
    }

    private static String colour(DomNode node, boolean foreground) {
        DomNode cursor = node;
        while (cursor != null) {
            String found = foreground ? foreground(cursor) : background(cursor);
            if (!found.isBlank() && (foreground || !transparent(found))) return found;
            cursor = cursor.parent;
        }
        return foreground ? "#000000" : "#ffffff";
    }

    private static String foreground(DomNode node) {
        String computed = first(node.computed.get("color"), style(node, "color"));
        if (!computed.isBlank()) return computed;
        return node.paint == null || node.paint.color == null ? "" : node.paint.color;
    }

    private static String background(DomNode node) {
        String computed = first(node.computed.get("background-color"), node.computed.get("background"), style(node, "background-color"));
        if (!computed.isBlank()) return computed;
        return node.paint == null || node.paint.background == null ? "" : node.paint.background;
    }

    private static String style(DomNode node, String property) {
        String style = attr(node, "style").toLowerCase(Locale.ROOT).replace(" ", "");
        for (String part : style.split(";")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            if (part.substring(0, colon).equals(property)) return part.substring(colon + 1);
        }
        return "";
    }

    private static boolean transparent(String colour) {
        String value = colour.toLowerCase(Locale.ROOT).replace(" ", "");
        return value.isBlank() || "transparent".equals(value) || value.startsWith("rgba(") && value.endsWith(",0)");
    }

    private static String first(String... values) {
        if (values == null) return "";
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    static double contrast(String foreground, String background) {
        double[] fg = rgb(foreground);
        double[] bg = rgb(background);
        if (fg == null || bg == null) return Double.NaN;
        double l1 = luminance(fg);
        double l2 = luminance(bg);
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double luminance(double[] rgb) {
        double sum = 0;
        double[] weight = {0.2126, 0.7152, 0.0722};
        for (int i = 0; i < 3; i++) {
            double channel = rgb[i] / 255.0;
            channel = channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
            sum += weight[i] * channel;
        }
        return sum;
    }

    private static double[] rgb(String colour) {
        if (colour == null) return null;
        String value = colour.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("#")) {
            String hex = value.substring(1);
            if (hex.length() == 3) {
                hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
            }
            if (hex.length() != 6) return null;
            try {
                return new double[] {
                        Integer.parseInt(hex.substring(0, 2), 16),
                        Integer.parseInt(hex.substring(2, 4), 16),
                        Integer.parseInt(hex.substring(4, 6), 16)
                };
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (value.startsWith("rgb")) {
            String body = value.substring(value.indexOf('(') + 1, value.endsWith(")") ? value.length() - 1 : value.length());
            String[] parts = body.split(",");
            if (parts.length < 3) return null;
            try {
                return new double[] {channel(parts[0]), channel(parts[1]), channel(parts[2])};
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static double channel(String raw) {
        String value = raw.trim();
        if (value.endsWith("%")) return Double.parseDouble(value.substring(0, value.length() - 1)) * 2.55;
        return Double.parseDouble(value);
    }

    private static DomNode find(DomNode node, String handle) {
        if (node == null) return null;
        if (handle.equals(node.id())) return node;
        for (DomNode child : node.children) {
            DomNode found = find(child, handle);
            if (found != null) return found;
        }
        for (DomNode shadow : node.shadowRoots) {
            DomNode found = find(shadow, handle);
            if (found != null) return found;
        }
        return null;
    }

    private static double number(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static final class Index {
        private final Map<String, List<String>> byRole = new LinkedHashMap<>();
        private final Map<String, List<String>> byRoleName = new LinkedHashMap<>();
        private final Map<String, List<String>> byTextToken = new LinkedHashMap<>();
        private final Map<String, List<String>> byTag = new LinkedHashMap<>();
        private final Map<String, List<String>> byId = new LinkedHashMap<>();
        private final Map<String, List<String>> byClass = new LinkedHashMap<>();
        private final Map<String, List<String>> byXpath = new LinkedHashMap<>();
        private final Map<String, List<String>> bySectionTitle = new LinkedHashMap<>();
        private final List<String> interactables = new ArrayList<>();
        private final List<String> inViewport = new ArrayList<>();
        private final Map<String, ObjectNode> viewport = new LinkedHashMap<>();
        private final Map<String, ObjectNode> colours = new LinkedHashMap<>();
        private final ObjectNode image = Jsons.object();

        private void add(Map<String, List<String>> map, String key, String handle) {
            if (key == null || key.isBlank() || handle == null || handle.isBlank()) return;
            List<String> list = map.computeIfAbsent(key, ignored -> new ArrayList<>());
            if (!list.contains(handle)) list.add(handle);
        }
    }
}
