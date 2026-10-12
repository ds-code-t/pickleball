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
        ResourceLocations.Location located = located(fixture, "wd-file:/opt/x/configs");
        assertEquals(PickleballPaths.Kind.FILE_SYSTEM, located.kind());
        assertEquals(absolute, located.file());
        assertEquals(absolute, RunConfigCopy.locate(fixture.project, "wd-file:/opt/x/configs", fixture.work).file());
        assertNull(RunConfigCopy.resolveRoot(fixture.project, "wd-file:/opt/x/configs", fixture.work));
        assertNull(ParsingMap.loadConfigs("wd-file:/opt/x/configs", fixture.work));
        assertNull(ResourceLocations.existingDirectory("wd-file:missing-configs", fixture.work));
        assertNull(ParsingMap.loadConfigs("wd-file:missing-configs", fixture.work));
    }

    @Test
    void classpathWinsWhenTheProjectAlsoHasRootAndTestResourceConfigs() throws Exception {
        Fixture fixture = fixture();
        useLoader(fixture.classpathRoot);

        Path resolved = RunConfigCopy.resolveRoot(fixture.project, "configs", fixture.work);
        assertEquals(fixture.classpathRoot.resolve("configs").toAbsolutePath().normalize(), resolved);
        assertFalse(resolved.startsWith(fixture.project));
        assertEquals("classpath-configs", marker(ParsingMap.loadConfigs("configs", fixture.work)));

        Path fromWdFile = RunConfigCopy.resolveRoot(fixture.project, "wd-file:configs", fixture.project);
        assertEquals(fixture.project.resolve("configs").toAbsolutePath().normalize(), fromWdFile);
        assertEquals("project-root", marker(ParsingMap.loadConfigs("wd-file:" + fixture.project.resolve("configs"), fixture.work)));
    }

    @Test
    void aMissingFolderDoesNotThrowOrCopyTheProjectDecoy() throws Exception {
        Fixture fixture = fixture();
        useLoader(fixture.classpathRoot);
        Path destination = temp.resolve("run-config");
        RunConfigCopy.copyIfAbsent(fixture.project, destination, "wd-file:missing-configs", fixture.work);
        assertFalse(Files.exists(destination.resolve("app.yaml")));
        assertNull(ParsingMap.loadConfigs("wd-file:missing-configs", fixture.work));
    }

    @Test
    void jarOnlyConfigsCopyAndLoad() throws Exception {
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
            Path destination = temp.resolve("jar-copy");
            RunConfigCopy.copyIfAbsent(fixture.project, destination, "configs", fixture.work);
            assertEquals("marker: from-jar\n", Files.readString(destination.resolve("app.yaml")));
            assertFalse(Files.readString(destination.resolve("app.yaml")).contains("project-root"));
            assertEquals("from-jar", marker(ParsingMap.loadConfigs("configs", fixture.work)));
            assertEquals("from-jar", marker(ParsingMap.loadConfigs("file:configs", fixture.work)));
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
        ResourceLocations.Location location = located(fixture, configured);
        assertEquals(PickleballPaths.Kind.CLASSPATH, location.kind(), String.valueOf(configured));
        Path root = RunConfigCopy.resolveRoot(fixture.project, configured, fixture.work);
        assertEquals(directory.toAbsolutePath().normalize(), root, String.valueOf(configured));
        assertEquals(root, ResourceLocations.existingDirectory(selected(fixture, configured), fixture.work));
        assertEquals(marker, marker(ParsingMap.loadConfigs(selected(fixture, configured), fixture.work)), String.valueOf(configured));
        assertFalse(root.startsWith(fixture.project), String.valueOf(configured));
    }

    private void assertFilesystem(Fixture fixture, String configured, Path directory, String marker) {
        ResourceLocations.Location location = located(fixture, configured);
        assertEquals(PickleballPaths.Kind.FILE_SYSTEM, location.kind(), configured);
        assertEquals(directory.toAbsolutePath().normalize(), location.file(), configured);
        assertEquals(directory.toAbsolutePath().normalize(), RunConfigCopy.resolveRoot(fixture.project, configured, fixture.work));
        assertEquals(marker, marker(ParsingMap.loadConfigs(configured, fixture.work)), configured);
    }

    private ResourceLocations.Location located(Fixture fixture, String configured) {
        ResourceLocations.Location direct = ResourceLocations.locate(selected(fixture, configured), fixture.work);
        ResourceLocations.Location copied = RunConfigCopy.locate(fixture.project, configured, fixture.work);
        assertEquals(direct.kind(), copied.kind(), String.valueOf(configured));
        assertEquals(direct.classpathResource(), copied.classpathResource(), String.valueOf(configured));
        assertEquals(direct.file(), copied.file(), String.valueOf(configured));
        return direct;
    }

    private static String selected(Fixture fixture, String configured) {
        return RunConfigCopy.selectSource(fixture.project, configured);
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
