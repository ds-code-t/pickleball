package tools.dscode.common.agentsurface;

/** {@code pkb_agent_popup*} settings. Unset mode is {@code auto}. */
public final class PopupConfig {
    private PopupConfig() {
    }

    public static String mode() {
        String raw = PopupLog.setting("pkb_agent_popup", "PKB_AGENT_POPUP");
        if (raw == null || raw.isBlank()) return "auto";
        String value = raw.trim().toLowerCase();
        return switch (value) {
            case "off", "auto", "always" -> value;
            case "false", "no", "0" -> "off";
            case "true", "on", "1" -> "always";
            default -> throw new IllegalArgumentException("pkb_agent_popup must be off|auto|always but was '" + raw.trim() + "'");
        };
    }

    public static boolean headless() {
        return truth(PopupLog.setting("pkb_agent_popup_headless", "PKB_AGENT_POPUP_HEADLESS"));
    }

    public static boolean onTop() {
        String raw = PopupLog.setting("pkb_agent_popup_on_top", "PKB_AGENT_POPUP_ON_TOP");
        if (raw == null) return true;
        return truth(raw);
    }

    public static int width() {
        return size()[0];
    }

    public static int height() {
        return size()[1];
    }

    public static String position() {
        String raw = PopupLog.setting("pkb_agent_popup_position", "PKB_AGENT_POPUP_POSITION");
        return raw == null ? "right" : raw.trim();
    }

    public static long idleMillis() {
        String raw = PopupLog.setting("pkb_agent_popup_idle_minutes", "PKB_AGENT_POPUP_IDLE_MINUTES");
        if (raw == null || raw.isBlank()) return 30L * 60_000L;
        try {
            double minutes = Double.parseDouble(raw.trim());
            if (minutes < 0) return 30L * 60_000L;
            return (long) (minutes * 60_000L);
        } catch (NumberFormatException failure) {
            return 30L * 60_000L;
        }
    }

    public static int[] origin(int width, int height) {
        String raw = position();
        if (raw.matches("-?\\d+\\s*,\\s*-?\\d+")) {
            String[] parts = raw.split(",");
            return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        }
        if (java.awt.GraphicsEnvironment.isHeadless()) return new int[] {0, 0};
        java.awt.Rectangle bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        int x = "left".equalsIgnoreCase(raw) ? bounds.x : bounds.x + Math.max(0, bounds.width - width);
        return new int[] {x, bounds.y};
    }

    public static String browserOverride() {
        String property = PopupLog.setting("pkb_agent_popup_browser", "PKB_AGENT_POPUP_BROWSER");
        return property;
    }

    private static int[] size() {
        String raw = PopupLog.setting("pkb_agent_popup_size", "PKB_AGENT_POPUP_SIZE");
        if (raw == null) return new int[] {420, 640};
        String[] parts = raw.toLowerCase().replace('x', ',').split(",");
        try {
            int width = Integer.parseInt(parts[0].trim());
            int height = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 640;
            return new int[] {width, height};
        } catch (NumberFormatException failure) {
            return new int[] {420, 640};
        }
    }

    private static boolean truth(String raw) {
        if (raw == null) return false;
        String value = raw.trim();
        return "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value) || "1".equals(value);
    }
}
