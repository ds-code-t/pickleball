package tools.dscode.workbench.ui.web;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;

/**
 * Workbench-only JavaFX bootstrap.
 *
 * <p>Investigation chose OpenJFX {@code WebView} + {@code JFXPanel} over JCEF.
 * JDK 21 does not ship a modern browser panel. JavaFX WebKit packages as
 * Maven-central modules that stay on the Workbench classpath, can be shaded
 * into the controller executable, and do not introduce Pickleball core,
 * Chromium download caches, or a second Gherkin runtime. JCEF would require
 * native Chromium bits that are harder to keep Workbench-only and isolation
 * clean.</p>
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
            extractNatives();
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

    static String platformKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("win")) return "win";
        if (os.contains("mac")) return arm ? "mac-aarch64" : "mac";
        return "linux";
    }

    private static void extractNatives() throws IOException {
        String platform = platformKey();
        Path cache = Path.of(System.getProperty("java.io.tmpdir"), "pickleball-workbench-javafx", platform);
        Files.createDirectories(cache);
        URL codeSource = JavaFxSupport.class.getProtectionDomain().getCodeSource() == null
                ? null
                : JavaFxSupport.class.getProtectionDomain().getCodeSource().getLocation();
        if (codeSource != null && "file".equals(codeSource.getProtocol()) && codeSource.getPath().endsWith(".jar")) {
            try {
                extractFromJar(new File(codeSource.toURI()).toPath(), "javafx-natives/" + platform + "/", cache);
            } catch (Exception ignored) {
                // Best effort; OpenJFX may still resolve natives from the shaded JAR root.
            }
        }
        String current = System.getProperty("java.library.path", "");
        if (!current.contains(cache.toString())) {
            System.setProperty(
                    "java.library.path",
                    cache + System.getProperty("path.separator") + current
            );
        }
    }

    private static void extractFromJar(Path jarFile, String prefix, Path target) throws IOException {
        if (!Files.isRegularFile(jarFile)) return;
        try (JarFile jar = new JarFile(jarFile.toFile())) {
            jar.stream()
                    .filter(entry -> !entry.isDirectory() && entry.getName().startsWith(prefix))
                    .forEach(entry -> {
                        String name = Path.of(entry.getName()).getFileName().toString();
                        Path out = target.resolve(name);
                        try (InputStream in = jar.getInputStream(entry)) {
                            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException ignored) {
                            // Native extraction is best-effort.
                        }
                    });
        }
    }
}
