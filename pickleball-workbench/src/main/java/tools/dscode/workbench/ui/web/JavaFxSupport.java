package tools.dscode.workbench.ui.web;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Workbench-only JavaFX bootstrap.
 *
 * <p>Investigation chose OpenJFX {@code WebView} + {@code JFXPanel} over JCEF.
 * JDK 21 does not ship a modern browser panel. JavaFX WebKit packages as
 * Maven-central modules resolved onto the controller child classpath at launch.
 * They are not shaded into the thin nested JAR and do not introduce Pickleball
 * core, Chromium download caches, or a second Gherkin runtime. JCEF would
 * require native Chromium bits that are harder to keep Workbench-only and
 * isolation clean.</p>
 */
public final class JavaFxSupport {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static volatile boolean available;
    private static volatile String failure;

    private JavaFxSupport() {
    }

    public static synchronized boolean ensureInitialized() {
        if (INITIALIZED.get()) return available;
        try {
            if (System.getProperty("prism.order") == null) {
                System.setProperty("prism.order", "sw");
            }
            Platform.setImplicitExit(false);
            new JFXPanel();
            available = true;
            failure = null;
        } catch (Throwable error) {
            available = false;
            failure = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        INITIALIZED.set(true);
        return available;
    }

    public static boolean available() {
        return ensureInitialized();
    }

    public static String failure() {
        ensureInitialized();
        return failure;
    }

    public static void runLater(Runnable action) {
        if (!ensureInitialized()) {
            throw new IllegalStateException("JavaFX WebView is not available: " + failure);
        }
        Platform.runLater(action);
    }

    /**
     * Must stay identical to {@code WorkbenchRuntimeLibs.platformKey()} in the
     * consumer launcher. The launcher duplicates this mapping because it must
     * not load this class (it imports {@code javafx.*}).
     */
    static String platformKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("win")) return "win";
        if (os.contains("mac")) return arm ? "mac-aarch64" : "mac";
        return "linux";
    }
}
