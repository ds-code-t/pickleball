package tools.dscode.studio.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenToolchainServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsBundledMavenRuntime() {
        MavenRuntime runtime = new MavenToolchainService(tempDir).prepare();

        assertTrue(Files.isDirectory(runtime.libDirectory()));
        assertTrue(Files.isRegularFile(
                runtime.libDirectory().resolve("maven-embedder-" + MavenToolchainService.MAVEN_VERSION + ".jar")
        ));
    }
}
