package tools.dscode.workbench.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.WorkbenchFeatureIndex;
import tools.dscode.control.protocol.speed.ClasspathCache;
import tools.dscode.control.protocol.speed.InputFingerprint;

/** Uses the consumer build once, then materializes one effective Workbench live runtime. */
public final class WorkbenchSynchronizer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GRADLE_METADATA_PREFIX = "PKB_WORKBENCH_METADATA=";

    @FunctionalInterface
    interface CommandRunner {
        String run(WorkbenchProject project, List<String> args, Path log);
    }

    private final CommandRunner commandRunner;

    public WorkbenchSynchronizer() {
        this(null);
    }

    WorkbenchSynchronizer(CommandRunner commandRunner) {
        this.commandRunner = commandRunner == null ? this::runProcess : commandRunner;
    }

    public WorkbenchManifest sync(Path requestedProject) {
        WorkbenchManifest reused = trySkipUnchanged(requestedProject);
        if (reused != null) return reused;
        progress("sync");
        try {
            WorkbenchManifest cached = syncFromClasspathCache(requestedProject);
            if (cached != null) return cached;
        } catch (RuntimeException ignored) {
            // Cache materialize is optional. The wrapper path below still runs.
        }
        WorkbenchProject project = WorkbenchProject.locate(requestedProject);
        Path stateRoot = WorkbenchManifest.workbenchRoot(project.root());
        WorkbenchManifest previous = WorkbenchManifest.readIfPresent(stateRoot);
        WorkbenchSyncInputs inputs = WorkbenchSyncInputs.capture(project, previous);
        WorkbenchSyncMode mode = WorkbenchSyncPlanner.decide(
                previous, inputs, WorkbenchSyncInputs.snapshotReady(stateRoot)
        );
        if (mode == WorkbenchSyncMode.RESOURCES_ONLY
                && !WorkbenchSyncInputs.compiledOutputsPresent(previous)) {
            mode = WorkbenchSyncMode.FULL;
        }
        if (mode == WorkbenchSyncMode.SKIPPED) {
            return skip(previous, inputs, stateRoot);
        }

        Path logs = stateRoot.resolve("logs");
        Path staging = stateRoot.resolve(".sync-" + UUID.randomUUID());
        createDirectories(logs, staging);

        Path log = logs.resolve("sync-" + System.currentTimeMillis() + ".log");
        try {
            SyncMetadata metadata = mode == WorkbenchSyncMode.RESOURCES_ONLY
                    ? synchronizeResources(project, previous, log)
                    : synchronizeFull(project, staging, log);
            return materialize(project, metadata, staging, stateRoot, mode);
        } finally {
            deleteTree(staging);
        }
    }

    static WorkbenchManifest materialize(
            WorkbenchProject project,
            SyncMetadata metadata,
            Path staging,
            Path stateRoot
    ) {
        return materialize(
                project,
                metadata,
                staging,
                stateRoot,
                WorkbenchSyncMode.FULL
        );
    }

    static WorkbenchManifest materialize(
            WorkbenchProject project,
            SyncMetadata metadata,
            Path staging,
            Path stateRoot,
            WorkbenchSyncMode mode
    ) {
        Path stagedBase = staging.resolve("base");
        Path stagedBaseClasses = stagedBase.resolve("classes");
        Path stagedLive = staging.resolve("live");
        Path stagedLiveClasses = stagedLive.resolve("classes");
        createDirectories(stagedBaseClasses, stagedLive);
        progress("materialize");
        boolean copiedAnyOutput = false;
        TreeStats copiedBase = TreeStats.EMPTY;
        for (OutputPath output : metadata.outputs()) {
            if (Files.isDirectory(output.path())) {
                copiedBase = copiedBase.plus(copyTree(output.path(), stagedBaseClasses));
                copiedAnyOutput = true;
            }
        }
        if (!copiedAnyOutput) {
            throw new IllegalStateException(
                    "Build completed but no compiled/resource output directories were found for " + project.root()
            );
        }
        progress(copiedBase.stage("copy-base"));

        Path finalBase = stateRoot.resolve("base");
        Path finalBaseClasses = finalBase.resolve("classes").toAbsolutePath().normalize();
        java.util.concurrent.atomic.AtomicBoolean baseInstalled = new java.util.concurrent.atomic.AtomicBoolean();
        TreeStats copiedLive = linkLiveClasses(
                stagedBase, stagedBaseClasses, stagedLiveClasses, finalBase, finalBaseClasses, baseInstalled
        );
        progress(copiedLive.stage("copy-live"));
        createDirectories(
                stagedLive.resolve("candidate-source"),
                stagedLive.resolve("generated-java"),
                stagedLive.resolve("generated-classes")
        );
        createDirectories(stateRoot, stateRoot.resolve("sessions"), stateRoot.resolve("evidence"), stateRoot.resolve("logs"));
        if (!baseInstalled.get()) {
            replaceDirectory(stagedBase, finalBase);
        }
        replaceDirectory(stagedLive, stateRoot.resolve("live"));

        List<String> dependencies = distinctExisting(metadata.dependencies());
        WorkbenchSyncInputs stored = WorkbenchSyncInputs.capture(
                project, metadata.sourceRoots(), dependencies
        );
        String fingerprint = fingerprint(
                finalBaseClasses,
                dependencies,
                stateRoot.resolve("jar-hashes.v2")
        );
        Path finalLiveClasses = PickleballLocalLayout.liveClasses(stateRoot);
        List<String> classpathEntries = workerClasspath(stateRoot, dependencies);

        WorkbenchManifest manifest = new WorkbenchManifest(
                WorkbenchManifest.CURRENT_SCHEMA,
                project.root().toString(),
                project.type().name(),
                project.launcher().toString(),
                metadata.sourceRoots().stream().map(Path::toString).toList(),
                metadata.outputs().stream()
                        .map(output -> new WorkbenchManifest.OutputRoot(output.kind(), output.path().toString()))
                        .toList(),
                metadata.outputs().stream()
                        .map(output -> new WorkbenchManifest.OutputMapping(
                                output.kind(),
                                output.path().toString(),
                                finalBaseClasses.toString(),
                                finalLiveClasses.toString()
                        ))
                        .toList(),
                finalLiveClasses.toString(),
                Instant.now().toString(),
                fingerprint,
                dependencies,
                implementationVersion(),
                System.getProperty("java.version", "unknown"),
                System.getProperty("java.home", "unknown"),
                mode == null ? WorkbenchSyncMode.FULL.name() : mode.name(),
                stored.javaFingerprint(),
                stored.resourceFingerprint(),
                stored.buildFingerprint(),
                stored.dependencyFingerprint()
        );

        Path stagedManifest = staging.resolve("manifest.json");
        Path stagedClasspath = staging.resolve(PickleballLocalLayout.WORKER_CLASSPATH_FILE);
        manifest.write(stagedManifest);
        writeLines(stagedClasspath, classpathEntries);
        replaceFile(stagedManifest, stateRoot.resolve("manifest.json"));
        replaceFile(stagedClasspath, stateRoot.resolve(PickleballLocalLayout.WORKER_CLASSPATH_FILE));
        return manifest;
    }

    /**
     * When the previous manifest, live classes, and cheap input fingerprints still match,
     * do not materialize or hash jars. The first sync after a fingerprint change still
     * materializes once and stores the cheap hash.
     */
    private WorkbenchManifest trySkipUnchanged(Path requestedProject) {
        try {
            WorkbenchProject project = WorkbenchProject.locate(requestedProject);
            Path stateRoot = WorkbenchManifest.workbenchRoot(project.root());
            WorkbenchManifest previous = WorkbenchManifest.readIfPresent(stateRoot);
            if (previous == null) return null;
            if (!WorkbenchSyncInputs.snapshotReady(stateRoot)) return null;
            if (!WorkbenchSyncInputs.compiledOutputsPresent(previous)) return null;
            WorkbenchSyncInputs inputs = WorkbenchSyncInputs.capture(project, previous);
            if (WorkbenchSyncPlanner.decide(previous, inputs, true) != WorkbenchSyncMode.SKIPPED) return null;
            int jars = previous.dependencyClasspath() == null ? 0 : previous.dependencyClasspath().size();
            System.err.println("progress stage=classpath-cache hashed=false jars=" + jars);
            return skip(previous, inputs, stateRoot);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * When discover already resolved this pom hash and test classes exist,
     * copy them into the live tree. No wrapper process.
     */
    static WorkbenchManifest syncFromClasspathCache(Path requestedProject) {
        WorkbenchProject project = WorkbenchProject.locate(requestedProject);
        Path root = project.root();
        String hash = InputFingerprint.lockHash(root);
        Path stateRoot = WorkbenchManifest.workbenchRoot(root);
        ClasspathCache.Hit hit = ClasspathCache.load(stateRoot, hash);
        if (!hit.hit() || !ClasspathCache.hasProjectOutput(root, hit.entries())) return null;
        List<OutputPath> outputs = new ArrayList<>();
        List<String> dependencies = new ArrayList<>();
        for (String entry : hit.entries()) {
            Path path = Path.of(entry).toAbsolutePath().normalize();
            if (!Files.exists(path)) return null;
            if (isProjectOutput(root, path) && Files.isDirectory(path)) {
                outputs.add(new OutputPath(path.toString().contains("test") ? "TEST" : "MAIN", path));
            } else if (Files.exists(path)) {
                dependencies.add(path.toString());
            }
        }
        if (outputs.isEmpty()) return null;
        progress("maven skipped=unchanged");
        Path staging = stateRoot.resolve(".sync-cache-" + UUID.randomUUID());
        createDirectories(staging);
        try {
            return materialize(
                    project,
                    new SyncMetadata(sourceRoots(root), outputs, dependencies),
                    staging,
                    stateRoot,
                    WorkbenchSyncMode.SKIPPED
            );
        } finally {
            deleteTree(staging);
        }
    }

    private static boolean isProjectOutput(Path root, Path path) {
        Path target = root.resolve("target");
        Path build = root.resolve("build");
        return path.startsWith(target) || path.startsWith(build);
    }

    private static List<Path> sourceRoots(Path root) {
        List<Path> roots = new ArrayList<>();
        for (String relative : List.of("src/main/java", "src/test/java", "src/main/resources", "src/test/resources")) {
            Path path = root.resolve(relative);
            if (Files.isDirectory(path)) roots.add(path);
        }
        if (roots.isEmpty()) roots.add(root.resolve("src/test/java"));
        return roots;
    }

    /** Index the catalog while sync runs. Sync failure still throws. Index failure does not. */
    public static WorkbenchManifest syncOverlapped(Path project) {
        String mode = System.getProperty("pkb_workbench_speed");
        if (mode == null) mode = System.getenv("PKB_WORKBENCH_SPEED");
        if (mode != null && "sequential".equalsIgnoreCase(mode.trim())) {
            return syncThenIndex(project);
        }
        java.util.concurrent.atomic.AtomicReference<WorkbenchManifest> manifest = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<RuntimeException> failure = new java.util.concurrent.atomic.AtomicReference<>();
        tools.dscode.control.protocol.speed.StageScheduler.run(List.of(
                        new tools.dscode.control.protocol.speed.StageScheduler.Task("sync", () -> {
                            progress("compile");
                            try {
                                manifest.set(new WorkbenchSynchronizer().sync(project));
                                return "sync";
                            } catch (RuntimeException syncFailure) {
                                failure.set(syncFailure);
                                throw syncFailure;
                            }
                        }),
                        new tools.dscode.control.protocol.speed.StageScheduler.Task("index", () -> {
                            progress("index");
                            try {
                                indexCatalog(project);
                                return "index";
                            } finally {
                                progress("index-end");
                            }
                        })
                ), true);
        if (failure.get() != null) throw failure.get();
        WorkbenchManifest done = manifest.get();
        if (done != null) return done;
        return new WorkbenchSynchronizer().sync(project);
    }

    private static WorkbenchManifest syncThenIndex(Path project) {
        progress("index");
        try {
            indexCatalog(project);
        } catch (RuntimeException ignored) {
            // Index failure does not hide sync.
        } finally {
            progress("index-end");
        }
        progress("compile");
        return new WorkbenchSynchronizer().sync(project);
    }

    private static void indexCatalog(Path project) {
        WorkbenchFeatureIndex.select(new WorkbenchFeatureIndex.Request(
                project, List.of(), null, null, null, List.of(), false
        ));
    }

    private static void progress(String stage) {
        System.err.println("progress stage=" + stage);
    }

    public static List<String> readWorkerClasspath(Path projectRoot) {
        Path stateRoot = WorkbenchManifest.workbenchRoot(projectRoot);
        Path file = stateRoot.resolve(PickleballLocalLayout.WORKER_CLASSPATH_FILE);
        try {
            List<String> entries = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
            if (entries.isEmpty()) {
                throw new IllegalStateException("Workbench classpath is empty: " + file);
            }
            assertNoBaseClasspath(stateRoot, entries);
            if (!PickleballLocalLayout.startsWithLiveClasses(stateRoot, entries)) {
                throw new IllegalStateException(
                        "Workbench classpath must start with the one effective live output: "
                                + PickleballLocalLayout.liveClasses(stateRoot)
                );
            }
            return entries;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read Workbench classpath. Run sync first: " + file, failure);
        }
    }

    /**
     * One worker classpath: live classes, then captured dependencies.
     * {@code base/classes} is never an entry. Sync is the only writer of
     * {@code classpath.txt}; a skip rebuilds this list instead of keeping a
     * file another step replaced.
     */
    static List<String> workerClasspath(Path stateRoot, List<String> dependencies) {
        Path live = PickleballLocalLayout.liveClasses(stateRoot);
        List<String> entries = new ArrayList<>();
        entries.add(live.toString());
        if (dependencies != null) {
            for (String dependency : dependencies) {
                if (dependency == null || dependency.isBlank()) continue;
                Path path = Path.of(dependency).toAbsolutePath().normalize();
                if (path.equals(live)) continue;
                entries.add(path.toString());
            }
        }
        assertNoBaseClasspath(stateRoot, entries);
        return entries;
    }

    private static void ensureWorkerClasspath(Path stateRoot, List<String> dependencies) {
        writeLines(
                stateRoot.resolve(PickleballLocalLayout.WORKER_CLASSPATH_FILE),
                workerClasspath(stateRoot, dependencies)
        );
    }

    private WorkbenchManifest skip(
            WorkbenchManifest previous,
            WorkbenchSyncInputs inputs,
            Path stateRoot
    ) {
        ensureWorkerClasspath(stateRoot, previous.dependencyClasspath());
        WorkbenchManifest updated = previous.withSkip(Instant.now().toString(), inputs);
        updated.write(stateRoot.resolve("manifest.json"));
        return updated;
    }

    private SyncMetadata synchronizeFull(WorkbenchProject project, Path staging, Path log) {
        return project.type() == WorkbenchProject.Type.MAVEN
                ? synchronizeMavenFull(project, staging, log)
                : synchronizeGradleFull(project, staging, log);
    }

    private SyncMetadata synchronizeResources(
            WorkbenchProject project,
            WorkbenchManifest previous,
            Path log
    ) {
        if (project.type() == WorkbenchProject.Type.MAVEN) {
            commandRunner.run(project, mavenResourceArgs(project), log);
        } else {
            commandRunner.run(project, gradleResourceArgs(), log);
        }
        return metadataFrom(previous);
    }

    private SyncMetadata synchronizeMavenFull(WorkbenchProject project, Path staging, Path log) {
        progress("maven");
        Path dependencyClasspath = staging.resolve("maven-classpath.txt");
        Path effectivePom = staging.resolve("effective-pom.xml");
        commandRunner.run(project, mavenFullArgs(project, dependencyClasspath, effectivePom), log);

        MavenMetadata pom = parseEffectivePom(project.root(), effectivePom);
        List<String> dependencies = readClasspathValue(dependencyClasspath);
        return new SyncMetadata(pom.sourceRoots(), pom.outputs(), dependencies);
    }

    static List<String> mavenFullArgs(WorkbenchProject project, Path dependencyClasspath, Path effectivePom) {
        return List.of(
                "-f", project.root().resolve("pom.xml").toString(),
                "-DskipTests",
                "test-compile",
                "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath",
                "-Dmdep.includeScope=test",
                "-Dmdep.pathSeparator=" + File.pathSeparator,
                "-Dmdep.outputFile=" + dependencyClasspath,
                "org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom",
                "-Doutput=" + effectivePom
        );
    }

    static List<String> mavenResourceArgs(WorkbenchProject project) {
        return List.of(
                "-f", project.root().resolve("pom.xml").toString(),
                "-DskipTests",
                "process-resources",
                "process-test-resources"
        );
    }

    static List<String> gradleResourceArgs() {
        return List.of(
                "processResources",
                "processTestResources",
                "--console=plain",
                "-q"
        );
    }

    private static SyncMetadata metadataFrom(WorkbenchManifest previous) {
        List<Path> sources = previous.sourceRoots().stream()
                .map(value -> Path.of(value).toAbsolutePath().normalize())
                .toList();
        List<OutputPath> outputs = previous.outputRoots().stream()
                .map(output -> new OutputPath(output.kind(), Path.of(output.path())))
                .toList();
        return new SyncMetadata(sources, outputs, previous.dependencyClasspath());
    }

    private SyncMetadata synchronizeGradleFull(WorkbenchProject project, Path staging, Path log) {
        Path initScript = staging.resolve("workbench-sync.init.gradle");
        writeString(initScript, gradleInitScript());
        List<String> args = List.of(
                "-I", initScript.toString(),
                "-Dpickleball.workbench.projectDir=" + project.root(),
                "pickleballWorkbenchSyncMetadata",
                "--console=plain",
                "-q"
        );
        String output = commandRunner.run(project, args, log);
        String metadataLine = output.lines()
                .filter(line -> line.startsWith(GRADLE_METADATA_PREFIX))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalStateException(
                        "Gradle synchronization did not report Workbench source/output metadata. See " + log
                ));
        try {
            JsonNode root = JSON.readTree(metadataLine.substring(GRADLE_METADATA_PREFIX.length()));
            List<Path> sources = paths(root.path("sourceRoots"));
            List<OutputPath> outputs = new ArrayList<>();
            paths(root.path("mainOutputs")).forEach(path -> outputs.add(new OutputPath("MAIN", path)));
            paths(root.path("testOutputs")).forEach(path -> outputs.add(new OutputPath("TEST", path)));
            List<String> dependencies = paths(root.path("dependencies")).stream()
                    .map(Path::toString)
                    .toList();
            return new SyncMetadata(sources, outputs, dependencies);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not parse Gradle Workbench metadata.", failure);
        }
    }

    static MavenMetadata parseEffectivePom(Path projectRoot, Path effectivePom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Element project = factory.newDocumentBuilder().parse(effectivePom.toFile()).getDocumentElement();
            Element build = directChild(project, "build");
            if (build == null) {
                throw new IllegalStateException("Effective Maven POM does not contain a build section.");
            }

            List<Path> sourceRoots = new ArrayList<>();
            addPath(sourceRoots, projectRoot, childText(build, "sourceDirectory"));
            addPath(sourceRoots, projectRoot, childText(build, "testSourceDirectory"));
            addResourcePaths(sourceRoots, projectRoot, directChild(build, "resources"), "resource");
            addResourcePaths(sourceRoots, projectRoot, directChild(build, "testResources"), "testResource");

            List<OutputPath> outputs = new ArrayList<>();
            addOutput(outputs, "MAIN", projectRoot, childText(build, "outputDirectory"));
            addOutput(outputs, "TEST", projectRoot, childText(build, "testOutputDirectory"));
            return new MavenMetadata(distinctPaths(sourceRoots), outputs);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not parse effective Maven POM: " + effectivePom, failure);
        }
    }

    private String runProcess(WorkbenchProject project, List<String> args, Path log) {
        List<String> command = executableCommand(project.launcher(), args);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(project.buildRoot().toFile())
                .redirectErrorStream(true);

        StringBuilder output = new StringBuilder();
        try {
            Files.createDirectories(log.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
                Process process = builder.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
                )) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        writer.write(line);
                        writer.newLine();
                        output.append(line).append('\n');
                    }
                }
                int exit = process.waitFor();
                if (exit != 0) {
                    throw new IllegalStateException(
                            project.type() + " synchronization failed with exit code " + exit
                                    + ". See " + log + "\n" + tail(output, 12000)
                    );
                }
            }
            return output.toString();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Workbench synchronization was interrupted.", failure);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not run " + project.type() + " synchronization through " + project.launcher(),
                    failure
            );
        }
    }

    static List<String> executableCommand(Path launcher, List<String> args) {
        String name = launcher.getFileName() == null ? launcher.toString() : launcher.getFileName().toString();
        List<String> command = new ArrayList<>();
        if (name.endsWith(".jar")) {
            command.add(javaExecutable());
            command.add("-jar");
            command.add(launcher.toString());
        } else if (WorkbenchProject.isWindows() && (name.endsWith(".cmd") || name.endsWith(".bat"))) {
            command.add("cmd.exe");
            command.add("/d");
            command.add("/c");
            command.add(launcher.toString());
        } else if (!WorkbenchProject.isWindows() && Files.isRegularFile(launcher) && !Files.isExecutable(launcher)) {
            command.add("sh");
            command.add(launcher.toString());
        } else {
            command.add(launcher.toString());
        }
        command.addAll(args);
        return command;
    }

    private static String javaExecutable() {
        String binary = WorkbenchProject.isWindows() ? "java.exe" : "java";
        Path bin = Path.of(System.getProperty("java.home"), "bin", binary);
        return Files.isRegularFile(bin) ? bin.toString() : binary;
    }

    private static String gradleInitScript() {
        return """
                import groovy.json.JsonOutput

                gradle.projectsEvaluated {
                    def selectedDir = new File(System.getProperty('pickleball.workbench.projectDir')).canonicalFile
                    def selected = gradle.rootProject.allprojects.find { it.projectDir.canonicalFile == selectedDir }
                    if (selected == null) {
                        throw new GradleException("No Gradle project found at ${selectedDir}")
                    }
                    def sourceSets = selected.extensions.findByName('sourceSets')
                    if (sourceSets == null || sourceSets.findByName('test') == null) {
                        throw new GradleException("Selected Workbench project must expose Java sourceSets: ${selected.path}")
                    }
                    def mainSet = sourceSets.findByName('main')
                    def testSet = sourceSets.findByName('test')
                    def outputFiles = { sourceSet ->
                        def result = []
                        result.addAll(sourceSet.output.classesDirs.files)
                        if (sourceSet.output.resourcesDir != null) result.add(sourceSet.output.resourcesDir)
                        result.findAll { it != null }.collect { it.canonicalFile }
                    }
                    def mainOutputs = outputFiles(mainSet)
                    def testOutputs = outputFiles(testSet)
                    def owned = (mainOutputs + testOutputs) as Set

                    selected.rootProject.tasks.register('pickleballWorkbenchSyncMetadata') {
                        dependsOn selected.tasks.named('testClasses')
                        doLast {
                            def metadata = [
                                sourceRoots: (mainSet.allSource.srcDirs + testSet.allSource.srcDirs)
                                        .collect { it.canonicalPath }.unique(),
                                mainOutputs: mainOutputs.collect { it.canonicalPath },
                                testOutputs: testOutputs.collect { it.canonicalPath },
                                dependencies: testSet.runtimeClasspath.files
                                        .collect { it.canonicalFile }
                                        .findAll { !owned.contains(it) }
                                        .collect { it.canonicalPath }
                            ]
                            println('PKB_WORKBENCH_METADATA=' + JsonOutput.toJson(metadata))
                        }
                    }
                }
                """;
    }

    private static List<Path> paths(JsonNode node) {
        List<Path> result = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(value -> result.add(Path.of(value.asText()).toAbsolutePath().normalize()));
        }
        return result;
    }

    private static void addResourcePaths(
            List<Path> target,
            Path projectRoot,
            Element container,
            String childName
    ) {
        if (container == null) return;
        for (Element resource : directChildren(container, childName)) {
            addPath(target, projectRoot, childText(resource, "directory"));
        }
    }

    private static void addOutput(List<OutputPath> target, String kind, Path projectRoot, String value) {
        if (value == null || value.isBlank()) return;
        target.add(new OutputPath(kind, resolve(projectRoot, value)));
    }

    private static void addPath(List<Path> target, Path projectRoot, String value) {
        if (value == null || value.isBlank()) return;
        target.add(resolve(projectRoot, value));
    }

    private static Path resolve(Path projectRoot, String value) {
        Path path = Path.of(value.trim());
        return (path.isAbsolute() ? path : projectRoot.resolve(path)).toAbsolutePath().normalize();
    }

    private static Element directChild(Element parent, String name) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && name.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> directChildren(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && name.equals(element.getTagName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static String childText(Element parent, String name) {
        Element child = directChild(parent, name);
        return child == null ? null : child.getTextContent().trim();
    }

    private static List<String> readClasspathValue(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (raw.isBlank()) return List.of();
            List<String> result = new ArrayList<>();
            for (String entry : raw.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    result.add(Path.of(entry.trim()).toAbsolutePath().normalize().toString());
                }
            }
            return result;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read resolved Maven test classpath: " + file, failure);
        }
    }

    private static List<String> distinctExisting(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (Files.exists(path)) result.add(path.toString());
        }
        return List.copyOf(result);
    }

    private static List<Path> distinctPaths(List<Path> values) {
        Set<Path> result = new LinkedHashSet<>();
        values.stream().map(path -> path.toAbsolutePath().normalize()).forEach(result::add);
        return List.copyOf(result);
    }

    private static void assertNoBaseClasspath(Path stateRoot, List<String> entries) {
        Path base = stateRoot.resolve("base").toAbsolutePath().normalize();
        for (String entry : entries) {
            Path path = Path.of(entry).toAbsolutePath().normalize();
            if (path.startsWith(base)) {
                throw new IllegalStateException("Workbench base output must never be on the worker classpath: " + path);
            }
        }
    }

    private static final long RACY_WINDOW_MS = 2_000L;
    private static final char KEY_SEP = '\u001f';
    private static volatile LongSupplier nowMillis = System::currentTimeMillis;

    /** Test hook. A null supplier restores the wall clock. */
    static void useClock(LongSupplier clock) {
        nowMillis = clock == null ? System::currentTimeMillis : clock;
    }

    /**
     * A jar token is path, size, mtime, and file identity. A jar first seen while
     * its mtime is still within two seconds of now is recorded with a content hash,
     * and that token stays with the jar. A later start inside the window reads the
     * bytes again so a same-mtime rewrite still changes the fingerprint. Once the
     * mtime is outside the window the same token is kept and the recorded time is
     * refreshed, and {@code hashed} is how many jars were read. On Windows, fileKey()
     * and ctime are not available, so the key is effectively path, size, and mtime
     * plus the 2 s rule.
     */
    private static String fingerprint(Path classes, List<String> dependencies, Path cacheFile) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            TreeStats classesDigest = updateDirectoryDigest(digest, classes, true);
            progress(classesDigest.stage("digest"));
            Map<String, CachedJar> cache = loadJarHashCache(cacheFile);
            Map<String, String> statKeys = new HashMap<>();
            for (Map.Entry<String, CachedJar> entry : cache.entrySet()) {
                statKeys.put(statKeyFromExact(entry.getKey()), entry.getKey());
            }
            Map<String, CachedJar> next = new java.util.LinkedHashMap<>();
            int jars = 0;
            int reused = 0;
            int hashed = 0;
            long bytes = 0L;
            List<String> ordered = dependencies == null ? List.of() : dependencies;
            long now = nowMillis.getAsLong();
            for (String dependency : ordered) {
                if (dependency == null || dependency.isBlank()) continue;
                Path path = Path.of(dependency).toAbsolutePath().normalize();
                digest.update(path.toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                if (!Files.isRegularFile(path)) {
                    if (Files.isDirectory(path)) updateDirectoryDigest(digest, path, false);
                    digest.update((byte) 0);
                    continue;
                }
                JarStat stat = jarStat(path);
                jars++;
                bytes += stat.size;
                String exact = exactKey(path.toString(), stat);
                String statKey = statKey(path.toString(), stat);
                CachedJar cached = cache.get(exact);
                String token;
                long recordedAt;
                boolean outsideWindow = now - stat.mtimeMillis > RACY_WINDOW_MS;
                boolean insideWindow = now - stat.mtimeMillis <= RACY_WINDOW_MS
                        && now - stat.mtimeMillis >= -RACY_WINDOW_MS;
                if (cached != null && (outsideWindow || !racy(stat, cached.recordedAt))) {
                    token = cached.token;
                    recordedAt = outsideWindow ? now : cached.recordedAt;
                    reused++;
                } else if (cached != null || statKeys.containsKey(statKey)) {
                    String content = contentToken(path);
                    hashed++;
                    token = cached != null && content.equals(cached.token) ? cached.token : content;
                    recordedAt = cached != null ? cached.recordedAt : now;
                } else if (insideWindow) {
                    token = contentToken(path);
                    recordedAt = now;
                    hashed++;
                } else {
                    token = statToken(exact);
                    recordedAt = now;
                }
                next.put(exact, new CachedJar(token, recordedAt));
                digest.update(token.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            saveJarHashCache(cacheFile, next);
            System.err.println("progress stage=hash jars=" + jars
                    + " reused=" + reused
                    + " hashed=" + hashed
                    + " bytes=" + bytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new IllegalStateException("Could not fingerprint Workbench synchronization output.", failure);
        }
    }

    private record JarStat(
            long size,
            long mtimeMillis,
            int mtimeNano,
            String fileKey,
            long ctimeMillis,
            int ctimeNano
    ) {}

    private record CachedJar(String token, long recordedAt) {}

    private static JarStat jarStat(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        FileTime modified = attrs.lastModifiedTime();
        Object key = attrs.fileKey();
        long ctimeMillis = -1L;
        int ctimeNano = -1;
        try {
            Object ctime = Files.getAttribute(path, "unix:ctime");
            if (ctime instanceof FileTime time) {
                ctimeMillis = time.toMillis();
                ctimeNano = time.toInstant().getNano();
            }
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            // No unix ctime on this provider. The racy-mtime rule still applies.
        }
        return new JarStat(
                attrs.size(),
                modified.toMillis(),
                modified.toInstant().getNano(),
                key == null ? "-" : sanitize(key.toString()),
                ctimeMillis,
                ctimeNano
        );
    }

    /** Same path, size, and mtime as a token recorded inside the coarse clock window. */
    private static boolean racy(JarStat stat, long recordedAt) {
        long delta = recordedAt - stat.mtimeMillis;
        return delta <= RACY_WINDOW_MS && delta >= -RACY_WINDOW_MS;
    }

    private static String exactKey(String path, JarStat stat) {
        return statKey(path, stat) + KEY_SEP + stat.fileKey + KEY_SEP + stat.ctimeMillis + KEY_SEP + stat.ctimeNano;
    }

    private static String statKey(String path, JarStat stat) {
        return sanitize(path) + KEY_SEP + stat.size + KEY_SEP + stat.mtimeMillis + KEY_SEP + stat.mtimeNano;
    }

    private static String statKeyFromExact(String exact) {
        int seps = 0;
        for (int i = 0; i < exact.length(); i++) {
            if (exact.charAt(i) == KEY_SEP && ++seps == 4) return exact.substring(0, i);
        }
        return exact;
    }

    private static String sanitize(String value) {
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').replace(KEY_SEP, ' ');
    }

    private static String statToken(String exactKey) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("stat".getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(exactKey.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String contentToken(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        updateFileDigest(digest, path);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Map<String, CachedJar> loadJarHashCache(Path cacheFile) {
        Map<String, CachedJar> cache = new java.util.LinkedHashMap<>();
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) return cache;
        try {
            List<String> lines = Files.readAllLines(cacheFile, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !"v2".equals(lines.get(0).trim())) return cache;
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                int first = line.indexOf('\t');
                int last = line.lastIndexOf('\t');
                if (first <= 0 || last <= first) continue;
                long recordedAt;
                try {
                    recordedAt = Long.parseLong(line.substring(last + 1).trim());
                } catch (NumberFormatException ignored) {
                    continue;
                }
                cache.put(line.substring(0, first), new CachedJar(line.substring(first + 1, last), recordedAt));
            }
        } catch (IOException ignored) {
            return new java.util.LinkedHashMap<>();
        }
        return cache;
    }

    private static void saveJarHashCache(Path cacheFile, Map<String, CachedJar> cache) throws IOException {
        if (cacheFile == null) return;
        Files.createDirectories(cacheFile.getParent());
        StringBuilder body = new StringBuilder("v2\n");
        for (Map.Entry<String, CachedJar> entry : cache.entrySet()) {
            body.append(entry.getKey()).append('\t')
                    .append(entry.getValue().token).append('\t')
                    .append(entry.getValue().recordedAt).append('\n');
        }
        Path temp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        Files.writeString(temp, body.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Class files are keyed by relative path, size, and mtime. Bytes are not read.
     * Jars keep the racy-window content hash. A directory dependency still hashes bytes.
     */
    private static TreeStats updateDirectoryDigest(MessageDigest digest, Path root, boolean metadataOnly) throws IOException {
        int files = 0;
        long bytes = 0L;
        long started = System.nanoTime();
        if (root != null && Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    digest.update(root.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    if (metadataOnly) {
                        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                        digest.update(Long.toString(attrs.size()).getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                        digest.update(Long.toString(attrs.lastModifiedTime().toMillis()).getBytes(StandardCharsets.UTF_8));
                        bytes += attrs.size();
                    } else {
                        bytes += updateFileDigest(digest, file);
                    }
                    digest.update((byte) 0);
                    files++;
                }
            }
        }
        return new TreeStats(files, bytes, elapsedMs(started), 0, 0);
    }

    private static long updateFileDigest(MessageDigest digest, Path file) throws IOException {
        long total = 0L;
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                    total += read;
                }
            }
        }
        return total;
    }

    private record TreeStats(int files, long bytes, long millis, int links, int copies) {
        static final TreeStats EMPTY = new TreeStats(0, 0, 0, 0, 0);

        TreeStats plus(TreeStats other) {
            if (other == null) return this;
            return new TreeStats(
                    files + other.files,
                    bytes + other.bytes,
                    millis + other.millis,
                    links + other.links,
                    copies + other.copies
            );
        }

        String stage(String name) {
            String line = name + " files=" + files + " bytes=" + bytes + " ms=" + millis;
            if ("copy-live".equals(name)) {
                line += " links=" + links + " copies=" + copies;
            }
            return line;
        }
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String implementationVersion() {
        String version = WorkbenchSynchronizer.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }

    private static TreeStats copyTree(Path source, Path target) {
        return transferTree(source, target, false);
    }

    /** Hardlink each file onto {@code target}. A link that the filesystem refuses is a copy. */
    private static TreeStats linkTree(Path source, Path target) {
        return transferTree(source, target, true);
    }

    /**
     * One directory link from live classes to the staged base when the filesystem
     * allows it. A relative symlink stays valid after the staged tree is installed.
     * Windows falls back to a junction aimed at the final base, then to the
     * per-file link-or-copy path.
     */
    private static TreeStats linkLiveClasses(
            Path stagedBase,
            Path stagedBaseClasses,
            Path stagedLiveClasses,
            Path finalBase,
            Path finalBaseClasses,
            java.util.concurrent.atomic.AtomicBoolean baseInstalled
    ) {
        long started = System.nanoTime();
        if (!linksDisabledForTest) {
            Path parent = stagedLiveClasses.getParent();
            Path relative = null;
            if (parent != null) {
                try {
                    relative = parent.toAbsolutePath().normalize()
                            .relativize(stagedBaseClasses.toAbsolutePath().normalize());
                } catch (IllegalArgumentException differentRoots) {
                    relative = null;
                }
            }
            if (relative != null && !relative.isAbsolute() && tryDirectorySymlink(stagedLiveClasses, relative)) {
                return directoryLinkStats(stagedBaseClasses, started);
            }
            if (isWindows()) {
                replaceDirectory(stagedBase, finalBase);
                baseInstalled.set(true);
                if (tryJunction(stagedLiveClasses, finalBaseClasses)) {
                    return directoryLinkStats(finalBaseClasses, started);
                }
            }
        }
        createDirectories(stagedLiveClasses);
        Path source = Files.isDirectory(stagedBaseClasses) ? stagedBaseClasses : finalBaseClasses;
        return transferTree(source, stagedLiveClasses, true);
    }

    private static TreeStats directoryLinkStats(Path source, long startedNanos) throws IllegalStateException {
        int files = 0;
        long bytes = 0L;
        try (var walk = Files.walk(source)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files++;
                bytes += Files.size(file);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not describe linked Workbench output " + source, failure);
        }
        return new TreeStats(files, bytes, elapsedMs(startedNanos), files, 0);
    }

    private static boolean tryDirectorySymlink(Path link, Path relativeTarget) {
        try {
            if (Files.exists(link) || Files.isSymbolicLink(link)) {
                Files.deleteIfExists(link);
            }
            Files.createSymbolicLink(link, relativeTarget);
            return Files.isSymbolicLink(link);
        } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            try {
                if (Files.isSymbolicLink(link)) Files.deleteIfExists(link);
            } catch (IOException ignored) {
                // The per-file path creates the directory itself.
            }
            return false;
        }
    }

    private static boolean tryJunction(Path link, Path absoluteTarget) {
        if (!isWindows() || absoluteTarget == null || !Files.isDirectory(absoluteTarget)) return false;
        try {
            if (Files.exists(link) || Files.isSymbolicLink(link)) Files.deleteIfExists(link);
            Process process = new ProcessBuilder(
                    "cmd.exe", "/d", "/c", "mklink", "/J",
                    link.toAbsolutePath().normalize().toString(),
                    absoluteTarget.toAbsolutePath().normalize().toString()
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0 && Files.isDirectory(link);
        } catch (IOException failure) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * A directory link shares {@code base/classes}. Replace it with a private
     * copy before a live write so the owned snapshot does not change.
     */
    static boolean detachLinkedLiveClasses(Path liveClasses) {
        if (!isDirectoryLink(liveClasses)) return false;
        Path real;
        try {
            real = liveClasses.toRealPath();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read linked live classes " + liveClasses, failure);
        }
        Path parent = liveClasses.getParent();
        if (parent == null) return false;
        Path temp = parent.resolve("classes.detach-" + UUID.randomUUID());
        try {
            copyTree(real, temp);
            Files.deleteIfExists(liveClasses);
            Files.move(temp, liveClasses);
            return true;
        } catch (IOException failure) {
            deleteQuietly(temp);
            throw new IllegalStateException("Could not detach linked live classes " + liveClasses, failure);
        } catch (RuntimeException failure) {
            deleteQuietly(temp);
            throw failure;
        }
    }

    /**
     * When {@code file} sits under a linked {@code live/classes}, detach that
     * directory before the write. A path outside that link is unchanged.
     */
    public static void detachContainingLiveClasses(Path file) {
        Path cursor = file == null ? null : file.toAbsolutePath().normalize();
        while (cursor != null) {
            Path name = cursor.getFileName();
            Path parent = cursor.getParent();
            if (name != null
                    && "classes".equals(name.toString())
                    && parent != null
                    && parent.getFileName() != null
                    && "live".equals(parent.getFileName().toString())
                    && isDirectoryLink(cursor)) {
                detachLinkedLiveClasses(cursor);
                return;
            }
            cursor = parent;
        }
    }

    private static boolean isDirectoryLink(Path path) {
        if (path == null || !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isSymbolicLink(path)) return true;
        if (!isWindows() || !Files.isDirectory(path)) return false;
        try {
            return !path.toAbsolutePath().normalize().equals(path.toRealPath());
        } catch (IOException failure) {
            return false;
        }
    }

    private static TreeStats transferTree(Path source, Path target, boolean link) {
        long started = System.nanoTime();
        List<Path> files = new ArrayList<>();
        long[] bytes = {0L};
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    files.add(file);
                    bytes[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
            int links = 0;
            int copies = 0;
            if (link) {
                int[] counted = linkFiles(source, target, files);
                links = counted[0];
                copies = counted[1];
            } else {
                copyFiles(source, target, files);
            }
            return new TreeStats(files.size(), bytes[0], elapsedMs(started), links, copies);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not materialize Workbench output from " + source, failure);
        }
    }

    /**
     * Same pool as {@link #copyFiles}. Each file still prefers {@code Files.createLink}
     * and falls back to a copy. The returned pair is hardlink successes, then copies.
     */
    private static int[] linkFiles(Path source, Path target, List<Path> files) throws IOException {
        java.util.concurrent.atomic.AtomicInteger links = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger copies = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean linksRefused = new java.util.concurrent.atomic.AtomicBoolean();
        List<IoTask> tasks = new ArrayList<>();
        for (Path file : files) {
            tasks.add(() -> {
                Path destination = target.resolve(source.relativize(file).toString());
                Files.createDirectories(destination.getParent());
                if (linkOrCopy(file, destination, linksRefused)) links.incrementAndGet();
                else copies.incrementAndGet();
            });
        }
        runParallel(tasks, source);
        return new int[] {links.get(), copies.get()};
    }

    private static void copyFiles(Path source, Path target, List<Path> files) throws IOException {
        List<IoTask> tasks = new ArrayList<>();
        for (Path file : files) {
            tasks.add(() -> copyOne(source, target, file));
        }
        runParallel(tasks, source);
    }

    @FunctionalInterface
    private interface IoTask {
        void run() throws IOException;
    }

    /**
     * One thread when there is nothing to fan out. Otherwise a fixed pool of
     * {@code min(tasks, available processors)}, the same bound the copy path used.
     */
    private static void runParallel(List<IoTask> tasks, Path source) throws IOException {
        if (tasks.isEmpty()) return;
        int threads = Math.min(tasks.size(), Math.max(1, Runtime.getRuntime().availableProcessors()));
        if (threads == 1) {
            for (IoTask task : tasks) task.run();
            return;
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<?>> waiting = new ArrayList<>();
            for (IoTask task : tasks) {
                waiting.add(pool.submit(() -> {
                    try {
                        task.run();
                        return null;
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                }));
            }
            for (java.util.concurrent.Future<?> done : waiting) {
                try {
                    done.get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while copying " + source, interrupted);
                } catch (java.util.concurrent.ExecutionException failure) {
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    if (cause instanceof IOException io) throw io;
                    throw new IOException("Could not copy " + source, cause);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void copyOne(Path source, Path target, Path file) throws IOException {
        Path destination = target.resolve(source.relativize(file).toString());
        Files.createDirectories(destination.getParent());
        copyFile(file, destination);
    }

    /**
     * Test-only. When true, live materialize copies every file and does not call
     * {@code Files.createLink}. Production leaves this false. The Windows-shaped
     * repro sets it so {@code copies=} is the whole tree.
     */
    static volatile boolean linksDisabledForTest;

    static boolean linkOrCopy(Path source, Path destination) throws IOException {
        return linkOrCopy(source, destination, null);
    }

    /**
     * Prefer a hardlink. A provider that cannot link ({@code UnsupportedOperationException}
     * or {@code SecurityException}) copies this file and the rest of the tree.
     * Any other link error copies this file only and later files still try a link.
     */
    static boolean linkOrCopy(
            Path source,
            Path destination,
            java.util.concurrent.atomic.AtomicBoolean linksRefused
    ) throws IOException {
        if (linksDisabledForTest || (linksRefused != null && linksRefused.get())) {
            copyFile(source, destination);
            return false;
        }
        try {
            Files.createLink(destination, source);
            return true;
        } catch (UnsupportedOperationException | SecurityException unsupported) {
            if (linksRefused != null) linksRefused.set(true);
            copyFile(source, destination);
            return false;
        } catch (IOException failure) {
            copyFile(source, destination);
            return false;
        }
    }

    private static void copyFile(Path source, Path destination) throws IOException {
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static final long[] REPLACE_BACKOFF_MS = {100L, 300L, 1000L};

    private static void replaceDirectory(Path staged, Path target) {
        replaceDirectory(staged, target, WorkbenchSynchronizer::moveReplacing);
    }

    /**
     * Installs a staged directory. A locked delete or move is tried once, then
     * retried after 100 ms, 300 ms, and 1000 ms. If the move still fails, the tree
     * is copied to a sibling {@code target.tmp-<n>} and renamed into place.
     * A failed copy deletes that temp directory and leaves the original target
     * absent or unchanged.
     */
    static void replaceDirectory(Path staged, Path target, DirectoryMove move) {
        replaceDirectory(staged, target, move, WorkbenchSynchronizer::deleteTreeOnce, WorkbenchSynchronizer::copyDirectory, Thread::sleep);
    }

    static void replaceDirectory(Path staged, Path target, DirectoryMove move, Sleeper sleeper) {
        replaceDirectory(staged, target, move, WorkbenchSynchronizer::deleteTreeOnce, WorkbenchSynchronizer::copyDirectory, sleeper);
    }

    static void replaceDirectory(
            Path staged,
            Path target,
            DirectoryMove move,
            DirectoryDelete delete,
            Sleeper sleeper
    ) {
        replaceDirectory(staged, target, move, delete, WorkbenchSynchronizer::copyDirectory, sleeper);
    }

    static void replaceDirectory(
            Path staged,
            Path target,
            DirectoryMove move,
            DirectoryDelete delete,
            DirectoryCopy copy,
            Sleeper sleeper
    ) {
        IOException deleteFailure = retry(attempt -> delete.delete(target), sleeper);
        if (deleteFailure != null) {
            throw new IllegalStateException("Could not clean Workbench path " + target, deleteFailure);
        }
        IOException moveFailure = retry(attempt -> move.move(staged, target), sleeper);
        if (moveFailure == null) {
            return;
        }
        Path temp = tempSibling(target);
        try {
            copy.copy(staged, temp);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            deleteQuietly(temp);
            IllegalStateException wrapped = new IllegalStateException(
                    "Could not install Workbench directory " + target,
                    failure
            );
            wrapped.addSuppressed(moveFailure);
            throw wrapped;
        }
        IOException stagedDelete = retry(attempt -> delete.delete(staged), sleeper);
        if (stagedDelete != null) {
            throw new IllegalStateException("Could not clean Workbench path " + staged, stagedDelete);
        }
    }

    private static IOException retry(IoAttempt attempt, Sleeper sleeper) {
        IOException last = null;
        for (int attemptNumber = 0; attemptNumber <= REPLACE_BACKOFF_MS.length; attemptNumber++) {
            if (attemptNumber > 0) {
                pause(sleeper, REPLACE_BACKOFF_MS[attemptNumber - 1]);
            }
            try {
                attempt.run(attemptNumber);
                return null;
            } catch (IOException failure) {
                last = failure;
            }
        }
        return last;
    }

    private static void pause(Sleeper sleeper, long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Workbench directory install was interrupted.", interrupted);
        }
    }

    private static Path tempSibling(Path target) {
        Path parent = target.getParent() == null ? Path.of(".") : target.getParent();
        String name = target.getFileName() == null ? "directory" : target.getFileName().toString();
        for (int n = 1; ; n++) {
            Path candidate = parent.resolve(name + ".tmp-" + n);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    private static void deleteQuietly(Path root) {
        try {
            deleteTreeOnce(root);
        } catch (IOException ignored) {
            // The install error is the one the caller sees. The temp must not remain.
        }
    }

    @FunctionalInterface
    interface DirectoryMove {
        void move(Path source, Path target) throws IOException;
    }

    @FunctionalInterface
    interface DirectoryDelete {
        void delete(Path root) throws IOException;
    }

    @FunctionalInterface
    interface DirectoryCopy {
        void copy(Path source, Path target) throws IOException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    private interface IoAttempt {
        void run(int attemptNumber) throws IOException;
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(source) && Files.isSymbolicLink(dir)) {
                    Path destination = target.resolve(source.relativize(dir).toString());
                    if (destination.getParent() != null) Files.createDirectories(destination.getParent());
                    Files.copy(dir, destination, StandardCopyOption.REPLACE_EXISTING, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path destination = target.resolve(source.relativize(file).toString());
                if (destination.getParent() != null) Files.createDirectories(destination.getParent());
                if (Files.isSymbolicLink(file)) {
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                } else {
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void replaceFile(Path staged, Path target) {
        try {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not install Workbench file " + target, failure);
        }
    }

    private static void deleteTree(Path root) {
        try {
            deleteTreeOnce(root);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not clean Workbench path " + root, failure);
        }
    }

    private static void deleteTreeOnce(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir)) {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void createDirectories(Path... directories) {
        try {
            for (Path directory : directories) Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not create Workbench directory.", failure);
        }
    }

    private static void writeLines(Path file, List<String> lines) {
        try {
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write Workbench classpath: " + file, failure);
        }
    }

    private static void writeString(Path file, String value) {
        try {
            Files.writeString(file, value, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write Workbench synchronization file: " + file, failure);
        }
    }

    private static String tail(StringBuilder output, int maxChars) {
        int start = Math.max(0, output.length() - maxChars);
        return output.substring(start);
    }

    record SyncMetadata(List<Path> sourceRoots, List<OutputPath> outputs, List<String> dependencies) {
        SyncMetadata {
            sourceRoots = List.copyOf(sourceRoots);
            outputs = List.copyOf(outputs);
            dependencies = List.copyOf(dependencies);
        }
    }

    record OutputPath(String kind, Path path) {
        OutputPath {
            path = path.toAbsolutePath().normalize();
        }
    }

    record MavenMetadata(List<Path> sourceRoots, List<OutputPath> outputs) { }
}
