package dev.domengine.desktop;

import dev.domengine.cdp.CdpException;
import dev.domengine.log.EngineLog;
import dev.domengine.tree.DomNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Live desktop entry. Windows uses UI Automation, macOS uses Accessibility,
 * and Linux uses AT-SPI. A missing bus, permission, or input tool throws.
 * It does not return a recorded tree.
 */
public final class NativeDesktop {
    public static final int WALK_DEPTH = 12;
    public static final int WALK_NODES = 500;
    private static final Set<String> SUPPORTED = Set.of(
            "linux-x64", "linux-aarch64", "windows-x64", "macos-aarch64", "macos-x64"
    );

    private NativeDesktop() {
    }

    public record CommandResult(int exit, String output, boolean timedOut) {
    }

    public static String family() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "macos";
        if (os.contains("nux") || os.contains("nix")) return "linux";
        throw new CdpException("unsupported desktop platform: " + os);
    }

    /** {@code linux-x64}, {@code linux-aarch64}, {@code windows-x64}, {@code macos-aarch64}, or {@code macos-x64}. */
    public static String platformId() {
        String archRaw = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String arch = switch (archRaw) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "aarch64";
            default -> archRaw;
        };
        String id = family() + "-" + arch;
        if (!SUPPORTED.contains(id)) {
            throw new CdpException("unsupported desktop platform: " + id
                    + " (supported: linux-x64, linux-aarch64, windows-x64, macos-aarch64, macos-x64)");
        }
        return id;
    }

    /** Verbs this backend implements today. Click and type are wired. The rest are not. */
    public static List<String> verbs(String backend) {
        return switch (backend == null ? "" : backend) {
            case "uia", "ax", "atspi" -> List.of("click", "type");
            default -> List.of();
        };
    }

    public static DomNode capture(String windowName) {
        String platform = platformId();
        EngineLog.info("desktop", "live capture " + platform);
        DomNode root = switch (family()) {
            case "windows" -> WindowsUia.capture(windowName);
            case "macos" -> MacAccessibility.capture(windowName);
            case "linux" -> AtSpi.capture(windowName);
            default -> throw new CdpException("unsupported desktop platform: " + platform);
        };
        stamp(root, platform);
        return selectWindow(root, windowName);
    }

    public static void click(DomNode node) {
        if (node == null) throw new CdpException("click failed: no node");
        String backend = node.attributes.getOrDefault("desktop-backend", "");
        EngineLog.info("desktop", "live click " + backend + " " + node.accessibleName);
        switch (backend) {
            case "uia" -> WindowsUia.click(node);
            case "ax" -> MacAccessibility.click(node);
            case "atspi" -> AtSpi.click(node);
            default -> throw new CdpException("click failed: live desktop node has no platform handle");
        }
    }

    public static void type(DomNode node, String text, boolean append) {
        if (node == null) throw new CdpException("type failed: no node");
        String backend = node.attributes.getOrDefault("desktop-backend", "");
        EngineLog.info("desktop", "live type " + backend + " " + node.accessibleName);
        switch (backend) {
            case "uia" -> WindowsUia.type(node, text, append);
            case "ax" -> MacAccessibility.type(node, text, append);
            case "atspi" -> AtSpi.type(node, text, append);
            default -> throw new CdpException("type failed: live desktop node has no platform handle");
        }
    }

    static CommandResult run(List<String> command) {
        return run(command, Map.of());
    }

    static CommandResult run(List<String> command, Map<String, String> env) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        if (env != null) builder.environment().putAll(env);
        try {
            Process process = builder.start();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try {
                    process.getInputStream().transferTo(buffer);
                } catch (Exception ignored) {
                    // The timeout or exit code still reports the failure.
                }
            }, "desktop-cmd");
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(1000);
                return new CommandResult(124, buffer.toString(StandardCharsets.UTF_8), true);
            }
            reader.join(1000);
            return new CommandResult(process.exitValue(), buffer.toString(StandardCharsets.UTF_8), false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CdpException("desktop command interrupted");
        } catch (CdpException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CdpException("desktop command failed: " + failure.getMessage());
        }
    }

    static Path tool(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(":")) {
            if (dir.isBlank()) continue;
            Path candidate = Path.of(dir, name);
            if (Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }

    static String oneLine(String text) {
        if (text == null || text.isBlank()) return "no output";
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() > 240 ? flat.substring(0, 240) : flat;
    }

    static DomNode parseLines(String output, String backend) {
        DomNode root = null;
        ArrayDeque<DomNode> stack = new ArrayDeque<>();
        boolean truncated = false;
        if (output != null) {
            for (String line : output.split("\\R")) {
                if (line.startsWith("TRUNCATED")) {
                    truncated = true;
                    continue;
                }
                if (!line.startsWith("NODE\t")) continue;
                String[] parts = line.split("\t", 9);
                if (parts.length < 9) continue;
                int depth = Integer.parseInt(parts[1].trim());
                DomNode node = new DomNode();
                node.nodeType = 1;
                node.displayed = true;
                node.frameId = "desktop";
                String role = mapRole(backend, parts[2].trim());
                node.role = role;
                node.localName = role;
                node.nodeName = role.toUpperCase(Locale.ROOT);
                node.x = parseDouble(parts[3]);
                node.y = parseDouble(parts[4]);
                node.width = parseDouble(parts[5]);
                node.height = parseDouble(parts[6]);
                node.frameX = node.x;
                node.frameY = node.y;
                node.hasBox = node.width > 0 && node.height > 0;
                node.accessibleName = parts[8];
                node.attributes.put("desktop-backend", backend);
                node.attributes.put("platform-role", parts[2].trim());
                if ("uia".equals(backend)) node.attributes.put("uia-runtime-id", parts[7]);
                if ("ax".equals(backend)) node.attributes.put("ax-path", parts[7]);
                while (stack.size() > depth) stack.removeLast();
                if (stack.isEmpty()) root = node;
                else stack.peekLast().children.add(node);
                stack.addLast(node);
            }
        }
        if (root == null) {
            throw new CdpException(("uia".equals(backend) ? "UIA" : "AX") + " unavailable: no nodes");
        }
        if (truncated) root.attributes.put("walk-truncated", "true");
        return root;
    }

    static DomNode selectWindow(DomNode root, String windowName) {
        if (root == null) throw new CdpException("desktop capture returned no tree");
        if (windowName == null || windowName.isBlank()) return root;
        List<DomNode> matches = new ArrayList<>();
        collectWindows(root, windowName.trim(), matches);
        if (matches.isEmpty()) throw new CdpException("not found: " + windowName);
        DomNode filtered = new DomNode();
        filtered.nodeType = 1;
        filtered.displayed = true;
        filtered.frameId = "desktop";
        filtered.role = "desktop";
        filtered.localName = "desktop";
        filtered.nodeName = "DESKTOP";
        filtered.accessibleName = windowName;
        filtered.attributes.put("desktop-backend", root.attributes.getOrDefault("desktop-backend", ""));
        filtered.attributes.put("platform", root.attributes.getOrDefault("platform", ""));
        if ("true".equals(root.attributes.get("walk-truncated"))) filtered.attributes.put("walk-truncated", "true");
        filtered.children.addAll(matches);
        return filtered;
    }

    private static void collectWindows(DomNode node, String name, List<DomNode> matches) {
        if (node == null) return;
        String role = node.role == null ? "" : node.role;
        boolean window = role.equals("window") || role.equals("application") || role.equals("dialog");
        String accessible = node.accessibleName == null ? "" : node.accessibleName;
        if (window && accessible.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) matches.add(node);
        for (DomNode child : node.children) collectWindows(child, name, matches);
    }

    private static void stamp(DomNode node, String platform) {
        if (node == null) return;
        node.frameId = "desktop";
        node.attributes.put("platform", platform);
        for (DomNode child : node.children) stamp(child, platform);
    }

    static String mapRole(String backend, String raw) {
        String value = raw == null ? "" : raw.trim();
        if ("ax".equals(backend) && value.startsWith("AX")) value = value.substring(2);
        value = value.toLowerCase(Locale.ROOT).replace('_', ' ').replace(" ", "");
        return switch (value) {
            case "button", "pushbutton", "togglebutton" -> "button";
            case "edit", "textfield", "textarea", "securetextfield", "passwordtext", "entry", "text" ->
                    "uia".equals(backend) && "text".equals(value) ? "label" : "textbox";
            case "statictext", "label" -> "label";
            case "checkbox" -> "checkbox";
            case "radiobutton" -> "radio";
            case "combobox", "popupbutton" -> "combobox";
            case "window", "frame", "desktopframe", "sheet" -> "window";
            case "application" -> "application";
            case "dialog" -> "dialog";
            case "image" -> "img";
            case "link", "hyperlink" -> "link";
            case "heading" -> "heading";
            case "list" -> "list";
            case "listitem", "row" -> "listitem";
            case "menu" -> "menu";
            case "menuitem" -> "menuitem";
            case "tab", "pagetab" -> "tab";
            default -> value.isBlank() ? "unknown" : value;
        };
    }

    private static double parseDouble(String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ignored) {
            return 0;
        }
    }
}
