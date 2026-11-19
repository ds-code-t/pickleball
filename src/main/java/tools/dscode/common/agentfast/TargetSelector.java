package tools.dscode.common.agentfast;

/**
 * One target spelling for browser, desktop, and snapshot refs.
 * {@code ref:} splits on the last colon. {@code browser:cdp=} keeps the URL up to {@code /tab:} or {@code /frame:}.
 */
public final class TargetSelector {
    public record Parsed(
            String raw,
            String kind,
            String session,
            String cdp,
            String tab,
            String frame,
            String pid,
            String app,
            String window,
            String docVersion,
            String handle,
            boolean auto
    ) {
    }

    private TargetSelector() {
    }

    public static Parsed parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return blank("");
        if (text.startsWith("ref:")) return ref(text);
        if (text.startsWith("browser:")) return browser(text);
        if (text.startsWith("desktop:")) return desktop(text);
        return blank(text);
    }

    private static Parsed ref(String text) {
        String rest = text.substring("ref:".length());
        int colon = rest.lastIndexOf(':');
        String doc = colon < 0 ? rest : rest.substring(0, colon);
        String handle = colon < 0 ? "" : rest.substring(colon + 1);
        return new Parsed(text, "ref", "", "", "", "", "", "", "", doc, handle, false);
    }

    private static Parsed browser(String text) {
        String rest = text.substring("browser:".length());
        int tabAt = rest.lastIndexOf("/tab:");
        int frameAt = rest.lastIndexOf("/frame:");
        int cut = -1;
        if (tabAt >= 0) cut = tabAt;
        if (frameAt >= 0 && (cut < 0 || frameAt < cut)) cut = frameAt;
        String head = cut < 0 ? rest : rest.substring(0, cut);
        String tail = cut < 0 ? "" : rest.substring(cut);
        String tab = segment(tail, "/tab:");
        String frame = segment(tail, "/frame:");
        if ("auto".equals(head)) {
            return new Parsed(text, "browser", "", "", tab, frame, "", "", "", "", "", true);
        }
        if (head.startsWith("cdp=")) {
            return new Parsed(text, "browser", "", head.substring("cdp=".length()), tab, frame, "", "", "", "", "", false);
        }
        return new Parsed(text, "browser", head, "", tab, frame, "", "", "", "", "", false);
    }

    private static Parsed desktop(String text) {
        String rest = text.substring("desktop:".length());
        int windowAt = rest.lastIndexOf("/window:");
        String head = windowAt < 0 ? rest : rest.substring(0, windowAt);
        String window = windowAt < 0 ? "" : rest.substring(windowAt + "/window:".length());
        if (head.startsWith("app~")) {
            return new Parsed(text, "desktop", "", "", "", "", "", head.substring("app~".length()), window, "", "", false);
        }
        boolean digits = !head.isEmpty();
        for (int index = 0; index < head.length(); index++) {
            if (!Character.isDigit(head.charAt(index))) {
                digits = false;
                break;
            }
        }
        if (digits) {
            return new Parsed(text, "desktop", "", "", "", "", head, "", window, "", "", false);
        }
        return new Parsed(text, "desktop", "", "", "", "", "", head, window, "", "", false);
    }

    /** Value after {@code marker} up to the next slash-marker, or the end. */
    private static String segment(String tail, String marker) {
        int at = tail.indexOf(marker);
        if (at < 0) return "";
        String value = tail.substring(at + marker.length());
        int slash = value.indexOf('/');
        return slash < 0 ? value : value.substring(0, slash);
    }

    private static Parsed blank(String raw) {
        return new Parsed(raw, "none", "", "", "", "", "", "", "", "", "", false);
    }
}
