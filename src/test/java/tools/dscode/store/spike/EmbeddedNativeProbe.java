package tools.dscode.store.spike;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Child JVM for the sqlite-jdbc spike. Not a test. */
public final class EmbeddedNativeProbe {
    private EmbeddedNativeProbe() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "load" : args[0];
        if ("sleep".equals(mode)) {
            Thread.sleep(8_000);
            return;
        }
        String dir = System.getProperty("org.sqlite.tmpdir", System.getProperty("java.io.tmpdir"));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        }
        System.out.println("loaded tmpdir=" + dir);
        try (var stream = Files.list(Path.of(dir))) {
            stream.forEach(path -> System.out.println("file " + path.getFileName()));
        }
    }
}
