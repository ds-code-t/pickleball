package tools.dscode.testengine;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;

/**
 * The only reader for {@code pickleball.properties} and its local overlays.
 * A normal launch reads classpath resources as ISO-8859-1, which is what
 * {@link Properties#load(InputStream)} did before 2.1.18. Project-root and
 * {@code src/test/resources} files are a Workbench addition and are not part
 * of a normal launch.
 */
final class ProjectPropertySources {
    static final List<String> CLASSPATH_RESOURCES = List.of(
            "pickleball.properties",
            "pickleball_local.properties",
            "pickleball_local2.properties"
    );
    /** Historical {@code Properties.load(InputStream)} encoding. */
    static final Charset NORMAL_CHARSET = StandardCharsets.ISO_8859_1;
    /** Encoding of the Workbench-only project files. */
    static final Charset WORKBENCH_FILE_CHARSET = StandardCharsets.UTF_8;

    private ProjectPropertySources() {
    }

    static List<Path> files(Path root) {
        Path working = (root == null ? Path.of("") : root).toAbsolutePath();
        return List.of(
                working.resolve("pickleball.properties"),
                working.resolve("pickleball_local.properties"),
                working.resolve("pickleball_local2.properties"),
                working.resolve("src/test/resources/pickleball.properties"),
                working.resolve("src/test/resources/pickleball_local.properties"),
                working.resolve("src/test/resources/pickleball_local2.properties")
        );
    }

    /**
     * Classpath resources only, ISO-8859-1. Later classpath copies overwrite earlier ones.
     * The path is ignored: a normal launch does not read project files.
     */
    static LinkedHashMap<String, String> read(Path ignored) {
        return readNormal();
    }

    static LinkedHashMap<String, String> readNormal() {
        LinkedHashMap<String, String> project = new LinkedHashMap<>();
        for (String resource : CLASSPATH_RESOURCES) {
            loadClasspath(resource, project);
        }
        return project;
    }

    /**
     * Workbench seed, dry-run, and protected-reference lookup. Classpath resources
     * stay ISO-8859-1. Project-root and {@code src/test/resources} files are then
     * read as UTF-8 and overwrite the classpath copy of the same key.
     */
    static LinkedHashMap<String, String> readWorkbench(Path root) {
        LinkedHashMap<String, String> project = readNormal();
        for (Path file : files(root)) {
            loadFile(file, project);
        }
        return project;
    }

    private static void loadClasspath(String resourceName, LinkedHashMap<String, String> into) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) loader = ProjectPropertySources.class.getClassLoader();
            Enumeration<URL> resources = loader.getResources(resourceName);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                Properties props = new Properties();
                try (InputStream in = url.openStream()) {
                    props.load(in);
                }
                copy(props, into);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Could not read project properties " + resourceName, failure);
        }
    }

    private static void loadFile(Path file, LinkedHashMap<String, String> into) {
        if (file == null || !Files.isRegularFile(file)) return;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(new java.io.InputStreamReader(in, WORKBENCH_FILE_CHARSET));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not read project properties " + file, failure);
        }
        copy(props, into);
    }

    private static void copy(Properties props, LinkedHashMap<String, String> into) {
        for (String key : props.stringPropertyNames()) {
            into.put(PickleballRunner.normalizeConfigurationKey(key), props.getProperty(key));
        }
    }
}
