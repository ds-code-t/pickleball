package dev.domengine.snapshot;

import dev.domengine.premap.BoxCaps;
import dev.domengine.premap.Platform;
import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Transport-neutral inputs the pre-map and the snapshot builder share. */
public final class SnapshotContext {
    private final Platform platform;
    private final String snapshotPlatform;
    private final String transport;
    private final String sessionId;
    private final String docVersion;
    private final String id;
    private final BoxCaps caps;
    private final double viewportWidth;
    private final double viewportHeight;
    private final Map<String, String> flags;
    private final String sourceKind;
    private final String sourceText;
    private final PngImage screenshot;
    private final List<String> warnings = new ArrayList<>();
    private final double devicePixelRatio;
    private final double scrollX;
    private final double scrollY;
    private final double visualOffsetX;
    private final double visualOffsetY;
    private final double visualScale;

    private SnapshotContext(
            Platform platform,
            String snapshotPlatform,
            String transport,
            String sessionId,
            String docVersion,
            String id,
            BoxCaps caps,
            double viewportWidth,
            double viewportHeight,
            Map<String, String> flags,
            String sourceKind,
            String sourceText,
            PngImage screenshot,
            double devicePixelRatio,
            double scrollX,
            double scrollY,
            double visualOffsetX,
            double visualOffsetY,
            double visualScale
    ) {
        this.platform = platform == null ? Platform.BROWSER : platform;
        this.snapshotPlatform = snapshotPlatform == null || snapshotPlatform.isBlank() ? "chromium" : snapshotPlatform;
        this.transport = transport == null || transport.isBlank() ? "fixture" : transport;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.docVersion = docVersion == null ? "" : docVersion;
        this.id = id == null ? "" : id;
        this.caps = caps == null ? BoxCaps.defaults() : caps;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.flags = flags == null ? Map.of() : flags;
        this.sourceKind = sourceKind == null ? "html" : sourceKind;
        this.sourceText = sourceText == null ? "" : sourceText;
        this.screenshot = screenshot;
        this.devicePixelRatio = devicePixelRatio <= 0 ? 1 : devicePixelRatio;
        this.scrollX = scrollX;
        this.scrollY = scrollY;
        this.visualOffsetX = visualOffsetX;
        this.visualOffsetY = visualOffsetY;
        this.visualScale = visualScale <= 0 ? 1 : visualScale;
    }

    public static SnapshotContext desktop(
            DomNode root,
            SnapshotOptions options,
            String xml,
            String platform,
            String transport,
            String docVersion
    ) {
        SnapshotOptions used = options == null ? new SnapshotOptions() : options;
        double[] viewport = viewport(root);
        if (root != null && root.hasBox && root.width > 0 && root.height > 0 && sizedDocument(root) == null) {
            viewport = new double[] {root.width, root.height};
        }
        java.util.Map<String, String> flags = new java.util.LinkedHashMap<>(used.flags());
        flags.put("screen-space", "true");
        if (root != null && root.hasBox) {
            flags.put("screen-origin-x", Double.toString(root.x));
            flags.put("screen-origin-y", Double.toString(root.y));
        }
        PngImage image = used.screenshot().isBlank() ? null : PngImage.read(java.nio.file.Path.of(used.screenshot()));
        return new SnapshotContext(
                dev.domengine.premap.Platform.DESKTOP,
                platform == null || platform.isBlank() ? "linux-atspi" : platform,
                transport == null || transport.isBlank() ? "fixture" : transport,
                used.sessionId(),
                docVersion == null ? "desktop:0" : docVersion,
                used.id(),
                dev.domengine.premap.BoxCaps.fromFlags(flags),
                viewport[0],
                viewport[1],
                flags,
                "a11y-xml",
                xml == null ? "" : xml,
                image,
                1, 0, 0, 0, 0, 1
        );
    }

    public static SnapshotContext empty() {
        return new SnapshotContext(Platform.BROWSER, "chromium", "fixture", "", "offline:0", "",
                BoxCaps.defaults(), 1280, 800, Map.of(), "html", "", null,
                1, 0, 0, 0, 0, 1);
    }

