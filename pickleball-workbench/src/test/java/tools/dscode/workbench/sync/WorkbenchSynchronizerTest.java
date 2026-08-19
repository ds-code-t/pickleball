package tools.dscode.workbench.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchSynchronizerTest {

    @TempDir
    Path tempDir;

    @Test
    void materializesOneLiveOutputWithTestOverMainPrecedence() throws Exception {
        Path projectRoot = tempDir.resolve("consumer");
        Path main = projectRoot.resolve("target/classes");
        Path test = projectRoot.resolve("target/test-classes");
        Files.createDirectories(main.resolve("features"));
        Files.createDirectories(test.resolve("features"));
        Files.writeString(main.resolve("features/example.feature"), "main", StandardCharsets.UTF_8);
        Files.writeString(test.resolve("features/example.feature"), "test", StandardCharsets.UTF_8);
        Files.writeString(main.resolve("main.txt"), "main", StandardCharsets.UTF_8);
        Files.writeString(test.resolve("test.txt"), "test", StandardCharsets.UTF_8);

        Path dependency = tempDir.resolve("dependency.jar");
        Files.write(dependency, new byte[]{1, 2, 3});

        WorkbenchProject project = new WorkbenchProject(
                projectRoot.toAbsolutePath().normalize(),
                WorkbenchProject.Type.MAVEN,
                projectRoot.toAbsolutePath().normalize(),
                Path.of("mvn")
        );
        Path stateRoot = WorkbenchManifest.workbenchRoot(projectRoot);
        Path staging = stateRoot.resolve(".sync-test");
        Files.createDirectories(staging);

        WorkbenchSynchronizer.SyncMetadata metadata = new WorkbenchSynchronizer.SyncMetadata(
                List.of(projectRoot.resolve("src/main/java"), projectRoot.resolve("src/test/java")),
                List.of(
                        new WorkbenchSynchronizer.OutputPath("MAIN", main),
                        new WorkbenchSynchronizer.OutputPath("TEST", test)
                ),
                List.of(dependency.toString())
        );

        WorkbenchManifest manifest = WorkbenchSynchronizer.materialize(
                project, metadata, staging, stateRoot
        );

        Path base = stateRoot.resolve("base/classes");
        Path live = stateRoot.resolve("live/classes");
        assertEquals("test", Files.readString(base.resolve("features/example.feature")));
        assertEquals("test", Files.readString(live.resolve("features/example.feature")));
        assertTrue(Files.exists(live.resolve("main.txt")));
        assertTrue(Files.exists(live.resolve("test.txt")));

        Files.writeString(live.resolve("features/example.feature"), "live-edit", StandardCharsets.UTF_8);
        assertEquals("live-edit", Files.readString(live.resolve("features/example.feature")));
        assertEquals("test", Files.readString(base.resolve("features/example.feature")));

        List<String> classpath = WorkbenchSynchronizer.readWorkerClasspath(projectRoot);
        assertEquals(live.toAbsolutePath().normalize().toString(), classpath.getFirst());
        assertEquals(dependency.toAbsolutePath().normalize().toString(), classpath.get(1));
        Path baseRoot = stateRoot.resolve("base").toAbsolutePath().normalize();
        assertFalse(classpath.stream()
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .anyMatch(path -> path.startsWith(baseRoot)));
        assertEquals(live.toAbsolutePath().normalize(), manifest.liveOutputPath());
        assertEquals(2, manifest.outputMappings().size());
        assertTrue(manifest.outputMappings().stream().allMatch(mapping ->
                Path.of(mapping.liveOutput()).toAbsolutePath().normalize().equals(live.toAbsolutePath().normalize())
        ));
        assertFalse(manifest.fingerprint().isBlank());
    }

    @Test
    void fingerprintChangesWhenDependencyContentsChangeAtSamePath() throws Exception {
        Path projectRoot = tempDir.resolve("fingerprint-consumer");
        Path test = projectRoot.resolve("target/test-classes");
        Files.createDirectories(test);
        Files.writeString(test.resolve("runner.class"), "runner", StandardCharsets.UTF_8);

        Path dependency = tempDir.resolve("same-version.jar");
        Files.write(dependency, new byte[]{1, 2, 3});

        WorkbenchProject project = new WorkbenchProject(
                projectRoot.toAbsolutePath().normalize(),
                WorkbenchProject.Type.MAVEN,
                projectRoot.toAbsolutePath().normalize(),
                Path.of("mvn")
        );
        WorkbenchSynchronizer.SyncMetadata metadata = new WorkbenchSynchronizer.SyncMetadata(
                List.of(projectRoot.resolve("src/test/java")),
                List.of(new WorkbenchSynchronizer.OutputPath("TEST", test)),
                List.of(dependency.toString())
        );
        Path stateRoot = WorkbenchManifest.workbenchRoot(projectRoot);

        Path firstStaging = stateRoot.resolve(".sync-first");
        Files.createDirectories(firstStaging);
        String first = WorkbenchSynchronizer.materialize(
                project, metadata, firstStaging, stateRoot
        ).fingerprint();

        Files.write(dependency, new byte[]{4, 5, 6});
        Path secondStaging = stateRoot.resolve(".sync-second");
        Files.createDirectories(secondStaging);
        String second = WorkbenchSynchronizer.materialize(
                project, metadata, secondStaging, stateRoot
        ).fingerprint();

        assertFalse(first.equals(second));
    }

    @Test
    void readsMavenEffectivePomSourceAndOutputRoots() throws Exception {
        Path project = tempDir.resolve("effective-pom-consumer").toAbsolutePath().normalize();
        Files.createDirectories(project);
        Path effectivePom = tempDir.resolve("effective-pom.xml");
        Files.writeString(effectivePom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <build>
                    <sourceDirectory>%s</sourceDirectory>
                    <testSourceDirectory>%s</testSourceDirectory>
                    <resources><resource><directory>%s</directory></resource></resources>
                    <testResources><testResource><directory>%s</directory></testResource></testResources>
                    <outputDirectory>%s</outputDirectory>
                    <testOutputDirectory>%s</testOutputDirectory>
                  </build>
                </project>
                """.formatted(
                project.resolve("src/main/java"),
                project.resolve("src/test/java"),
                project.resolve("src/main/resources"),
                project.resolve("src/test/resources"),
                project.resolve("target/classes"),
                project.resolve("target/test-classes")
        ));

        WorkbenchSynchronizer.MavenMetadata metadata =
                WorkbenchSynchronizer.parseEffectivePom(project, effectivePom);

        assertEquals(4, metadata.sourceRoots().size());
        assertEquals(project.resolve("target/classes"), metadata.outputs().get(0).path());
        assertEquals(project.resolve("target/test-classes"), metadata.outputs().get(1).path());
    }

    @Test
    void detectsMavenAndGradleSelectedProjects() throws Exception {
        Path maven = tempDir.resolve("maven");
        Files.createDirectories(maven);
        Files.writeString(maven.resolve("pom.xml"), "<project/>");
        assertEquals(WorkbenchProject.Type.MAVEN, WorkbenchProject.locate(maven).type());

        Path gradle = tempDir.resolve("gradle");
        Files.createDirectories(gradle);
        Files.writeString(gradle.resolve("build.gradle"), "plugins { id 'java' }");
        assertEquals(WorkbenchProject.Type.GRADLE, WorkbenchProject.locate(gradle).type());
    }
}
