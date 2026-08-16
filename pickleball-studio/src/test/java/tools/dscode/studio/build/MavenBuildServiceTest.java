package tools.dscode.studio.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.studio.process.WorkspaceProcessService;
import tools.dscode.studio.workspace.WorkspaceInfo;
import tools.dscode.studio.workspace.WorkspaceService;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenBuildServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsJvmClasspathWildcardWithoutTreatingWildcardAsAPath() {
        Path home = tempDir.resolve("maven");
        Path lib = home.resolve("lib");
        MavenRuntime runtime = new MavenRuntime(home, lib);

        List<String> command = MavenBuildService.command(runtime, tempDir, List.of("--version"));

        int classpathIndex = command.indexOf("-classpath");
        assertTrue(classpathIndex >= 0);
        assertEquals(lib + File.separator + "*", command.get(classpathIndex + 1));
    }

    @Test
    void runsBundledMavenWithoutHostMaven() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>studio-maven-test</artifactId>
                  <version>1.0.0</version>
                </project>
                """);

        WorkspaceInfo workspace = new WorkspaceService().open(tempDir);
        MavenRunResult result = new MavenBuildService(
                workspace,
                new WorkspaceProcessService(workspace),
                new MavenToolchainService(tempDir.resolve("tool-cache"))
        ).run(List.of("--version"), 30);

        assertEquals(MavenToolchainService.MAVEN_VERSION, result.mavenVersion());
        assertEquals(0, result.process().exitCode(), result.process().stderr());
        assertFalse(result.process().timedOut());
        assertTrue(
                result.process().stdout().contains("Apache Maven " + MavenToolchainService.MAVEN_VERSION)
                        || result.process().stderr().contains("Apache Maven " + MavenToolchainService.MAVEN_VERSION),
                result.process().stdout() + result.process().stderr()
        );
    }
}