    public static SnapshotContext offline(DomNode root, SnapshotOptions options, String html) {
        SnapshotOptions used = options == null ? new SnapshotOptions() : options;
        double[] viewport = viewport(root);
        String text = html == null ? "" : html;
        String version = "offline:" + PngImage.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 12);
        PngImage image = used.screenshot().isBlank() ? null : PngImage.read(java.nio.file.Path.of(used.screenshot()));
        return new SnapshotContext(
                Platform.BROWSER,
                used.platform().isBlank() ? "chromium" : used.platform(),
                "fixture",
                used.sessionId(),
                version,
                used.id(),
                BoxCaps.fromFlags(used.flags()),
                viewport[0],
                viewport[1],
                used.flags(),
                "html",
                text,
                image,
                1, 0, 0, 0, 0, 1
        );
    }

    public static SnapshotContext browser(
            SnapshotOptions options,
            String platform,
            String sessionId,
            String docVersion,
            String html,
            PngImage image,
            dev.domengine.tree.LiveTree.Layout layout
    ) {
        SnapshotOptions used = options == null ? new SnapshotOptions() : options;
        dev.domengine.tree.LiveTree.Layout metrics = layout == null
                ? new dev.domengine.tree.LiveTree.Layout(1280, 800, 0, 0, 0, 0, 1, 1)
                : layout;
        return new SnapshotContext(
                Platform.BROWSER,
                platform == null || platform.isBlank() ? "chrome" : platform,
                "cdp",
                sessionId == null ? used.sessionId() : sessionId,
                docVersion == null ? "" : docVersion,
                used.id(),
                BoxCaps.fromFlags(used.flags()),
                metrics.cssWidth(),
                metrics.cssHeight(),
                used.flags(),
                "html",
                html == null ? "" : html,
                image,
                metrics.devicePixelRatio(),
                metrics.scrollX(),
                metrics.scrollY(),
                metrics.offsetX(),
                metrics.offsetY(),
                metrics.scale()
        );
    }

    /**
     * A page snapshot from a facade (CDP, BiDi, or the Selenium fallback).
     * {@code png} may be null. A non-PNG byte array fails the same way a bad file does.
     */
    public static SnapshotContext captured(
            SnapshotOptions options,
            String platform,
            String transport,
            String docVersion,
            String html,
            byte[] png,
            double cssWidth,
            double cssHeight,
            double devicePixelRatio,
            double scrollX,
            double scrollY,
            double visualOffsetX,
            double visualOffsetY,
            double visualScale
    ) {
        SnapshotOptions used = options == null ? new SnapshotOptions() : options;
        PngImage image = png == null || png.length == 0 ? null : PngImage.decode(png);
        double width = cssWidth > 0 ? cssWidth : 1280;
        double height = cssHeight > 0 ? cssHeight : 800;
        return new SnapshotContext(
                Platform.BROWSER,
                platform == null || platform.isBlank() ? "chromium" : platform,
                transport == null || transport.isBlank() ? "cdp" : transport,
                used.sessionId(),
                docVersion == null ? "" : docVersion,
                used.id(),
                BoxCaps.fromFlags(used.flags()),
                width,
                height,
                used.flags(),
                "html",
                html == null ? "" : html,
                image,
                devicePixelRatio,
                scrollX,
                scrollY,
                visualOffsetX,
                visualOffsetY,
                visualScale
        );
    }

    public Platform platform() {
        return platform;
    }

    public String snapshotPlatform() {
        return snapshotPlatform;
    }

    public String transport() {
        return transport;
    }

    public String sessionId() {
        return sessionId;
    }

    public String docVersion() {
        return docVersion;
    }

    public String id() {
        return id;
    }

    public BoxCaps caps() {
        return caps;
    }

    public double viewportWidth() {
        return viewportWidth;
    }

    public double viewportHeight() {
        return viewportHeight;
    }

    public Map<String, String> flags() {
        return flags;
    }

    public String sourceKind() {
        return sourceKind;
    }

    public String sourceText() {
        return sourceText;
    }

    public PngImage screenshot() {
        return screenshot;
    }

    public byte[] screenshotPng() {
        return screenshot == null || screenshot.bytes() == null ? new byte[0] : screenshot.bytes();
    }

    public List<String> warnings() {
        return warnings;
    }

    public double imageScale() {
        if (screenshot == null || screenshot.width() <= 0 || viewportWidth <= 0) return 1;
        return screenshot.width() / viewportWidth;
    }

    public double devicePixelRatio() {
        return devicePixelRatio;
    }

    public double scrollX() {
        return scrollX;
    }

    public double scrollY() {
        return scrollY;
    }

    public double visualOffsetX() {
        return visualOffsetX;
    }

    public double visualOffsetY() {
        return visualOffsetY;
    }

    public double visualScale() {
        return visualScale;
    }

    private static double[] viewport(DomNode root) {
        DomNode sized = sizedDocument(root);
        if (sized != null) return new double[] {sized.width, sized.height};
        return new double[] {1280, 800};
    }

    private static DomNode sizedDocument(DomNode node) {
        if (node == null) return null;
        if (node.nodeType == 1 && node.hasBox && node.width > 0 && node.height > 0) {
            String tag = node.localName == null ? "" : node.localName;
            if ("html".equals(tag) || "body".equals(tag)) return node;
        }
        for (DomNode child : node.children) {
            DomNode found = sizedDocument(child);
            if (found != null) return found;
        }
        return null;
    }
}
