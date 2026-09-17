package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PickleballArtifactLocatorTest {
    @TempDir
    Path tempDir;

    @Test
    void findsExactMavenJarAndPrefersLatestReleaseOverSnapshot() throws Exception {
        Path m2 = tempDir.resolve("m2");
        Path gradle = tempDir.resolve("gradle");
        writeMaven(m2, "2.1.10");
        writeMaven(m2, "2.1.11");
        writeMaven(m2, "2.1.12-SNAPSHOT");
        PickleballArtifactLocator.Repositories repos = new PickleballArtifactLocator.Repositories(m2, gradle);

        Path found = PickleballArtifactLocator.find("2.1.10", repos).orElseThrow();
        assertEquals(PickleballArtifactLocator.mavenJar(m2, "2.1.10"), found);

        Optional<PickleballArtifactLocator.Located> latest = PickleballArtifactLocator.findLatest(repos);
        assertTrue(latest.isPresent());
        assertEquals("2.1.11", latest.get().version());
    }

    @Test
    void latestFallsBackToSnapshotWhenNoReleaseExists() throws Exception {
        Path m2 = tempDir.resolve("m2");
        writeMaven(m2, "2.1.11-SNAPSHOT");
        writeMaven(m2, "2.1.10-SNAPSHOT");
        Optional<PickleballArtifactLocator.Located> latest = PickleballArtifactLocator.findLatest(
                new PickleballArtifactLocator.Repositories(m2, tempDir.resolve("gradle"))
        );
        assertEquals("2.1.11-SNAPSHOT", latest.orElseThrow().version());
    }

    private static void writeMaven(Path m2, String version) throws Exception {
        Path jar = PickleballArtifactLocator.mavenJar(m2, version);
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, version);
    }
}
