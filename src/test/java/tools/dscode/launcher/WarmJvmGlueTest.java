package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The warm JVM runs each command in a fresh class loader whose parent is the
 * platform loader, then {@link WorkbenchDirectRun}. Consumer {@code @Before}
 * state and {@code @DataTableType} converters have to be registered there
 * before the first step.
 */
class WarmJvmGlueTest {
    @Test
    void warmJvmRegistersDataTableConvertersAndBeforeHooks(@TempDir Path project) throws Exception {
        Path classes = project.resolve("target").resolve("test-classes");
        Files.createDirectories(classes);
        Path feature = classes.resolve("login.feature");
        Files.writeString(feature, """
                @warm-glue
                Feature: warm glue
                  Scenario: login
                    When the operator signs in with
                      | user | role |
                      | ada  | mgr  |
                """, StandardCharsets.UTF_8);
        Path result = project.resolve("result.txt");
        String featureArg = feature.toAbsolutePath().normalize().toString();
        List<String> dashD = List.of(
                "-Dpkb_engine=native",
                "-Dpkb_glue=tools.dscode.warmglue",
                "-Dcucumber.glue=tools.dscode.warmglue",
                "-Dpkb_features=" + featureArg,
                "-Dcucumber.features=" + featureArg,
                "-Dpkb_tags=@warm-glue",
                "-Dcucumber.filter.tags=@warm-glue",
                "-Dpkb_name=login",
                "-Dcucumber.filter.name=login",
                "-Dpkb_warm_glue_result=" + result.toAbsolutePath().normalize(),
                "-Dpkb_loglevel=error",
                "-Dcucumber.execution.parallel.enabled=false",
                "-Dpkb_parallel=1"
        );
        Properties before = new Properties();
        before.putAll(System.getProperties());
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (URLClassLoader loader = newLoader()) {
            Thread.currentThread().setContextClassLoader(loader);
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            Class<?> type = Class.forName("tools.dscode.launcher.WorkbenchDirectRun", true, loader);
            Object value = type.getMethod("run", Path.class, List.class).invoke(null, project, dashD);
            int exit = (Integer) value;
            String written = Files.isRegularFile(result) ? Files.readString(result).trim() : "";
            String log = captured.toString(StandardCharsets.UTF_8);
            if (log.length() > 2000) log = log.substring(log.length() - 2000);
            assertEquals(0, exit, written + "\n" + log);
            assertEquals("ready:login|ada|mgr", written);
        } finally {
            System.setOut(out);
            System.setErr(err);
            Thread.currentThread().setContextClassLoader(previous);
            System.setProperties(before);
        }
    }

    /** Same loader shape as {@code WorkbenchWarmMain}. */
    private static URLClassLoader newLoader() throws Exception {
        String classpath = System.getProperty("java.class.path", "");
        List<URL> urls = new ArrayList<>();
        for (String entry : classpath.split(java.io.File.pathSeparator, -1)) {
            if (entry.isBlank()) continue;
            urls.add(Path.of(entry).toAbsolutePath().normalize().toUri().toURL());
        }
        assertTrue(urls.size() > 0);
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }
}
