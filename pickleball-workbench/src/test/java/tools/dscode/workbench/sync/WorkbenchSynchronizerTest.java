package tools.dscode.workbench.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    @Test
    void mavenFullArgsSkipTestsAndNeverInvokeSurefire() {
        WorkbenchProject project = new WorkbenchProject(
                tempDir.resolve("consumer").toAbsolutePath().normalize(),
                WorkbenchProject.Type.MAVEN,
                tempDir.resolve("consumer").toAbsolutePath().normalize(),
                Path.of("mvn")
        );
        List<String> full = WorkbenchSynchronizer.mavenFullArgs(
                project, tempDir.resolve("cp.txt"), tempDir.resolve("pom.xml")
        );
        List<String> resources = WorkbenchSynchronizer.mavenResourceArgs(project);
        assertTrue(full.contains("-DskipTests"));
        assertTrue(full.contains("test-compile"));
        assertFalse(full.contains("test"));
        assertTrue(resources.contains("-DskipTests"));
        assertTrue(resources.contains("process-test-resources"));
        assertFalse(resources.contains("test-compile"));
        assertTrue(WorkbenchSynchronizer.gradleResourceArgs().contains("processTestResources"));
        assertFalse(WorkbenchSynchronizer.gradleResourceArgs().contains("testClasses"));
    }

    @Test
    void skipsWrapperWhenJavaResourcesAndBuildAreUnchanged() throws Exception {
        MavenFixture fixture = MavenFixture.create(tempDir.resolve("skip-consumer"));
        RecordingRunner runner = fixture.runner();
        WorkbenchSynchronizer synchronizer = new WorkbenchSynchronizer(runner);

        WorkbenchManifest first = synchronizer.sync(fixture.projectRoot);
        assertEquals(WorkbenchSyncMode.FULL.name(), first.syncMode());
        assertEquals(1, runner.invocations.size());
        assertTrue(runner.invocations.getFirst().contains("test-compile"));
        assertTrue(first.hasInputFingerprints());

        String liveMarker = Files.readString(Path.of(first.liveOutput()).resolve("Runner.class"));
        WorkbenchManifest second = synchronizer.sync(fixture.projectRoot);
        assertEquals(WorkbenchSyncMode.SKIPPED.name(), second.syncMode());
        assertEquals(1, runner.invocations.size());
        assertEquals(first.fingerprint(), second.fingerprint());
        assertEquals(liveMarker, Files.readString(Path.of(second.liveOutput()).resolve("Runner.class")));
    }

    @Test
    void refreshesResourcesWithoutTestCompileWhenOnlyFeaturesChanged() throws Exception {
        MavenFixture fixture = MavenFixture.create(tempDir.resolve("resource-consumer"));
        RecordingRunner runner = fixture.runner();
        WorkbenchSynchronizer synchronizer = new WorkbenchSynchronizer(runner);
        WorkbenchManifest first = synchronizer.sync(fixture.projectRoot);
        assertEquals(WorkbenchSyncMode.FULL.name(), first.syncMode());

        Files.writeString(
                fixture.projectRoot.resolve("src/test/resources/features/demo.feature"),
                "Feature: changed\n",
                StandardCharsets.UTF_8
        );
        WorkbenchManifest second = synchronizer.sync(fixture.projectRoot);
        assertEquals(WorkbenchSyncMode.RESOURCES_ONLY.name(), second.syncMode());
        assertEquals(2, runner.invocations.size());
        assertTrue(runner.invocations.get(1).contains("process-test-resources"));
        assertFalse(runner.invocations.get(1).contains("test-compile"));
        assertEquals(
                "Feature: changed\n",
                Files.readString(Path.of(second.liveOutput()).resolve("features/demo.feature"))
        );
        assertTrue(Files.exists(Path.of(second.liveOutput()).resolve("Runner.class")));
    }

    @Test
    void runsFullCompileWhenJavaSourceChanges() throws Exception {
        MavenFixture fixture = MavenFixture.create(tempDir.resolve("java-consumer"));
        RecordingRunner runner = fixture.runner();
        WorkbenchSynchronizer synchronizer = new WorkbenchSynchronizer(runner);
        synchronizer.sync(fixture.projectRoot);

        Files.writeString(
                fixture.projectRoot.resolve("src/test/java/Runner.java"),
                "class Runner { int n = 2; }\n",
                StandardCharsets.UTF_8
        );
        WorkbenchManifest second = synchronizer.sync(fixture.projectRoot);
        assertEquals(WorkbenchSyncMode.FULL.name(), second.syncMode());
        assertEquals(2, runner.invocations.size());
        assertTrue(runner.invocations.get(1).contains("test-compile"));
    }

    private static final class RecordingRunner implements WorkbenchSynchronizer.CommandRunner {
        private final List<List<String>> invocations = new ArrayList<>();
        private final MavenFixture fixture;

        private RecordingRunner(MavenFixture fixture) {
            this.fixture = fixture;
        }

        @Override
        public String run(WorkbenchProject project, List<String> args, Path log) {
            invocations.add(List.copyOf(args));
            try {
                if (args.contains("test-compile")) {
                    fixture.writeCompileOutputs(args);
                } else if (args.contains("process-test-resources")) {
                    fixture.writeResourceOutputs();
                }
            } catch (Exception failure) {
                throw new IllegalStateException("Fake Maven runner failed.", failure);
            }
            return "";
        }
    }

    private static final class MavenFixture {
        private final Path projectRoot;
        private final Path dependency;
        private final RecordingRunner runner = new RecordingRunner(this);

        private MavenFixture(Path projectRoot, Path dependency) {
            this.projectRoot = projectRoot;
            this.dependency = dependency;
        }

        static MavenFixture create(Path projectRoot) throws Exception {
            Files.createDirectories(projectRoot.resolve("src/test/java"));
            Files.createDirectories(projectRoot.resolve("src/test/resources/features"));
            Files.writeString(projectRoot.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
            Files.writeString(
                    projectRoot.resolve("src/test/java/Runner.java"),
                    "class Runner {}\n",
                    StandardCharsets.UTF_8
            );
            Files.writeString(
                    projectRoot.resolve("src/test/resources/features/demo.feature"),
                    "Feature: demo\n",
                    StandardCharsets.UTF_8
            );
            Path dependency = projectRoot.resolve("dep.jar");
            Files.write(dependency, new byte[]{1, 2, 3});
            return new MavenFixture(projectRoot.toAbsolutePath().normalize(), dependency);
        }

        RecordingRunner runner() {
            return runner;
        }

        void writeCompileOutputs(List<String> args) throws Exception {
            Path main = projectRoot.resolve("target/classes");
            Path test = projectRoot.resolve("target/test-classes");
            Files.createDirectories(main);
            Files.createDirectories(test.resolve("features"));
            Files.writeString(test.resolve("Runner.class"), "class-bytes", StandardCharsets.UTF_8);
            Files.writeString(
                    test.resolve("features/demo.feature"),
                    Files.readString(projectRoot.resolve("src/test/resources/features/demo.feature")),
                    StandardCharsets.UTF_8
            );
            Path classpath = outputFile(args, "-Dmdep.outputFile=");
            Path effectivePom = outputFile(args, "-Doutput=");
            Files.writeString(classpath, dependency.toString(), StandardCharsets.UTF_8);
            Files.writeString(effectivePom, """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project>
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
                    projectRoot.resolve("src/main/java"),
                    projectRoot.resolve("src/test/java"),
                    projectRoot.resolve("src/main/resources"),
                    projectRoot.resolve("src/test/resources"),
                    main.toAbsolutePath(),
                    test.toAbsolutePath()
            ), StandardCharsets.UTF_8);
        }

        void writeResourceOutputs() throws Exception {
            Path test = projectRoot.resolve("target/test-classes");
            Files.createDirectories(test.resolve("features"));
            Files.writeString(
                    test.resolve("features/demo.feature"),
                    Files.readString(projectRoot.resolve("src/test/resources/features/demo.feature")),
                    StandardCharsets.UTF_8
            );
        }

        private static Path outputFile(List<String> args, String prefix) {
            return args.stream()
                    .filter(arg -> arg.startsWith(prefix))
                    .map(arg -> Path.of(arg.substring(prefix.length())))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
