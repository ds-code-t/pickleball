package tools.dscode.common.coordination;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.mappings.FileAndDataParsing;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.resources.ResourceLocations;
import tools.dscode.control.protocol.PickleballPaths;

import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathPrefixResolutionTest {
    @TempDir
    Path temp;

    private ClassLoader previousLoader;
    private boolean loaderCaptured;

    @AfterEach
    void restoreLoader() {
        if (loaderCaptured) {
            Thread.currentThread().setContextClassLoader(previousLoader);
            loaderCaptured = false;
        }
        System.clearProperty("pkb_configpath");
        AgentCoordination.clearCurrent();
    }

    @Test
    void unsetAndClasspathPrefixesUseTheResourcesFolder() throws Exception {
        Fixture fixture = fixture();
        useLoader(fixture.classpathRoot);

        for (String configured : new String[] {null, "", "   ", "configs", "file:configs", "classpath:configs"}) {
            assertClasspath(fixture, configured, fixture.classpathRoot.resolve("configs"), "classpath-configs");
        }
        assertClasspath(fixture, "qa/configs", fixture.classpathRoot.resolve("qa/configs"), "qa");
        assertClasspath(
                fixture,
                "src/test/resources/qa/configs",
                fixture.classpathRoot.resolve("src/test/resources/qa/configs"),
                "slash-resource"
        );
        assertClasspath(
                fixture,
                "file:/opt/x/configs",
                fixture.classpathRoot.resolve("opt/x/configs"),
                "opt-classpath"
        );
    }

    @Test
    void wdFileUsesTheWorkingDirectoryAndAnAbsolutePath() throws Exception {
        Fixture fixture = fixture();
        useLoader(fixture.classpathRoot);

        assertFilesystem(fixture, "wd-file:configs", fixture.work.resolve("configs"), "from-work");
        assertFilesystem(fixture, "WD-FILE:configs", fixture.work.resolve("configs"), "from-work");

        Path absolute = Path.of("/opt/x/configs").normalize();
        ResourceLocations.Location located = located("wd-file:/opt/x/configs", fixture.work);
        assertEquals(PickleballPaths.Kind.FILE_SYSTEM, located.kind());
        assertEquals(absolute, located.file());
        assertNull(ResourceLocations.existingDirectory("wd-file:/opt/x/configs", fixture.work));
        assertNull(ParsingMap.loadConfigs("wd-file:/opt/x/configs", fixture.work));
        assertNull(ResourceLocations.existingDirectory("wd-file:missing-configs", fixture.work));
        assertNull(ParsingMap.loadConfigs("wd-file:missing-configs", fixture.work));
    }

    @Test
    void classpathWinsWhenTheProjectAlsoHasRootAndTestResourceConfigs() throws Exception {
        Fixture fixture = fixture();
        useLoader(fixture.classpathRoot);

        Path resolved = ResourceLocations.existingDirectory("configs", fixture.work);
        assertEquals(fixture.classpathRoot.resolve("configs").toAbsolutePath().normalize(), resolved);
        assertFalse(resolved.startsWith(fixture.project));
        assertEquals("classpath-configs", marker(ParsingMap.loadConfigs("configs", fixture.work)));

        Path fromWdFile = ResourceLocations.existingDirectory("wd-file:configs", fixture.project);
        assertEquals(fixture.project.resolve("configs").toAbsolutePath().normalize(), fromWdFile);
        assertEquals("project-root", marker(ParsingMap.loadConfigs("wd-file:" + fixture.project.resolve("configs"), fixture.work)));
    }

    @Test
    void jarOnlyConfigsLoadFromTheClasspath() throws Exception {
        Fixture fixture = fixture();
        Path jar = temp.resolve("configs.jar");
        try (OutputStream raw = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(raw)) {
            jarOut.putNextEntry(new JarEntry("configs/"));
            jarOut.closeEntry();
            jarOut.putNextEntry(new JarEntry("configs/app.yaml"));
            jarOut.write("marker: from-jar\n".getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            useLoader(loader);
            assertNull(ResourceLocations.classpathDirectory("configs"));
            assertNull(ResourceLocations.existingDirectory("configs", fixture.work));
            assertEquals("from-jar", marker(ParsingMap.loadConfigs("configs", fixture.work)));
            assertEquals("from-jar", marker(ParsingMap.loadConfigs("file:configs", fixture.work)));
            assertFalse(marker(ParsingMap.loadConfigs("configs", fixture.work)).contains("project-root"));
        }
    }

    @Test
    void fileReferenceStaysOnTheClasspathAndWdFileReadsTheDirectory() throws Exception {
        Fixture fixture = fixture();
        useLoader(fixture.classpathRoot);
        JsonNode classpathName = FileAndDataParsing.buildJsonFromPath("files/customers #1.name", false);
        assertEquals("Ava", classpathName.asText());
        String absolute = "wd-file:" + fixture.work.resolve("files/customers").toString().replace('\\', '/');
        JsonNode diskName = FileAndDataParsing.buildJsonFromPath(absolute + " #1.name", false);
        assertEquals("Nia", diskName.asText());
        assertEquals(
                "Ava",
                FileAndDataParsing.buildJsonFromPathUnderRoot("data", "files/customerPayload.customer.name", false).asText()
        );
        assertNull(FileAndDataParsing.buildJsonFromPathUnderRoot(
                "wd-file:" + fixture.work.resolve("missing-data"),
                "files/customerPayload.customer.name",
                false
        ));
        String diskRoot = "wd-file:" + fixture.work.resolve("data").toString().replace('\\', '/');
        assertEquals(
                "Disk",
                FileAndDataParsing.buildJsonFromPathUnderRoot(diskRoot, "files/customerPayload.customer.name", false).asText()
        );
    }

    private void assertClasspath(Fixture fixture, String configured, Path directory, String marker) {
        String selected = selected(configured);
        ResourceLocations.Location location = located(configured, fixture.work);
        assertEquals(PickleballPaths.Kind.CLASSPATH, location.kind(), String.valueOf(configured));
        Path root = ResourceLocations.existingDirectory(selected, fixture.work);
        assertEquals(directory.toAbsolutePath().normalize(), root, String.valueOf(configured));
        assertEquals(marker, marker(ParsingMap.loadConfigs(selected, fixture.work)), String.valueOf(configured));
        assertFalse(root.startsWith(fixture.project), String.valueOf(configured));
    }

    private void assertFilesystem(Fixture fixture, String configured, Path directory, String marker) {
        ResourceLocations.Location location = located(configured, fixture.work);
        assertEquals(PickleballPaths.Kind.FILE_SYSTEM, location.kind(), configured);
        assertEquals(directory.toAbsolutePath().normalize(), location.file(), configured);
        assertEquals(
                directory.toAbsolutePath().normalize(),
                ResourceLocations.existingDirectory(selected(configured), fixture.work)
        );
        assertEquals(marker, marker(ParsingMap.loadConfigs(configured, fixture.work)), configured);
    }

    private static ResourceLocations.Location located(String configured, Path work) {
        return ResourceLocations.locate(selected(configured), work);
    }

    private static String selected(String configured) {
        if (configured == null || configured.isBlank()) return "configs";
        return configured.trim();
    }

    private static String marker(JsonNode node) {
        return node.path("app").path("marker").asText();
    }

    private void useLoader(Path root) throws Exception {
        useLoader(new URLClassLoader(new URL[] {root.toUri().toURL()}, null));
    }

    private void useLoader(ClassLoader loader) {
        if (!loaderCaptured) {
            previousLoader = Thread.currentThread().getContextClassLoader();
            loaderCaptured = true;
        }
        Thread.currentThread().setContextClassLoader(loader);
    }

    private Fixture fixture() throws Exception {
        Path project = temp.resolve("project");
        Path classpathRoot = temp.resolve("classpath");
        Path work = temp.resolve("work");
        write(project.resolve("configs/app.yaml"), "project-root");
        write(project.resolve("src/test/resources/configs/app.yaml"), "project-test-resources");
        write(project.resolve("src/test/resources/qa/configs/app.yaml"), "project-qa");
        write(classpathRoot.resolve("configs/app.yaml"), "classpath-configs");
        write(classpathRoot.resolve("qa/configs/app.yaml"), "qa");
        write(classpathRoot.resolve("src/test/resources/qa/configs/app.yaml"), "slash-resource");
        write(classpathRoot.resolve("opt/x/configs/app.yaml"), "opt-classpath");
        write(classpathRoot.resolve("files/customers.yaml"), null);
        Files.writeString(classpathRoot.resolve("files/customers.yaml"), """
                - name: Ava
                  city: Phoenix
                """, StandardCharsets.UTF_8);
        write(classpathRoot.resolve("data/files/customerPayload.yaml"), null);
        Files.writeString(classpathRoot.resolve("data/files/customerPayload.yaml"), """
                customer:
                  name: Ava
                """, StandardCharsets.UTF_8);
        write(work.resolve("configs/app.yaml"), "from-work");
        Files.createDirectories(work.resolve("files"));
        Files.writeString(work.resolve("files/customers.yaml"), """
                - name: Nia
                  city: Tempe
                """, StandardCharsets.UTF_8);
        write(work.resolve("data/files/customerPayload.yaml"), null);
        Files.writeString(work.resolve("data/files/customerPayload.yaml"), """
                customer:
                  name: Disk
                """, StandardCharsets.UTF_8);
        return new Fixture(project, classpathRoot, work);
    }

    private static void write(Path file, String marker) throws Exception {
        Files.createDirectories(file.getParent());
        if (marker == null) {
            return;
        }
        Files.writeString(file, "marker: " + marker + "\n", StandardCharsets.UTF_8);
    }

    private record Fixture(Path project, Path classpathRoot, Path work) {
    }
}
