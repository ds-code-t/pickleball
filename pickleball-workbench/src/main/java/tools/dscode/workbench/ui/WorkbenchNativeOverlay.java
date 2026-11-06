package tools.dscode.workbench.ui;

import javax.swing.JLabel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Workbench-native overlay. Same command map as the in-page helper. Headless does not open a window. */
public final class WorkbenchNativeOverlay {
    private static final Map<String, JWindow> WINDOWS = new LinkedHashMap<>();
    private static final List<String> REMOVED = new ArrayList<>();

    private WorkbenchNativeOverlay() {
    }

    /** Guidance text plus the provenance sentence. Headless callers can read this without a window. */
    public static String caption(Map<String, Object> command) {
        if (command == null) return "";
        String guidance = text(command.get("guidance"));
        if (guidance.isBlank()) guidance = text(command.get("text"));
        String sentence = text(command.get("sentence"));
        if (guidance.isBlank()) return sentence;
        if (sentence.isBlank()) return guidance;
        return guidance + " — " + sentence;
    }

    public static void place(Map<String, Object> command) {
        if (command == null) return;
        String id = String.valueOf(command.getOrDefault("id", "overlay"));
        String shown = caption(command);
        if (GraphicsEnvironment.isHeadless()) return;
        Object left = command.get("x");
        Object top = command.get("y");
        int x = left instanceof Number number ? number.intValue() : 24;
        int y = top instanceof Number number ? number.intValue() : 24;
        SwingUtilities.invokeLater(() -> {
            JWindow window = new JWindow();
            window.getContentPane().add(new JLabel(shown));
            window.setLocation(x, y);
            window.pack();
            window.setAlwaysOnTop(true);
            window.setVisible(true);
            JWindow previous = WINDOWS.put(id, window);
            if (previous != null) previous.dispose();
        });
    }

    public static void remove(Object id) {
        String key = id == null || "null".equals(String.valueOf(id)) || String.valueOf(id).isBlank()
                ? "*"
                : String.valueOf(id);
        REMOVED.add(key);
        if (GraphicsEnvironment.isHeadless()) {
            if ("*".equals(key)) WINDOWS.clear();
            else WINDOWS.remove(key);
            return;
        }
        if ("*".equals(key)) {
            WINDOWS.values().forEach(window -> SwingUtilities.invokeLater(window::dispose));
            WINDOWS.clear();
            return;
        }
        JWindow window = WINDOWS.remove(key);
        if (window != null) SwingUtilities.invokeLater(window::dispose);
    }

    public static List<String> removedForTests() {
        return List.copyOf(REMOVED);
    }

    public static void resetForTests() {
        REMOVED.clear();
        WINDOWS.clear();
    }

    private static String text(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        return "null".equals(text) ? "" : text;
    }
}