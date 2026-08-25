package tools.dscode.workbench.ui.web;

import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.net.URL;
import java.util.Objects;
import java.util.function.Consumer;

/** Swing host for one Workbench HTML/JS panel running inside JavaFX WebView. */
public final class WebViewPanel extends JPanel {
    private final String resource;
    private final String bridgeName;
    private final Object bridge;
    private final javafx.embed.swing.JFXPanel fxPanel;
    private WebEngine engine;
    private volatile boolean ready;

    public WebViewPanel(String resource, String bridgeName, Object bridge) {
        this.resource = Objects.requireNonNull(resource, "resource");
        this.bridgeName = Objects.requireNonNull(bridgeName, "bridgeName");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        setLayout(new BorderLayout());
        setOpaque(true);
        if (!JavaFxSupport.ensureInitialized()) {
            throw new IllegalStateException("JavaFX WebView is not available: " + JavaFxSupport.failure());
        }
        fxPanel = new javafx.embed.swing.JFXPanel();
        add(fxPanel, BorderLayout.CENTER);
        JavaFxSupport.runLater(this::attachScene);
    }

    public boolean ready() {
        return ready;
    }

    public void eval(String script) {
        if (engine == null) return;
        JavaFxSupport.runLater(() -> {
            try {
                engine.executeScript(script);
            } catch (RuntimeException ignored) {
                // The page may not have finished installing helpers yet.
            }
        });
    }

    public void evalJsonCall(String functionName, String json) {
        String escaped = json
                .replace("\\", "\\\\")
                .replace("'", "\\'");
        eval(functionName + "('" + escaped + "')");
    }

    private void attachScene() {
        WebView view = new WebView();
        view.setContextMenuEnabled(false);
        engine = view.getEngine();
        engine.getLoadWorker().stateProperty().addListener((observable, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                installBridge();
                ready = true;
            }
        });
        URL url = WebViewPanel.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing Workbench WebView resource: " + resource);
        }
        engine.load(url.toExternalForm());
        fxPanel.setScene(new Scene(view, Color.web("#f8fafc")));
    }

    private void installBridge() {
        JSObject window = (JSObject) engine.executeScript("window");
        window.setMember(bridgeName, bridge);
        try {
            engine.executeScript("if (window.onWorkbenchReady) window.onWorkbenchReady();");
        } catch (RuntimeException ignored) {
            // Optional page hook.
        }
    }

    public static void onSwing(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    public static void onSwing(Consumer<Void> action) {
        onSwing(() -> action.accept(null));
    }
}
