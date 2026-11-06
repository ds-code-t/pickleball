package tools.dscode.workbench.ui;

import javax.swing.JLabel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.LinkedHashMap;
import java.util.Map;

/** Workbench-native overlay. Same command map as the in-page helper. Headless is a no-op. */
public final class WorkbenchNativeOverlay {
    private static final Map<String, JWindow> WINDOWS = new LinkedHashMap<>();

    private WorkbenchNativeOverlay() {
    }

    public static void place(Map<String, Object> command) {
        if (command == null || GraphicsEnvironment.isHeadless()) return;
        String id = String.valueOf(command.getOrDefault("id", "overlay"));
        String sentence = String.valueOf(command.getOrDefault("sentence", ""));
        Object left = command.get("x");
        Object top = command.get("y");
        int x = left instanceof Number number ? number.intValue() : 24;
        int y = top instanceof Number number ? number.intValue() : 24;
        SwingUtilities.invokeLater(() -> {
            JWindow window = new JWindow();
            window.getContentPane().add(new JLabel(sentence));
            window.setLocation(x, y);
            window.pack();
            window.setAlwaysOnTop(true);
            window.setVisible(true);
            JWindow previous = WINDOWS.put(id, window);
            if (previous != null) previous.dispose();
        });
    }

    public static void remove(Object id) {
        if (GraphicsEnvironment.isHeadless()) return;
        if (id == null || "null".equals(String.valueOf(id)) || String.valueOf(id).isBlank()) {
            WINDOWS.values().forEach(window -> SwingUtilities.invokeLater(window::dispose));
            WINDOWS.clear();
            return;
        }
        JWindow window = WINDOWS.remove(String.valueOf(id));
        if (window != null) SwingUtilities.invokeLater(window::dispose);
    }
}
