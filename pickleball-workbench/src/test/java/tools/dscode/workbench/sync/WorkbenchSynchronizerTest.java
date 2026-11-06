package tools.dscode.workbench.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.dscode.control.protocol.speed.ClasspathCache;
import tools.dscode.control.protocol.speed.InputFingerprint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        Path liveFeature = live.resolve("features/example.feature");
        Path baseFeature = base.resolve("features/example.feature");
        if (Files.isSameFile(baseFeature, liveFeature)) {
            Path generated = stateRoot.resolve("live/generated-classes/extra.txt");
            Files.createDirectories(generated.getParent());
            Files.writeString(generated, "live-edit", StandardCharsets.UTF_8);
            assertFalse(Files.exists(stateRoot.resolve("base/generated-classes/extra.txt")));
            assertEquals("test", Files.readString(baseFeature));
        } else {
            Files.writeString(liveFeature, "live-edit", StandardCharsets.UTF_8);
            assertEquals("live-edit", Files.readString(liveFeature));
            assertEquals("test", Files.readString(baseFeature));
        }

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
    void sameMtimeRewriteChangesFingerprintAndCountsTheHash() throws Exception {
        Path projectRoot = tempDir.resolve("same-mtime-consumer");
        Path test = projectRoot.resolve("target/test-classes");
        Files.createDirectories(test);
        Files.writeString(test.resolve("runner.class"), "runner", StandardCharsets.UTF_8);
        Path dependency = tempDir.resolve("same-mtime.jar");
        Files.write(dependency, new byte[]{1, 2, 3});
        FileTime stamp = FileTime.fromMillis(System.currentTimeMillis() / 1000L * 1000L);
        Files.setLastModifiedTime(dependency, stamp);

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
        String[] firstErr = new String[1];
        String first = capture(firstErr, () -> WorkbenchSynchronizer.materialize(
                project, metadata, stateRoot.resolve(".sync-first"), stateRoot
        ).fingerprint());
        Files.write(dependency, new byte[]{4, 5, 6});
        Files.setLastModifiedTime(dependency, stamp);
        String[] secondErr = new String[1];
        String second = capture(secondErr, () -> WorkbenchSynchronizer.materialize(
                project, metadata, stateRoot.resolve(".sync-second"), stateRoot
        ).fingerprint());
        assertFalse(first.equals(second), firstErr[0] + "\n" + secondErr[0]);
        assertTrue(firstErr[0].contains("hashed=1"), firstErr[0]);
        assertTrue(secondErr[0].contains("hashed=1"), secondErr[0]);
    }

    @Test
    void freshJarKeepsFingerprintAndStopsHashingAfterTheRacyWindow() throws Exception {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        WorkbenchSynchronizer.useClock(clock::get);
        try {
            Path projectRoot = tempDir.resolve("fresh-jar-consumer");
            Path test = projectRoot.resolve("target/test-classes");
            Files.createDirectories(test);
            Files.writeString(test.resolve("runner.class"), "runner", StandardCharsets.UTF_8);
            Path dependency = tempDir.resolve("fresh.jar");
            Files.write(dependency, new byte[]{1, 2, 3});
            Files.setLastModifiedTime(dependency, FileTime.fromMillis(clock.get()));

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
            String[] firstErr = new String[1];
            String first = capture(firstErr, () -> WorkbenchSynchronizer.materialize(
                    project, metadata, stateRoot.resolve(".sync-first"), stateRoot
            ).fingerprint());
            String tokenAtRecord = cachedJarToken(stateRoot);
            clock.addAndGet(500L);
            String[] earlyErr = new String[1];
            String early = capture(earlyErr, () -> WorkbenchSynchronizer.materialize(
                    project, metadata, stateRoot.resolve(".sync-early"), stateRoot
            ).fingerprint());
            clock.addAndGet(2_001L);
            String[] secondErr = new String[1];
            String second = capture(secondErr, () -> WorkbenchSynchronizer.materialize(
                    project, metadata, stateRoot.resolve(".sync-second"), stateRoot
            ).fingerprint());
            clock.addAndGet(2_001L);
            String[] thirdErr = new String[1];
            String third = capture(thirdErr, () -> WorkbenchSynchronizer.materialize(
                    project, metadata, stateRoot.resolve(".sync-third"), stateRoot
            ).fingerprint());

            assertEquals(first, early, firstErr[0] + "\n" + earlyErr[0]);
            assertEquals(first, second, firstErr[0] + "\n" + secondErr[0]);
            assertEquals(second, third, secondErr[0] + "\n" + thirdErr[0]);
            assertTrue(firstErr[0].contains("hashed=1"), firstErr[0]);
            assertTrue(secondErr[0].contains("hashed=0"), secondErr[0]);
            assertTrue(thirdErr[0].contains("hashed=0"), thirdErr[0]);
            assertEquals(tokenAtRecord, cachedJarToken(stateRoot), thirdErr[0]);
            assertTrue(cachedJarRecordedAt(stateRoot) - 1_700_000_000_000L > 2_000L, thirdErr[0]);
        } finally {
            WorkbenchSynchronizer.useClock(null);
        }
    }

    private static String cachedJarToken(Path stateRoot) throws IOException {
        String row = cachedJarRow(stateRoot);
        int first = row.indexOf('\t');
        int last = row.lastIndexOf('\t');
        return row.substring(first + 1, last);
    }

    private static long cachedJarRecordedAt(Path stateRoot) throws IOException {
        String row = cachedJarRow(stateRoot);
        return Long.parseLong(row.substring(row.lastIndexOf('\t') + 1).trim());
    }

    private static String cachedJarRow(Path stateRoot) throws IOException {
        List<String> lines = Files.readAllLines(stateRoot.resolve("jar-hashes.v2"), StandardCharsets.UTF_8);
        assertEquals("v2", lines.get(0).trim());
        assertEquals(2, lines.size(), lines.toString());
        return lines.get(1);
    }

    private static String capture(String[] err, java.util.concurrent.Callable<String> call) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            return call.call();
        } finally {
            System.setErr(previous);
            err[0] = buffer.toString(StandardCharsets.UTF_8);
        }
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
        Path mvnw = maven.resolve(WorkbenchProject.isWindows() ? "mvnw.cmd" : "mvnw");
        Files.writeString(mvnw, WorkbenchProject.isWindows() ? "@echo off\r\n" : "#!/bin/sh\n");
        assertEquals(WorkbenchProject.Type.MAVEN, WorkbenchProject.locate(maven).type());
        assertEquals(mvnw, WorkbenchProject.locate(maven).launcher());

        Path gradle = tempDir.resolve("gradle");
        Files.createDirectories(gradle);
        Files.writeString(gradle.resolve("build.gradle"), "plugins { id 'java' }");
        Path gradlew = gradle.resolve(WorkbenchProject.isWindows() ? "gradlew.bat" : "gradlew");
        Files.writeString(gradlew, WorkbenchProject.isWindows() ? "@echo off\r\n" : "#!/bin/sh\n");
        assertEquals(WorkbenchProject.Type.GRADLE, WorkbenchProject.locate(gradle).type());
        assertEquals(gradlew, WorkbenchProject.locate(gradle).launcher());
    }

    @Test
    void wrapperJarIsUsedWhenTheScriptIsAbsent() throws Exception {
        Path maven = tempDir.resolve("jar-maven");
        Files.createDirectories(maven.resolve(".mvn").resolve("wrapper"));
        Files.writeString(maven.resolve("pom.xml"), "<project/>");
        Path jar = maven.resolve(".mvn").resolve("wrapper").resolve("maven-wrapper.jar");
        Files.writeString(jar, "jar");
        assertEquals(jar, WorkbenchProject.locate(maven).launcher());
        Path module = maven.resolve("module");
        Files.createDirectories(module);
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        assertEquals(jar, WorkbenchProject.locate(module).launcher());
        List<String> command = WorkbenchSynchronizer.executableCommand(jar, List.of("test"));
        assertEquals("-jar", command.get(1));
        assertEquals(jar.toString(), command.get(2));
        assertEquals("test", command.get(3));

        Path gradle = tempDir.resolve("jar-gradle");
        Path gradleModule = gradle.resolve("module");
        Files.createDirectories(gradleModule);
        Files.writeString(gradleModule.resolve("build.gradle"), "plugins { id 'java' }\n");
        Path gradleJar = gradle.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.jar");
        Files.createDirectories(gradleJar.getParent());
        Files.writeString(gradleJar, "jar");
        assertEquals(gradleJar, WorkbenchProject.locate(gradleModule).launcher());
        List<String> gradleCommand = WorkbenchSynchronizer.executableCommand(gradleJar, List.of("test"));
        assertEquals("-jar", gradleCommand.get(1));
        assertEquals(gradleJar.toString(), gradleCommand.get(2));
    }

    @Test
    void missingWrapperNamesTheProjectScript() throws Exception {
        Path maven = tempDir.resolve("bare-maven");
        Files.createDirectories(maven);
        Files.writeString(maven.resolve("pom.xml"), "<project/>");
        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchProject.locate(maven)
        );
        assertTrue(failure.getMessage().contains("mvnw"));
        assertTrue(failure.getMessage().contains("machine-wide"));
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
    void skipRebuildsAWorkerClasspathThatDoesNotStartWithLive() throws Exception {
        MavenFixture fixture = MavenFixture.create(tempDir.resolve("order-consumer"));
        RecordingRunner runner = fixture.runner();
        WorkbenchSynchronizer synchronizer = new WorkbenchSynchronizer(runner);
        WorkbenchManifest first = synchronizer.sync(fixture.projectRoot);
        Path live = Path.of(first.liveOutput()).toAbsolutePath().normalize();
        Path classpath = WorkbenchManifest.workbenchRoot(fixture.projectRoot).resolve("classpath.txt");
        assertEquals(live.toString(), WorkbenchSynchronizer.readWorkerClasspath(fixture.projectRoot).getFirst());

        Path testClasses = fixture.projectRoot.resolve("target/test-classes").toAbsolutePath().normalize();
        Files.writeString(classpath, testClasses + "\n" + fixture.dependency + "\n", StandardCharsets.UTF_8);
        IllegalStateException rejected = assertThrows(
                IllegalStateException.class,
                () -> WorkbenchSynchronizer.readWorkerClasspath(fixture.projectRoot)
        );
        assertTrue(rejected.getMessage().contains("must start with the one effective live output"), rejected.getMessage());

        WorkbenchManifest second = synchronizer.sync(fixture.projectRoot);
        assertEquals(WorkbenchSyncMode.SKIPPED.name(), second.syncMode());
        assertEquals(1, runner.invocations.size());
        List<String> restored = WorkbenchSynchronizer.readWorkerClasspath(fixture.projectRoot);
        assertEquals(live.toString(), restored.getFirst());
        assertEquals(fixture.dependency.toAbsolutePath().normalize().toString(), restored.get(1));

        Files.delete(classpath);
        WorkbenchManifest third = synchronizer.sync(fixture.projectRoot);
        assertEquals(WorkbenchSyncMode.SKIPPED.name(), third.syncMode());
        assertEquals(1, runner.invocations.size());
        assertEquals(live.toString(), WorkbenchSynchronizer.readWorkerClasspath(fixture.projectRoot).getFirst());
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

    @Test
    void refreshesResourcesWithoutTestCompileWhenOnlyConfigChanges() throws Exception {
        MavenFixture fixture = MavenFixture.create(tempDir.resolve("config-consumer"));
        Files.createDirectories(fixture.projectRoot.resolve("src/test/resources/configs"));
        Files.writeString(
                fixture.projectRoot.resolve("src/test/resources/configs/app.yaml"),
                "url: first\n",
                StandardCharsets.UTF_8
        );
        RecordingRunner runner = fixture.runner();
        WorkbenchSynchronizer synchronizer = new WorkbenchSynchronizer(runner);
        WorkbenchManifest first = synchronizer.sync(fixture.projectRoot);
        assertEquals(WorkbenchSyncMode.FULL.name(), first.syncMode());

        Files.writeString(
                fixture.projectRoot.resolve("src/test/resources/configs/app.yaml"),
                "url: second\n",
                StandardCharsets.UTF_8
        );
        WorkbenchManifest second = synchronizer.sync(fixture.projectRoot);
        assertEquals(WorkbenchSyncMode.RESOURCES_ONLY.name(), second.syncMode());
        assertEquals(2, runner.invocations.size());
        assertTrue(runner.invocations.get(1).contains("process-test-resources"));
        assertFalse(runner.invocations.get(1).contains("test-compile"));
        assertTrue(Files.exists(Path.of(second.liveOutput()).resolve("Runner.class")));
    }

    @Test
    void refreshesResourcesWithoutTestCompileWhenOnlyDataChanges() throws Exception {
        MavenFixture fixture = MavenFixture.create(tempDir.resolve("data-consumer"));
        Files.createDirectories(fixture.projectRoot.resolve("src/test/resources/data"));
        Files.writeString(
                fixture.projectRoot.resolve("src/test/resources/data/sample.json"),
                "{\"n\":1}\n",
                StandardCharsets.UTF_8
        );
        RecordingRunner runner = fixture.runner();
        WorkbenchSynchronizer synchronizer = new WorkbenchSynchronizer(runner);
        WorkbenchManifest first = synchronizer.sync(fixture.projectRoot);
        assertEquals(WorkbenchSyncMode.FULL.name(), first.syncMode());

        Files.writeString(
                fixture.projectRoot.resolve("src/test/resources/data/sample.json"),
                "{\"n\":2}\n",
                StandardCharsets.UTF_8
        );
        WorkbenchManifest second = synchronizer.sync(fixture.projectRoot);
        assertEquals(WorkbenchSyncMode.RESOURCES_ONLY.name(), second.syncMode());
        assertEquals(2, runner.invocations.size());
        assertTrue(runner.invocations.get(1).contains("process-test-resources"));
        assertFalse(runner.invocations.get(1).contains("test-compile"));
    }

    @Test
    void escalatesResourcesOnlyToFullWhenCompiledOutputsAreMissing() throws Exception {
        MavenFixture fixture = MavenFixture.create(tempDir.resolve("cleaned-consumer"));
        RecordingRunner runner = fixture.runner();
        WorkbenchSynchronizer synchronizer = new WorkbenchSynchronizer(runner);
        WorkbenchManifest first = synchronizer.sync(fixture.projectRoot);
        assertEquals(WorkbenchSyncMode.FULL.name(), first.syncMode());

        Files.writeString(
                fixture.projectRoot.resolve("src/test/resources/features/demo.feature"),
                "Feature: cleaned\n",
                StandardCharsets.UTF_8
        );
        Path testClasses = fixture.projectRoot.resolve("target/test-classes");
        deleteTree(testClasses);
        assertFalse(Files.isDirectory(testClasses));

        WorkbenchManifest second = synchronizer.sync(fixture.projectRoot);
        assertEquals(WorkbenchSyncMode.FULL.name(), second.syncMode());
        assertEquals(2, runner.invocations.size());
        assertTrue(runner.invocations.get(1).contains("test-compile"));
        assertTrue(Files.exists(Path.of(second.liveOutput()).resolve("Runner.class")));
    }

    @Test
    void replaceDirectoryRetriesALockThenCopies() throws Exception {
        Path staged = tempDir.resolve("staged");
        Files.createDirectories(staged.resolve("nested"));
        Files.writeString(staged.resolve("nested").resolve("a.txt"), "kept");
        Path target = tempDir.resolve("installed");
        int[] attempts = {0};
        List<Long> delays = new ArrayList<>();
        WorkbenchSynchronizer.replaceDirectory(staged, target, (source, dest) -> {
            attempts[0]++;
            throw new java.io.IOException("locked by Windows");
        }, delays::add);
        assertEquals(4, attempts[0]);
        assertEquals(List.of(100L, 300L, 1000L), delays);
        assertEquals("kept", Files.readString(target.resolve("nested").resolve("a.txt")));
        assertFalse(Files.exists(staged));
        assertNoTempSiblings(target);
    }

    @Test
    void replaceDirectoryRetriesMoveWithBackoffThenSucceeds() throws Exception {
        Path staged = stagedTree("kept");
        Path target = tempDir.resolve("installed");
        int[] attempts = {0};
        List<Long> delays = new ArrayList<>();
        WorkbenchSynchronizer.replaceDirectory(staged, target, (source, dest) -> {
            attempts[0]++;
            if (attempts[0] < 4) {
                throw new IOException("locked by Windows");
            }
            Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING);
        }, delays::add);
        assertEquals(4, attempts[0]);
        assertEquals(List.of(100L, 300L, 1000L), delays);
        assertEquals("kept", Files.readString(target.resolve("nested").resolve("a.txt")));
        assertFalse(Files.exists(staged));
    }

    @Test
    void replaceDirectoryFallsBackToACompleteCopy() throws Exception {
        Path staged = stagedTree("kept");
        Files.writeString(staged.resolve("nested").resolve("b.txt"), "also");
        Path target = tempDir.resolve("installed");
        WorkbenchSynchronizer.replaceDirectory(staged, target, (source, dest) -> {
            throw new IOException("locked by Windows");
        }, millis -> {
        });
        assertEquals("kept", Files.readString(target.resolve("nested").resolve("a.txt")));
        assertEquals("also", Files.readString(target.resolve("nested").resolve("b.txt")));
        assertFalse(Files.exists(staged));
        assertNoTempSiblings(target);
    }

    @Test
    void copyFailureLeavesNoPartialTree() throws Exception {
        Path staged = stagedTree("kept");
        Path target = tempDir.resolve("installed");
        Files.createDirectories(target.resolve("nested"));
        Files.writeString(target.resolve("nested").resolve("old.txt"), "old");
        Files.writeString(target.resolve("keep.txt"), "keep");
        assertThrows(IllegalStateException.class, () -> WorkbenchSynchronizer.replaceDirectory(
                staged,
                target,
                (source, dest) -> {
                    throw new IOException("locked by Windows");
                },
                root -> deleteTree(root),
                (source, dest) -> {
                    Files.createDirectories(dest.resolve("nested"));
                    Files.writeString(dest.resolve("nested").resolve("partial.txt"), "partial");
                    throw new IOException("copy failed");
                },
                millis -> {
                }
        ));
        assertFalse(Files.exists(target.resolve("nested").resolve("partial.txt")));
        if (Files.exists(target)) {
            assertEquals("old", Files.readString(target.resolve("nested").resolve("old.txt")));
            assertEquals("keep", Files.readString(target.resolve("keep.txt")));
            assertFalse(Files.exists(target.resolve("nested").resolve("a.txt")));
        }
        assertNoTempSiblings(target);
        assertTrue(Files.exists(staged), "a failed copy must not consume the staged tree");
    }

    @Test
    void lockedDeleteTreeIsRetried() throws Exception {
        Path staged = stagedTree("kept");
        Path target = tempDir.resolve("installed");
        Files.createDirectories(target);
        Files.writeString(target.resolve("locked.txt"), "locked");
        int[] deletes = {0};
        List<Long> delays = new ArrayList<>();
        WorkbenchSynchronizer.replaceDirectory(
                staged,
                target,
                (source, dest) -> Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING),
                root -> {
                    deletes[0]++;
                    if (deletes[0] < 4) {
                        throw new IOException("locked by Windows");
                    }
                    deleteTree(root);
                },
                delays::add
        );
        assertEquals(4, deletes[0]);
        assertEquals(List.of(100L, 300L, 1000L), delays);
        assertEquals("kept", Files.readString(target.resolve("nested").resolve("a.txt")));
        assertFalse(Files.exists(target.resolve("locked.txt")));
        assertFalse(Files.exists(staged));
    }

    @Test
    void isolateSyncReusesTheDiscoverClasspathAndDoesNotCompile() throws Exception {
        Path project = consumerProject("with-jar");
        Path classes = project.resolve("target/test-classes");
        Path jar = tempDir.resolve("lib.jar");
        Files.writeString(jar, "jar");
        storeDiscoverCache(project, List.of(classes.toString(), jar.toString()));
        assertEquals(0, syncWithoutMaven(project));
        assertTrue(Files.isRegularFile(WorkbenchManifest.workbenchRoot(project)
                .resolve("live/classes/Runner.class")));
    }

    @Test
    void projectOutputAloneIsEnoughToSkipTheWrapper() throws Exception {
        Path project = consumerProject("classes-only");
        storeDiscoverCache(project, List.of(project.resolve("target/test-classes").toString()));
        assertEquals(0, syncWithoutMaven(project));
    }

    @Test
    void isolateStartupSplitsIndexFromCompile() throws Exception {
        Path project = consumerProject("stages");
        storeDiscoverCache(project, List.of(project.resolve("target/test-classes").toString()));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        String mode = System.getProperty("pkb_workbench_speed");
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        System.setProperty("pkb_workbench_speed", "overlap");
        try {
            WorkbenchSynchronizer.syncOverlapped(project);
        } finally {
            System.setErr(previous);
            if (mode == null) System.clearProperty("pkb_workbench_speed");
            else System.setProperty("pkb_workbench_speed", mode);
        }
        String text = err.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("progress stage=index" + System.lineSeparator()), text);
        assertTrue(text.contains("progress stage=index-end"), text);
        assertTrue(text.contains("progress stage=compile"), text);
        assertTrue(text.indexOf("progress stage=index" + System.lineSeparator())
                < text.indexOf("progress stage=index-end"), text);
    }

    @Test
    void secondSyncSkipsUnchangedClassesWithoutMaven() throws Exception {
        Path project = consumerProject("warm-skip");
        storeDiscoverCache(project, List.of(project.resolve("target/test-classes").toString()));
        assertEquals(0, syncWithoutMaven(project));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            assertEquals(0, syncWithoutMaven(project));
        } finally {
            System.setErr(previous);
        }
        String text = err.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("progress stage=classpath-cache"), text);
        assertTrue(Files.isRegularFile(WorkbenchManifest.workbenchRoot(project).resolve("live/classes/Runner.class")));
    }

    @Test
    void secondSyncDoesNotHashUnchangedJars() throws Exception {
        Path project = consumerProject("warm-jars");
        Path jar = tempDir.resolve("bulk.jar");
        Files.write(jar, new byte[1024 * 1024]);
        storeDiscoverCache(project, List.of(
                project.resolve("target/test-classes").toString(),
                jar.toString()
        ));
        String first = syncCaptured(project);
        assertTrue(first.contains("progress stage=hash"), first);
        assertTrue(first.contains("jars=1"), first);
        assertTrue(first.contains("hashed=1"), first);
        assertTrue(first.contains("progress stage=materialize"), first);
        assertTrue(first.contains("maven skipped=unchanged"), first);
        String second = syncCaptured(project);
        assertTrue(second.contains("progress stage=classpath-cache"), second);
        assertTrue(second.contains("hashed=false"), second);
        assertFalse(second.contains("progress stage=jar-hash"), second);
    }

    @Test
    void materializePrintsCopyAndDigestStagesBeforeHash() throws Exception {
        Path projectRoot = tempDir.resolve("stage-consumer");
        Path main = projectRoot.resolve("target/classes");
        Path test = projectRoot.resolve("target/test-classes");
        Files.createDirectories(main);
        Files.createDirectories(test);
        Files.writeString(main.resolve("main.txt"), "main", StandardCharsets.UTF_8);
        Files.writeString(test.resolve("test.txt"), "test", StandardCharsets.UTF_8);
        WorkbenchProject project = new WorkbenchProject(
                projectRoot.toAbsolutePath().normalize(),
                WorkbenchProject.Type.MAVEN,
                projectRoot.toAbsolutePath().normalize(),
                Path.of("mvn")
        );
        Path stateRoot = WorkbenchManifest.workbenchRoot(projectRoot);
        String[] err = new String[1];
        capture(err, () -> WorkbenchSynchronizer.materialize(
                project,
                new WorkbenchSynchronizer.SyncMetadata(
                        List.of(projectRoot.resolve("src/test/java")),
                        List.of(
                                new WorkbenchSynchronizer.OutputPath("MAIN", main),
                                new WorkbenchSynchronizer.OutputPath("TEST", test)
                        ),
                        List.of()
                ),
                stateRoot.resolve(".sync-stages"),
                stateRoot
        ).fingerprint());
        String text = err[0];
        int copyBase = text.indexOf("progress stage=copy-base ");
        int copyLive = text.indexOf("progress stage=copy-live ");
        int digest = text.indexOf("progress stage=digest ");
        int hash = text.indexOf("progress stage=hash ");
        assertTrue(copyBase >= 0 && copyLive > copyBase && digest > copyLive && hash > digest, text);
        assertTrue(text.contains("progress stage=copy-base files=2 bytes=8 ms="), text);
        assertTrue(text.contains("progress stage=copy-live files=2 bytes=8 ms="), text);
        assertLiveAccounted(text, 2);
        assertTrue(text.contains("progress stage=digest files=2 bytes=8 ms="), text);
        Path liveDir = stateRoot.resolve("live/classes");
        Path baseFile = stateRoot.resolve("base/classes/main.txt");
        Path liveFile = stateRoot.resolve("live/classes/main.txt");
        assertFalse(Files.isSameFile(main.resolve("main.txt"), baseFile), "base/classes must be a copy, not a link into target");
        if (Files.isSymbolicLink(liveDir)) {
            assertEquals(Path.of("..", "base", "classes"), Files.readSymbolicLink(liveDir));
            assertTrue(Files.isSameFile(baseFile, liveFile), "directory link shares the base file until detach");
            assertTrue(WorkbenchSynchronizer.detachLinkedLiveClasses(liveDir));
            assertFalse(Files.isSymbolicLink(liveDir));
            Files.writeString(liveFile, "live-edit");
            assertEquals("main", Files.readString(baseFile));
            assertEquals("live-edit", Files.readString(liveFile));
            System.out.println("windows-shaped-dirlink " + liveLine(text) + " symlink=true");
        } else {
            System.out.println("windows-shaped-dirlink " + liveLine(text) + " symlink=false");
            try {
                Path probe = tempDir.resolve("link-probe");
                Files.writeString(probe, "x");
                Files.createLink(tempDir.resolve("link-dest"), probe);
                assertTrue(Files.isSameFile(baseFile, liveFile), "live/classes should hardlink base");
            } catch (UnsupportedOperationException | IOException unsupported) {
                assertFalse(Files.isSameFile(baseFile, liveFile));
            }
        }
    }

    @Test
    void coldMaterializeOfManyClassFilesStaysInsideTheLiveStartBudget() throws Exception {
        Path projectRoot = tempDir.resolve("many-classes");
        Path test = projectRoot.resolve("target/test-classes");
        int count = 4000;
        byte[] body = new byte[128];
        for (int bucket = 0; bucket < 40; bucket++) Files.createDirectories(test.resolve("p" + bucket));
        for (int i = 0; i < count; i++) {
            Files.write(test.resolve("p" + (i % 40)).resolve("C" + i + ".class"), body);
        }
        WorkbenchProject project = new WorkbenchProject(
                projectRoot.toAbsolutePath().normalize(),
                WorkbenchProject.Type.MAVEN,
                projectRoot.toAbsolutePath().normalize(),
                Path.of("mvn")
        );
        Path stateRoot = WorkbenchManifest.workbenchRoot(projectRoot);
        String[] err = new String[1];
        long started = System.nanoTime();
        capture(err, () -> WorkbenchSynchronizer.materialize(
                project,
                new WorkbenchSynchronizer.SyncMetadata(
                        List.of(projectRoot.resolve("src/test/java")),
                        List.of(new WorkbenchSynchronizer.OutputPath("TEST", test)),
                        List.of()
                ),
                stateRoot.resolve(".sync-many"),
                stateRoot
        ).fingerprint());
        long elapsed = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        String text = err[0];
        assertTrue(text.contains("progress stage=copy-base files=" + count), text);
        assertTrue(text.contains("progress stage=copy-live files=" + count), text);
        assertLiveAccounted(text, count);
        assertTrue(text.contains("progress stage=digest files=" + count), text);
        assertTrue(elapsed < 60_000L, "materialize elapsed " + elapsed + "ms\n" + text);
        Path sample = stateRoot.resolve("base/classes/p0/C0.class");
        Path live = stateRoot.resolve("live/classes/p0/C0.class");
        if (canLink(tempDir)) assertTrue(Files.isSameFile(sample, live), text);
        System.out.println("windows-shaped-small copy-live " + liveLine(text)
                + " serialLinkMs=" + serialLinkMillis(test, count)
                + " materializeWallMs=" + elapsed);
    }

    @Test
    void coldMaterializeOfConsumerSizedTreeStaysInsideTheLiveStartBudget() throws Exception {
        Path projectRoot = tempDir.resolve("consumer-sized");
        Path test = projectRoot.resolve("target/test-classes");
        int count = 5259;
        byte[] body = new byte[37 * 1024];
        for (int bucket = 0; bucket < 40; bucket++) Files.createDirectories(test.resolve("p" + bucket));
        for (int i = 0; i < count; i++) {
            Files.write(test.resolve("p" + (i % 40)).resolve("C" + i + ".class"), body);
        }
        WorkbenchProject project = new WorkbenchProject(
                projectRoot.toAbsolutePath().normalize(),
                WorkbenchProject.Type.MAVEN,
                projectRoot.toAbsolutePath().normalize(),
                Path.of("mvn")
        );
        Path stateRoot = WorkbenchManifest.workbenchRoot(projectRoot);
        String[] err = new String[1];
        long started = System.nanoTime();
        capture(err, () -> WorkbenchSynchronizer.materialize(
                project,
                new WorkbenchSynchronizer.SyncMetadata(
                        List.of(projectRoot.resolve("src/test/java")),
                        List.of(new WorkbenchSynchronizer.OutputPath("TEST", test)),
                        List.of()
                ),
                stateRoot.resolve(".sync-sized"),
                stateRoot
        ).fingerprint());
        long elapsed = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        String text = err[0];
        assertTrue(text.contains("progress stage=copy-base files=" + count), text);
        assertTrue(text.contains("progress stage=copy-live files=" + count), text);
        assertLiveAccounted(text, count);
        assertTrue(text.contains("progress stage=digest files=" + count), text);
        assertTrue(elapsed < 60_000L, "materialize elapsed " + elapsed + "ms\n" + text);
        System.out.println("windows-shaped-consumer copy-live " + liveLine(text)
                + " files=" + count
                + " bytes=" + (long) count * body.length
                + " materializeWallMs=" + elapsed);
    }

    @Test
    void linkOrCopyFallsBackToACopyWhenTheDestinationAlreadyExists() throws Exception {
        Path source = tempDir.resolve("src.bin");
        Path destination = tempDir.resolve("dest.bin");
        Files.writeString(source, "abc");
        Files.writeString(destination, "old");
        assertFalse(WorkbenchSynchronizer.linkOrCopy(source, destination));
        assertEquals("abc", Files.readString(destination));
        assertFalse(Files.isSameFile(source, destination));
    }

    @Test
    void refusedLinksCopyEveryFileAndLeaveBaseAlone() throws Exception {
        Path projectRoot = tempDir.resolve("refused-links");
        Path test = projectRoot.resolve("target/test-classes");
        Files.createDirectories(test.resolve("p"));
        int count = 48;
        for (int i = 0; i < count; i++) {
            Files.writeString(test.resolve("p").resolve("C" + i + ".class"), "class-" + i);
        }
        WorkbenchProject project = new WorkbenchProject(
                projectRoot.toAbsolutePath().normalize(),
                WorkbenchProject.Type.MAVEN,
                projectRoot.toAbsolutePath().normalize(),
                Path.of("mvn")
        );
        Path stateRoot = WorkbenchManifest.workbenchRoot(projectRoot);
        String[] err = new String[1];
        boolean previous = WorkbenchSynchronizer.linksDisabledForTest;
        WorkbenchSynchronizer.linksDisabledForTest = true;
        try {
            capture(err, () -> WorkbenchSynchronizer.materialize(
                    project,
                    new WorkbenchSynchronizer.SyncMetadata(
                            List.of(projectRoot.resolve("src/test/java")),
                            List.of(new WorkbenchSynchronizer.OutputPath("TEST", test)),
                            List.of()
                    ),
                    stateRoot.resolve(".sync-refused"),
                    stateRoot
            ).fingerprint());
        } finally {
            WorkbenchSynchronizer.linksDisabledForTest = previous;
        }
        String text = err[0];
        assertTrue(text.contains("progress stage=copy-live files=" + count), text);
        assertTrue(text.contains("links=0 copies=" + count), text);
        Path base = stateRoot.resolve("base/classes/p/C0.class");
        Path live = stateRoot.resolve("live/classes/p/C0.class");
        assertFalse(Files.isSameFile(test.resolve("p/C0.class"), base));
        assertFalse(Files.isSameFile(base, live));
        Files.writeString(live, "mutated");
        assertEquals("class-0", Files.readString(base));
        System.out.println("windows-shaped-fallback " + liveLine(text));
    }

    private static void assertLiveAccounted(String text, int files) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "progress stage=copy-live files=(\\d+) bytes=\\d+ ms=\\d+ links=(\\d+) copies=(\\d+)"
        ).matcher(text);
        assertTrue(matcher.find(), text);
        assertEquals(files, Integer.parseInt(matcher.group(1)), text);
        int links = Integer.parseInt(matcher.group(2));
        int copies = Integer.parseInt(matcher.group(3));
        assertEquals(files, links + copies, text);
    }

    private static String liveLine(String text) {
        for (String line : text.split("\\R")) {
            if (line.contains("progress stage=copy-live ")) return line.trim();
        }
        return "";
    }

    /** Serial linkOrCopy of the same tree, so a parallel copy-live can be compared on this host. */
    private static long serialLinkMillis(Path source, int expected) throws IOException {
        Path target = source.resolveSibling("serial-links");
        Files.createDirectories(target);
        long started = System.nanoTime();
        int files = 0;
        try (var paths = Files.walk(source)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                Path destination = target.resolve(source.relativize(file).toString());
                Files.createDirectories(destination.getParent());
                WorkbenchSynchronizer.linkOrCopy(file, destination);
                files++;
            }
        }
        assertEquals(expected, files);
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static boolean canLink(Path directory) {
        try {
            Path source = directory.resolve("can-link-src");
            Path dest = directory.resolve("can-link-dst");
            Files.writeString(source, "x");
            Files.createLink(dest, source);
            return Files.isSameFile(source, dest);
        } catch (IOException | UnsupportedOperationException failure) {
            return false;
        }
    }

    private static String syncCaptured(Path project) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            assertEquals(0, syncWithoutMaven(project));
        } finally {
            System.setErr(previous);
        }
        return err.toString(StandardCharsets.UTF_8);
    }

    private Path consumerProject(String name) throws IOException {
        Path project = tempDir.resolve(name);
        Files.createDirectories(project.resolve("target/test-classes"));
        Files.writeString(project.resolve("target/test-classes/Runner.class"), "class");
        Files.writeString(project.resolve("pom.xml"), "<project>" + name + "</project>\n");
        Files.writeString(project.resolve("mvnw"), "#!/bin/sh\nexit 1\n");
        return project;
    }

    private static void storeDiscoverCache(Path project, List<String> entries) throws IOException {
        ClasspathCache.store(
                WorkbenchManifest.workbenchRoot(project),
                InputFingerprint.lockHash(project),
                entries
        );
    }

    private static int syncWithoutMaven(Path project) {
        AtomicInteger maven = new AtomicInteger();
        WorkbenchSynchronizer synchronizer = new WorkbenchSynchronizer((ignored, args, log) -> {
            maven.incrementAndGet();
            throw new IllegalStateException("maven started: " + args);
        });
        synchronizer.sync(project);
        return maven.get();
    }

    private Path stagedTree(String contents) throws IOException {
        Path staged = tempDir.resolve("staged-" + contents);
        Files.createDirectories(staged.resolve("nested"));
        Files.writeString(staged.resolve("nested").resolve("a.txt"), contents);
        return staged;
    }

    private static void assertNoTempSiblings(Path target) throws IOException {
        Path parent = target.getParent();
        String prefix = target.getFileName() + ".tmp-";
        try (var children = Files.list(parent)) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().startsWith(prefix)));
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
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
            Files.writeString(projectRoot.resolve("mvnw.cmd"), "@echo off\r\n", StandardCharsets.UTF_8);
            Files.writeString(projectRoot.resolve("mvnw"), "#!/bin/sh\n", StandardCharsets.UTF_8);
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
