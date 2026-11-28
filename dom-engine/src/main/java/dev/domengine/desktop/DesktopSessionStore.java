package dev.domengine.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Live desktop session ids and their window names, kept on disk so the id {@code desktop launch} or
 * {@code desktop attach} printed still works in the next CLI process ({@code desktop type/click/close --id=}).
 * The file is {@code domengine.desktop.sessions} (system property) or {@code <tmp>/domengine-desktop-sessions.properties}.
 */
public final class DesktopSessionStore {
    private DesktopSessionStore() {
    }

    static Path file() {
        String override = System.getProperty("domengine.desktop.sessions");
        if (override != null && !override.isBlank()) return Path.of(override);
        return Path.of(System.getProperty("java.io.tmpdir"), "domengine-desktop-sessions.properties");
    }

    public static synchronized void remember(String id, String window) {
        if (id == null || id.isBlank() || window == null || window.isBlank()) return;
        Properties all = load();
        all.setProperty(id, window);
        save(all);
    }

    /** The window name a live id was attached to, or null. */
    public static synchronized String windowOf(String id) {
        if (id == null || id.isBlank()) return null;
        return load().getProperty(id);
    }

    public static synchronized boolean forget(String id) {
        if (id == null || id.isBlank()) return false;
        Properties all = load();
        if (all.remove(id) == null) return false;
        save(all);
        return true;
    }

    private static Properties load() {
        Properties all = new Properties();
        Path file = file();
        if (!Files.isRegularFile(file)) return all;
        try (InputStream in = Files.newInputStream(file)) {
            all.load(in);
        } catch (IOException ignored) {
            // A damaged file is treated as empty.
        }
        return all;
    }

    private static void save(Properties all) {
        Path file = file();
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                all.store(out, "domengine live desktop sessions");
            }
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Best effort: the id still works in this process.
        }
    }
}
