package dev.domengine.desktop;

import dev.domengine.cdp.CdpException;
import dev.domengine.log.EngineLog;
import dev.domengine.tree.DomNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Linux AT-SPI over the session bus. dbus-send is the client. No bus is a failure, not an empty tree. */
final class AtSpi {
    private static final Pattern NUMBER = Pattern.compile("(?:uint32|int32|uint16|int16)\\s+(-?\\d+)");

    private AtSpi() {
    }

    record Ref(String dest, String path) {
    }

    static void requireLinux() {
        if (!"linux".equals(NativeDesktop.family())) throw new CdpException("requires Linux");
    }

    static DomNode capture(String windowName) {
        requireLinux();
        String address = busAddress();
        int[] count = {0};
        boolean[] truncated = {false};
        DomNode root = read(address, "org.a11y.atspi.Registry", "/org/a11y/atspi/accessible/root", 0, count, truncated, true);
        if (truncated[0]) root.attributes.put("walk-truncated", "true");
        EngineLog.info("desktop", "atspi nodes=" + count[0] + " truncated=" + truncated[0]);
        return root;
    }

    static void click(DomNode node) {
        requireLinux();
        String address = busAddress();
        String dest = required(node, "atspi-dest");
        String path = required(node, "atspi-path");
        NativeDesktop.CommandResult action = call(address, dest, path, "org.a11y.atspi.Action.DoAction", "int32:0");
        if (!action.timedOut() && action.exit() == 0 && action.output().contains("boolean true")) return;
        if (node == null || !node.hasBox) {
            throw new CdpException("click failed: AT-SPI DoAction failed (" + NativeDesktop.oneLine(action.output())
                    + ") and the control has no bounds");
        }
        Path xdotool = NativeDesktop.tool("xdotool");
        if (xdotool == null) {
            throw new CdpException("click failed: AT-SPI DoAction failed (" + NativeDesktop.oneLine(action.output())
                    + ") and xdotool is not installed");
        }
        int x = (int) Math.round(node.x + node.width / 2.0);
        int y = (int) Math.round(node.y + node.height / 2.0);
        NativeDesktop.CommandResult pointer = NativeDesktop.run(List.of(
                xdotool.toString(), "mousemove", Integer.toString(x), Integer.toString(y), "click", "1"
        ));
        if (pointer.timedOut() || pointer.exit() != 0) {
            throw new CdpException("click failed: xdotool " + NativeDesktop.oneLine(pointer.output()));
        }
    }

    static void type(DomNode node, String text, boolean append) {
        requireLinux();
        String address = busAddress();
        String dest = required(node, "atspi-dest");
        String path = required(node, "atspi-path");
        String value = text == null ? "" : text;
        if (append) {
            String current = textContents(address, dest, path);
            if (current == null) throw new CdpException("type failed: append requires a readable AT-SPI text value");
            value = current + value;
        }
        NativeDesktop.CommandResult set = call(
                address, dest, path, "org.a11y.atspi.EditableText.SetTextContents", "string:" + value
        );
        if (!set.timedOut() && set.exit() == 0 && set.output().contains("boolean true")) return;
        NativeDesktop.CommandResult focus = call(address, dest, path, "org.a11y.atspi.Component.GrabFocus");
        Path xdotool = NativeDesktop.tool("xdotool");
        if (focus.timedOut() || focus.exit() != 0 || xdotool == null) {
            throw new CdpException("type failed: AT-SPI SetTextContents failed (" + NativeDesktop.oneLine(set.output())
                    + ")" + (xdotool == null ? " and xdotool is not installed" : " and GrabFocus failed ("
                    + NativeDesktop.oneLine(focus.output()) + ")"));
        }
        NativeDesktop.CommandResult typed = NativeDesktop.run(List.of(xdotool.toString(), "type", "--", value));
        if (typed.timedOut() || typed.exit() != 0) {
            throw new CdpException("type failed: xdotool " + NativeDesktop.oneLine(typed.output()));
        }
    }

