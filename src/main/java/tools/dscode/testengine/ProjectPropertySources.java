package tools.dscode.testengine;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;

/**
 * The only reader for {@code pickleball.properties} and its local overlays.
 * The runner, Workbench dry-run, and {@code resolve-runvars} all call {@link #read}.
 */
final class ProjectPropertySources {
    static final List<String> CLASSPATH_RESOURCES = List.of(
            "pickleball.properties",
            "pickleball_local.properties",
            "pickleball_local2.properties"
    );

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
     * Classpath resources first, then project files. Later sources overwrite earlier ones.
     * Same order {@link PickleballRunner} used before this reader was shared.
     */
    static LinkedHashMap<String, String> read(Path root) {
        LinkedHashMap<String, String> project = new LinkedHashMap<>();
        for (String resource : CLASSPATH_RESOURCES) {
            loadClasspath(resource, project);
        }
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
                    props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
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
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
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
