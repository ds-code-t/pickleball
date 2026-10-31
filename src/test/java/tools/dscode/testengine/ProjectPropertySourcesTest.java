package tools.dscode.testengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProjectPropertySourcesTest {
    @TempDir
    Path temp;

    @Test
    void normalLaunchReadsClasspathAsIso88591AndIgnoresProjectFiles() throws Exception {
        Path classpath = temp.resolve("cp");
        Files.createDirectories(classpath);
        Files.write(classpath.resolve("pickleball.properties"), "pkb_name=caf\u00e9\n".getBytes(StandardCharsets.ISO_8859_1));
        Files.writeString(temp.resolve("pickleball.properties"), "pkb_name=from-file\npkb_browser=from-file\n", StandardCharsets.UTF_8);

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classpath.toUri().toURL()}, null)) {
            Thread.currentThread().setContextClassLoader(loader);
            Map<String, String> normal = ProjectPropertySources.read(temp);
            assertEquals("caf\u00e9", normal.get("pkb_name"));
            assertFalse(normal.containsKey("pkb_browser"), normal.toString());
            Map<String, String> workbench = ProjectPropertySources.readWorkbench(temp);
            assertEquals("from-file", workbench.get("pkb_name"));
            assertEquals("from-file", workbench.get("pkb_browser"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void workbenchPreviewSeesAProjectFileANormalReadDoesNot(@TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("src/test/resources"));
        Files.writeString(project.resolve("src/test/resources/pickleball.properties"),
                "pkb_name=from-workbench-file\n", StandardCharsets.UTF_8);
        assertNull(ProjectPropertySources.readNormal().get("pkb_name"));
        PKB_props.ResolvedRunVars preview = PickleballRunner.previewLaunch(project);
        assertEquals("from-workbench-file", preview.runVars().get("pkb_name"));
    }

    @Test
    void utf8BytesStayIso88591OnTheClasspathAndAreUtf8OnlyInWorkbenchFiles() throws Exception {
        assertEquals(StandardCharsets.ISO_8859_1, ProjectPropertySources.NORMAL_CHARSET);
        assertEquals(StandardCharsets.UTF_8, ProjectPropertySources.WORKBENCH_FILE_CHARSET);
        byte[] utf8 = "pkb_name=caf\u00e9\n".getBytes(StandardCharsets.UTF_8);
        assertEquals("caf\u00c3\u00a9", new String("caf\u00e9".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));

        Path classpath = temp.resolve("cp");
        Files.createDirectories(classpath);
        Files.write(classpath.resolve("pickleball.properties"), utf8);
        Files.write(temp.resolve("pickleball.properties"), utf8);
        Files.createDirectories(temp.resolve("src/test/resources"));
        Files.write(temp.resolve("src/test/resources/pickleball_local.properties"),
                "pkb_browser=caf\u00e9\n".getBytes(StandardCharsets.UTF_8));

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classpath.toUri().toURL()}, null)) {
            Thread.currentThread().setContextClassLoader(loader);
            Map<String, String> normal = ProjectPropertySources.read(temp);
            assertEquals("caf\u00c3\u00a9", normal.get("pkb_name"));
            assertFalse(normal.containsKey("pkb_browser"), normal.toString());

            Map<String, String> workbench = ProjectPropertySources.readWorkbench(temp);
            assertEquals("caf\u00e9", workbench.get("pkb_name"));
            assertEquals("caf\u00e9", workbench.get("pkb_browser"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