    static String busAddress() {
        requireLinux();
        String session = System.getenv("DBUS_SESSION_BUS_ADDRESS");
        if ((session == null || session.isBlank()) && !Files.exists(sessionBusSocket())) {
            throw new CdpException("AT-SPI unavailable: no session bus (DBUS_SESSION_BUS_ADDRESS is unset)");
        }
        if (NativeDesktop.tool("dbus-send") == null) {
            throw new CdpException("AT-SPI unavailable: dbus-send is not installed");
        }
        NativeDesktop.CommandResult result = NativeDesktop.run(List.of(
                "dbus-send", "--session", "--print-reply",
                "--dest=org.a11y.Bus", "/org/a11y/bus", "org.a11y.Bus.GetAddress"
        ));
        if (result.timedOut()) {
            throw new CdpException("AT-SPI unavailable: dbus-send timed out calling org.a11y.Bus.GetAddress");
        }
        if (result.exit() != 0) throw new CdpException("AT-SPI unavailable: " + NativeDesktop.oneLine(result.output()));
        List<String> strings = strings(result.output());
        if (strings.isEmpty() || strings.get(0).isBlank()) {
            throw new CdpException("AT-SPI unavailable: org.a11y.Bus.GetAddress returned no address");
        }
        return strings.get(0);
    }

    /** AT-SPI role number to the shared query role. Unknown numbers stay {@code unknown}; the number is stored separately. */
    static String roleName(int role) {
        return switch (role) {
            case 7 -> "checkbox";
            case 11 -> "combobox";
            case 16 -> "dialog";
            case 22, 68 -> "window";
            case 26 -> "img";
            case 28 -> "label";
            case 30 -> "list";
            case 31 -> "listitem";
            case 32 -> "menu";
            case 34 -> "menuitem";
            case 36 -> "tab";
            case 39, 60, 78 -> "textbox";
            case 42, 61 -> "button";
            case 43 -> "radio";
            case 54 -> "table";
            case 55 -> "cell";
            case 74 -> "application";
            case 82 -> "heading";
            case 87 -> "link";
            case 91, 92, 93, 94, 95 -> "document";
            default -> "unknown";
        };
    }

    static List<String> strings(String output) {
        List<String> found = new ArrayList<>();
        if (output == null) return found;
        int cursor = 0;
        while (cursor < output.length()) {
            int at = indexOfToken(output, "string \"", cursor);
            if (at < 0) break;
            int start = at + "string \"".length();
            int end = endQuote(output, start);
            found.add(unescape(output.substring(start, end)));
            cursor = end + 1;
        }
        return found;
    }

    static List<Ref> objectRefs(String output) {
        List<Ref> refs = new ArrayList<>();
        if (output == null) return refs;
        int cursor = 0;
        while (cursor < output.length()) {
            int at = indexOfToken(output, "string \"", cursor);
            if (at < 0) break;
            int start = at + "string \"".length();
            int end = endQuote(output, start);
            String dest = unescape(output.substring(start, end));
            int pathAt = indexOfToken(output, "object path \"", end);
            if (pathAt < 0) break;
            int pathStart = pathAt + "object path \"".length();
            int pathEnd = endQuote(output, pathStart);
            refs.add(new Ref(dest, output.substring(pathStart, pathEnd)));
            cursor = pathEnd + 1;
        }
        return refs;
    }

    static int lastInt(String output, int fallback) {
        if (output == null) return fallback;
        Matcher matcher = NUMBER.matcher(output);
        int value = fallback;
        boolean found = false;
        while (matcher.find()) {
            value = Integer.parseInt(matcher.group(1));
            found = true;
        }
        return found ? value : fallback;
    }

    private static DomNode read(
            String address, String dest, String path, int depth, int[] count, boolean[] truncated, boolean root
    ) {
        if (depth > NativeDesktop.WALK_DEPTH || count[0] >= NativeDesktop.WALK_NODES) {
            truncated[0] = true;
            return null;
        }
        count[0]++;
        DomNode node = new DomNode();
        node.nodeType = 1;
        node.displayed = true;
        node.frameId = "desktop";
        node.attributes.put("desktop-backend", "atspi");
        node.attributes.put("atspi-dest", dest);
        node.attributes.put("atspi-path", path);
        int role = propertyInt(address, dest, path, "Role");
        String mapped = roleName(role);
        node.role = mapped;
        node.localName = mapped;
        node.nodeName = mapped.toUpperCase(Locale.ROOT);
        node.accessibleName = propertyString(address, dest, path, "Name");
        node.attributes.put("atspi-role", Integer.toString(role));
        int[] box = extents(address, dest, path);
        if (box != null) {
            node.x = box[0];
            node.y = box[1];
            node.width = box[2];
            node.height = box[3];
            node.frameX = node.x;
            node.frameY = node.y;
            node.hasBox = node.width > 0 && node.height > 0;
        }
        List<Ref> children = children(address, dest, path, root);
        for (Ref child : children) {
            if (count[0] >= NativeDesktop.WALK_NODES) {
                truncated[0] = true;
                break;
            }
            DomNode next = read(address, child.dest(), child.path(), depth + 1, count, truncated, false);
            if (next != null) node.children.add(next);
        }
        return node;
    }

