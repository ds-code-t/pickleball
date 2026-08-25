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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
        createDirectories(stagedBaseClasses, stagedLiveClasses);

        boolean copiedAnyOutput = false;
        for (OutputPath output : metadata.outputs()) {
            if (Files.isDirectory(output.path())) {
                copyTree(output.path(), stagedBaseClasses);
                copiedAnyOutput = true;
            }
        }
        if (!copiedAnyOutput) {
            throw new IllegalStateException(
                    "Build completed but no compiled/resource output directories were found for " + project.root()
            );
        }

        copyTree(stagedBaseClasses, stagedLiveClasses);
        createDirectories(
                stagedLive.resolve("candidate-source"),
                stagedLive.resolve("generated-java"),
                stagedLive.resolve("generated-classes")
        );

        List<String> dependencies = distinctExisting(metadata.dependencies());
        WorkbenchSyncInputs stored = WorkbenchSyncInputs.capture(
                project, metadata.sourceRoots(), dependencies
        );
        String fingerprint = fingerprint(stagedBaseClasses, dependencies);
        Path finalBaseClasses = stateRoot.resolve("base").resolve("classes").toAbsolutePath().normalize();
        Path finalLiveClasses = stateRoot.resolve("live").resolve("classes").toAbsolutePath().normalize();

        List<String> classpathEntries = new ArrayList<>(dependencies.size() + 1);
        classpathEntries.add(finalLiveClasses.toString());
        classpathEntries.addAll(dependencies);
        assertNoBaseClasspath(stateRoot, classpathEntries);

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
        Path stagedClasspath = staging.resolve("classpath.txt");
        manifest.write(stagedManifest);
        writeLines(stagedClasspath, classpathEntries);

        createDirectories(stateRoot, stateRoot.resolve("sessions"), stateRoot.resolve("evidence"), stateRoot.resolve("logs"));
        replaceDirectory(stagedBase, stateRoot.resolve("base"));
        replaceDirectory(stagedLive, stateRoot.resolve("live"));
        replaceFile(stagedManifest, stateRoot.resolve("manifest.json"));
        replaceFile(stagedClasspath, stateRoot.resolve("classpath.txt"));
        return manifest;
    }

    public static List<String> readWorkerClasspath(Path projectRoot) {
        Path stateRoot = WorkbenchManifest.workbenchRoot(projectRoot);
        Path file = stateRoot.resolve("classpath.txt");
        try {
            List<String> entries = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
            if (entries.isEmpty()) {
                throw new IllegalStateException("Workbench classpath is empty: " + file);
            }
            assertNoBaseClasspath(stateRoot, entries);
            Path expectedLive = stateRoot.resolve("live").resolve("classes").toAbsolutePath().normalize();
            if (!Path.of(entries.getFirst()).toAbsolutePath().normalize().equals(expectedLive)) {
                throw new IllegalStateException(
                        "Workbench classpath must start with the one effective live output: " + expectedLive
                );
            }
            return entries;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read Workbench classpath. Run sync first: " + file, failure);
        }
    }

    private WorkbenchManifest skip(
            WorkbenchManifest previous,
            WorkbenchSyncInputs inputs,
            Path stateRoot
    ) {
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

    private static List<String> executableCommand(Path launcher, List<String> args) {
        String name = launcher.getFileName() == null ? launcher.toString() : launcher.getFileName().toString();
        List<String> command = new ArrayList<>();
        if (WorkbenchProject.isWindows() && (name.endsWith(".cmd") || name.endsWith(".bat"))) {
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

    private static String fingerprint(Path classes, List<String> dependencies) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDirectoryDigest(digest, classes);
            for (String dependency : dependencies) {
                Path path = Path.of(dependency).toAbsolutePath().normalize();
                digest.update(path.toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                if (Files.isRegularFile(path)) {
                    updateFileDigest(digest, path);
                } else if (Files.isDirectory(path)) {
                    updateDirectoryDigest(digest, path);
                }
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new IllegalStateException("Could not fingerprint Workbench synchronization output.", failure);
        }
    }

    private static void updateDirectoryDigest(MessageDigest digest, Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                digest.update(root.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                updateFileDigest(digest, file);
                digest.update((byte) 0);
            }
        }
    }

    private static void updateFileDigest(MessageDigest digest, Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
    }

    private static String implementationVersion() {
        String version = WorkbenchSynchronizer.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }

    private static void copyTree(Path source, Path target) {
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path destination = target.resolve(source.relativize(file).toString());
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            throw new IllegalStateException("Could not materialize Workbench output from " + source, failure);
        }
    }

    private static void replaceDirectory(Path staged, Path target) {
        deleteTree(target);
        try {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not install Workbench directory " + target, failure);
        }
    }

    private static void replaceFile(Path staged, Path target) {
        try {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not install Workbench file " + target, failure);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            throw new IllegalStateException("Could not clean Workbench path " + root, failure);
        }
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
