package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpException;
import dev.domengine.desktop.DesktopTree;
import dev.domengine.tree.DomNode;

import java.util.Locale;
import java.util.Map;

/** Desktop composite snapshot. A fixture without {@code --screenshot} fails. */
public final class DesktopSnapshotProducer {
    private DesktopSnapshotProducer() {
    }

    public static ObjectNode produce(DesktopTree.Session session, Map<String, String> flags) {
        if (session == null) throw new CdpException("session not found");
        SnapshotOptions options = SnapshotOptions.fromFlags(flags);
        if (!session.live() && options.screenshot().isBlank()) {
            throw new CdpException("error: desktop fixture snapshot needs --screenshot");
        }
        java.util.Map<String, String> merged = new java.util.LinkedHashMap<>();
        if (flags != null) merged.putAll(flags);
        merged.put("session", session.id());
        if (session.live() && options.screenshot().isBlank()) {
            dev.domengine.desktop.DesktopScreenshot.Rect rect = clipOf(session.root());
            dev.domengine.desktop.DesktopScreenshot.Shot shot = dev.domengine.desktop.DesktopScreenshot.capture(rect);
            try {
                java.nio.file.Path file = screenshotFile(merged);
                java.nio.file.Files.write(file, shot.png());
                merged.put("screenshot", file.toString());
            } catch (java.io.IOException failure) {
                throw new CdpException("error: desktop screenshot failed\nfix: " + failure.getMessage());
            }
        }
        String xml = xml(session.root());
        String platform = session.live() ? platformName(session.platform()) : platformName(os());
        String transport = session.live() ? transportName(session.platform()) : "fixture";
        long started = System.nanoTime();
        SnapshotContext context = SnapshotContext.desktop(
                session.root(),
                SnapshotOptions.fromFlags(merged),
                xml,
                platform,
                transport,
                "desktop:" + session.sequence()
        );
        return CompositeSnapshotBuilder.build(session.root(), context, started).toJson();
    }

    private static java.nio.file.Path screenshotFile(java.util.Map<String, String> flags) throws java.io.IOException {
        String dir = flags.get("out-dir");
        if (dir != null && !dir.isBlank()) {
            java.nio.file.Path out = java.nio.file.Path.of(dir);
            java.nio.file.Files.createDirectories(out);
            return out.resolve("screenshot.png");
        }
        return java.nio.file.Files.createTempFile("desktop-shot-", ".png");
    }

    private static dev.domengine.desktop.DesktopScreenshot.Rect clipOf(DomNode root) {
        double[] box = null;
        if (root != null && root.hasBox && root.width > 0 && root.height > 0) {
            box = new double[] {root.x, root.y, root.width, root.height};
        } else {
            box = union(root, null);
        }
        if (box == null) {
            throw new CdpException(
                    "error: screenshot rect is empty\n"
                            + "fix: Attach a window with a box, or pass --screenshot.");
        }
        int x = (int) Math.floor(box[0]);
        int y = (int) Math.floor(box[1]);
        int width = Math.max(1, (int) Math.ceil(box[2]));
        int height = Math.max(1, (int) Math.ceil(box[3]));
        return new dev.domengine.desktop.DesktopScreenshot.Rect(x, y, width, height);
    }

    private static double[] union(DomNode node, double[] box) {
        if (node == null) return box;
        if (node.nodeType == 1 && node.hasBox && node.width > 0 && node.height > 0) {
            if (box == null) box = new double[] {node.x, node.y, node.width, node.height};
            else {
                double right = Math.max(box[0] + box[2], node.x + node.width);
                double bottom = Math.max(box[1] + box[3], node.y + node.height);
                box[0] = Math.min(box[0], node.x);
                box[1] = Math.min(box[1], node.y);
                box[2] = right - box[0];
                box[3] = bottom - box[1];
            }
        }
        for (DomNode child : node.children) box = union(child, box);
        return box;
    }

    private static String platformName(String platform) {
        String value = platform == null ? "" : platform.toLowerCase(Locale.ROOT);
        if (value.startsWith("windows")) return "windows-uia";
        if (value.startsWith("macos")) return "macos-ax";
        return "linux-atspi";
    }

    private static String transportName(String platform) {
        String value = platform == null ? "" : platform.toLowerCase(Locale.ROOT);
        if (value.startsWith("windows")) return "uia";
        if (value.startsWith("macos")) return "ax";
        return "atspi";
    }

    private static String os() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) return "windows";
        if (name.contains("mac")) return "macos";
        return "linux";
    }

    static String xml(DomNode node) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        write(node, xml, 0);
        return xml.toString();
    }

    private static void write(DomNode node, StringBuilder xml, int depth) {
        if (node == null || node.nodeType != 1) return;
        indent(xml, depth);
        xml.append("<node role=\"").append(escape(node.role))
                .append("\" name=\"").append(escape(node.accessibleName))
                .append("\" x=\"").append(node.x)
                .append("\" y=\"").append(node.y)
                .append("\" w=\"").append(node.width)
                .append("\" h=\"").append(node.height)
                .append("\">\n");
        for (DomNode child : node.children) write(child, xml, depth + 1);
        indent(xml, depth);
        xml.append("</node>\n");
    }

    private static void indent(StringBuilder xml, int depth) {
        xml.append("  ".repeat(Math.max(0, depth)));
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
