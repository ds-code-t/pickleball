package tools.dscode.control.protocol.surface;

import java.awt.Color;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.util.LinkedHashMap;
import java.util.Map;

/** Four 3px undecorated strips around a screen rectangle. Headless draws nothing. */
public final class NativeHighlightBox {
    public static final Color COLOR = new Color(0xff2d55);
    private static final Map<String, Strip[]> SHOWN = new LinkedHashMap<>();

    private NativeHighlightBox() {
    }

    public record Drawn(boolean drawn, String reason, String id) {
    }

    public static Drawn show(String id, int x, int y, int width, int height) {
        String key = id == null || id.isBlank() ? "native" : id;
        if (GraphicsEnvironment.isHeadless()) return new Drawn(false, "headless", key);
        double scale = scale();
        Rectangle[] geometry = strips(x, y, width, height, scale);
        Strip[] windows = new Strip[geometry.length];
        for (int index = 0; index < geometry.length; index++) windows[index] = new Strip(geometry[index]);
        Strip[] previous = SHOWN.put(key, windows);
        if (previous != null) {
            for (Strip strip : previous) strip.hide();
        }
        return new Drawn(true, "", key);
    }

    public static Drawn hide(String id) {
        String key = id == null || id.isBlank() ? "native" : id;
        Strip[] windows = SHOWN.remove(key);
        if (windows != null) {
            for (Strip strip : windows) strip.hide();
        }
        if (GraphicsEnvironment.isHeadless()) return new Drawn(false, "headless", key);
        return new Drawn(windows != null, windows == null ? "absent" : "", key);
    }

    public static Rectangle[] strips(int x, int y, int width, int height, double scale) {
        int thickness = Math.max(1, (int) Math.round(3.0 * (scale <= 0 ? 1.0 : scale)));
        int wide = Math.max(width, thickness);
        int tall = Math.max(height, thickness);
        return new Rectangle[] {
                new Rectangle(x, y, wide, thickness),
                new Rectangle(x, y + tall - thickness, wide, thickness),
                new Rectangle(x, y, thickness, tall),
                new Rectangle(x + wide - thickness, y, thickness, tall)
        };
    }

    public static double scale() {
        if (GraphicsEnvironment.isHeadless()) return 1.0;
        GraphicsConfiguration configuration = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        AffineTransform transform = configuration.getDefaultTransform();
        return transform.getScaleX() == 0 ? 1.0 : transform.getScaleX();
    }

    private static final class Strip {
        private final javax.swing.JWindow window;

        Strip(Rectangle bounds) {
            window = new javax.swing.JWindow();
            window.setBackground(COLOR);
            window.getContentPane().setBackground(COLOR);
            window.setAlwaysOnTop(true);
            window.setFocusableWindowState(false);
            window.setBounds(bounds);
            window.setVisible(true);
        }

        void hide() {
            window.setVisible(false);
            window.dispose();
        }
    }
}