    private static String propertyString(String address, String dest, String path, String name) {
        NativeDesktop.CommandResult result = call(
                address, dest, path, "org.freedesktop.DBus.Properties.Get",
                "string:org.a11y.atspi.Accessible", "string:" + name
        );
        if (result.timedOut() || result.exit() != 0) return "";
        List<String> values = strings(result.output());
        return values.isEmpty() ? "" : values.get(0);
    }

    private static int propertyInt(String address, String dest, String path, String name) {
        NativeDesktop.CommandResult result = call(
                address, dest, path, "org.freedesktop.DBus.Properties.Get",
                "string:org.a11y.atspi.Accessible", "string:" + name
        );
        if (result.timedOut() || result.exit() != 0) return -1;
        return lastInt(result.output(), -1);
    }

    private static List<Ref> children(String address, String dest, String path, boolean root) {
        NativeDesktop.CommandResult result = call(address, dest, path, "org.a11y.atspi.Accessible.GetChildren");
        if (result.timedOut() || result.exit() != 0) {
            if (root) {
                throw new CdpException("AT-SPI unavailable: cannot read desktop children ("
                        + NativeDesktop.oneLine(result.output()) + ")");
            }
            EngineLog.warn("desktop", "atspi children " + path + " " + NativeDesktop.oneLine(result.output()));
            return List.of();
        }
        return objectRefs(result.output());
    }

    private static int[] extents(String address, String dest, String path) {
        NativeDesktop.CommandResult result = call(
                address, dest, path, "org.a11y.atspi.Component.GetExtents", "uint32:0"
        );
        if (result.timedOut() || result.exit() != 0) return null;
        Matcher matcher = NUMBER.matcher(result.output());
        int[] box = new int[4];
        int found = 0;
        while (matcher.find() && found < 4) box[found++] = Integer.parseInt(matcher.group(1));
        return found == 4 ? box : null;
    }

    private static String textContents(String address, String dest, String path) {
        NativeDesktop.CommandResult result = call(
                address, dest, path, "org.a11y.atspi.Text.GetText", "int32:0", "int32:-1"
        );
        if (result.timedOut() || result.exit() != 0) return null;
        List<String> values = strings(result.output());
        return values.isEmpty() ? "" : values.get(0);
    }

    private static NativeDesktop.CommandResult call(
            String address, String dest, String path, String method, String... typed
    ) {
        Path tool = NativeDesktop.tool("dbus-send");
        if (tool == null) throw new CdpException("AT-SPI unavailable: dbus-send is not installed");
        List<String> command = new ArrayList<>();
        command.add(tool.toString());
        command.add("--address=" + address);
        command.add("--print-reply");
        command.add("--dest=" + dest);
        command.add(path);
        command.add(method);
        for (String arg : typed) command.add(arg);
        return NativeDesktop.run(command);
    }

    private static String required(DomNode node, String name) {
        String value = node == null ? null : node.attributes.get(name);
        if (value == null || value.isBlank()) throw new CdpException("live desktop node is missing " + name);
        return value;
    }

    private static Path sessionBusSocket() {
        String uid = "0";
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("Uid:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length > 1) uid = parts[1];
                    break;
                }
            }
        } catch (Exception ignored) {
            String env = System.getenv("UID");
            if (env != null && !env.isBlank()) uid = env.trim();
        }
        return Path.of("/run/user/" + uid + "/bus");
    }

    private static int indexOfToken(String output, String token, int from) {
        return output.indexOf(token, from);
    }

    private static int endQuote(String output, int start) {
        boolean escape = false;
        for (int i = start; i < output.length(); i++) {
            char c = output.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') return i;
        }
        return output.length();
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
