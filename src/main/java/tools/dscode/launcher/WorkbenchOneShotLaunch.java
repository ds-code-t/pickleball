package tools.dscode.launcher;

import tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.testengine.DynamicSuiteBootstrap;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * One launch path for Workbench one-shots ({@code help}, {@code plan}, {@code hint},
 * {@code status}, {@code query}, {@code events}, {@code workspace-*}).
 *
 * <p>A successful {@code discover} or {@code workspace-seed} writes
 * {@code classpath.cache} under the Workbench state root (the versioned
 * {@code .pickleball/v/<version>/workbench} directory when {@code current.json}
 * is complete, otherwise {@code .pickleball/workbench}). The file is one Java
 * argument-file classpath. The next one-shot is
 * {@code java -cp @classpath.cache tools.dscode.launcher.PickleballWorkbenchLauncher}.
 * No Maven or Gradle wrapper starts. A missing or stale cache falls back to the
 * project wrapper. {@code help} with no cache still uses that launcher jar and
 * {@link WorkbenchCliHelp} instead of the wrapper. {@code discover}, {@code confirm},
 * and {@code isolate} keep the wrapper when they must build or launch a worker.</p>
 */
public final class WorkbenchOneShotLaunch {
    static final String CACHE_FILE = "classpath.cache";
    static final String MAIN_CLASS = "tools.dscode.launcher.PickleballWorkbenchLauncher";
    static final Set<String> ONE_SHOTS = Set.of(
            "help", "plan", "hint", "discover-hint",
            "status", "query", "events",
            "workspace-show", "workspace-edit", "workspace-seed"
    );
    private static final List<String> OUTPUT_DIRS = List.of(
            "target/test-classes",
            "target/classes",
            "build/classes/java/test",
            "build/classes/java/main",
            "build/classes/kotlin/test",
            "build/classes/kotlin/main",
            "build/resources/test",
            "build/resources/main"
    );
    private static final List<String> BUILD_FILES = List.of(
            "pom.xml", "build.gradle", "build.gradle.kts"
    );

    private WorkbenchOneShotLaunch() {
    }

    public static boolean isOneShot(String command) {
        return command != null && ONE_SHOTS.contains(command);
    }

    public static Path cacheFile(Path project) {
        return PickleballLocalLayout.workbenchStateRoot(project).resolve(CACHE_FILE);
    }

    /**
     * Write the one-shot cache from this JVM when that classpath can already
     * load the launcher, otherwise from {@code dependency:build-classpath}
     * (Gradle: the test runtime classpath). Already-extracted controller jars
     * are appended. A failure is a warning; the caller still succeeds.
     */
    public static void capture(Path project, Appendable warnings) {
        capture(project, DynamicSuiteBootstrap.effectiveClasspathEntries(), warnings);
    }

    static void capture(Path project, List<String> snapshot, Appendable warnings) {
        if (project == null) return;
        try {
            List<String> entries = new ArrayList<>();
            List<String> synced = readWorkerClasspath(project);
            if (containsExisting(synced)) {
                entries.addAll(synced);
            } else if (usableSnapshot(snapshot)) {
                entries.addAll(snapshot);
            } else {
                entries.addAll(resolveWithWrapper(project, warnings));
            }
            entries.addAll(0, existingOutputs(project));
            for (Path jar : controllerJars(project)) entries.add(jar.toString());
            addCodeSource(entries);
            List<String> unique = existingUnique(entries);
            if (unique.isEmpty()) {
                warn(warnings, "WARNING: one-shot classpath cache was not written. No classpath entries exist.");
                return;
            }
            writeCache(project, unique);
        } catch (RuntimeException | IOException failure) {
            warn(warnings, "WARNING: one-shot classpath cache was not written. " + failure.getMessage());
        }
    }

    /** True when {@code classpath.cache} exists, every entry exists, and the build file is not newer. */
    public static boolean fresh(Path project) {
        if (project == null) return false;
        Path file = cacheFile(project);
        if (!Files.isRegularFile(file)) return false;
        List<String> entries;
        try {
            entries = readCache(file);
        } catch (IOException failure) {
            return false;
        }
        if (entries.isEmpty()) return false;
        for (String entry : entries) {
            if (!Files.exists(Path.of(entry))) return false;
        }
        try {
            long cached = Files.getLastModifiedTime(file).toMillis();
            for (String name : BUILD_FILES) {
                Path build = project.resolve(name);
                if (Files.isRegularFile(build) && Files.getLastModifiedTime(build).toMillis() > cached) {
                    return false;
                }
            }
        } catch (IOException failure) {
            return false;
        }
        return true;
    }

    /**
     * The process command for these launcher arguments. A fresh cache is
     * {@code java -cp @classpath.cache} plus the launcher class. Otherwise the
     * project wrapper, except {@code help}, which uses the launcher jar.
     */
    public static List<String> command(Path project, List<String> args) {
        List<String> launcherArgs = args == null ? List.of() : args;
        String verb = verb(launcherArgs);
        if (isOneShot(verb) && fresh(project)) {
            return fastCommand(project, launcherArgs);
        }
        if ("help".equals(verb)) {
            Optional<Path> jar = launcherJar();
            if (jar.isPresent()) return jarCommand(jar.get(), launcherArgs);
        }
        return wrapperCommand(project, launcherArgs);
    }

    public static int launch(Path project, List<String> args, PrintStream out, PrintStream err) {
        return ConsumerMavenTestRunner.run(project, command(project, args), out, err);
    }

    static List<String> fastCommand(Path project, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add("@" + cacheFile(project).toAbsolutePath().normalize());
        command.add(MAIN_CLASS);
        command.addAll(args);
        return List.copyOf(command);
    }

    static List<String> wrapperCommand(Path project, List<String> args) {
        Path root = project.toAbsolutePath().normalize();
        List<String> command = new ArrayList<>(ConsumerMavenTestRunner.wrapperLaunchPrefix(
                ConsumerMavenTestRunner.wrapperPath(root)
        ));
        String joined = String.join(" ", args);
        boolean maven = Files.isRegularFile(root.resolve("pom.xml"));
        command.add("-q");
        if (maven) {
            command.add("org.codehaus.mojo:exec-maven-plugin:3.5.0:java");
            command.add("-Dexec.mainClass=" + MAIN_CLASS);
            command.add("-Dexec.classpathScope=test");
            command.add("-Dexec.args=" + joined);
        } else {
            command.add("pickleballWorkbench");
            command.add("-PpkbArgs=" + joined);
        }
        return List.copyOf(command);
    }

    static boolean usesWrapper(List<String> command) {
        if (command == null) return false;
        for (String argument : command) {
            if (argument == null) continue;
            String name = argument.replace('\\', '/');
            if (name.endsWith("/mvnw") || name.endsWith("/mvnw.cmd") || name.endsWith("/gradlew")
                    || name.endsWith("/gradlew.bat") || name.endsWith("maven-wrapper.jar")
                    || name.endsWith("gradle-wrapper.jar") || name.contains("exec-maven-plugin")
                    || "pickleballWorkbench".equals(argument)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> jarCommand(Path jar, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-jar");
        command.add(jar.toAbsolutePath().normalize().toString());
        command.addAll(args);
        return List.copyOf(command);
    }

    private static String verb(List<String> args) {
        if (args.isEmpty() || args.getFirst() == null || args.getFirst().isBlank()) return "";
        String token = args.getFirst().trim();
        if (WorkbenchCliHelp.isHelpToken(token)) return "help";
        return token;
    }

    private static void writeCache(Path project, List<String> entries) throws IOException {
        Path file = cacheFile(project);
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(CACHE_FILE + ".tmp");
        Files.writeString(temporary, quote(String.join(java.io.File.pathSeparator, entries)) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static List<String> readCache(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return List.of();
        String classpath = unquote(text.lines().findFirst().orElse(""));
        if (classpath.isBlank()) return List.of();
        List<String> entries = new ArrayList<>();
        for (String entry : classpath.split(java.io.File.pathSeparator, -1)) {
            if (!entry.isBlank()) entries.add(entry);
        }
        return entries;
    }

    private static List<String> readWorkerClasspath(Path project) {
        Path file = PickleballLocalLayout.workbenchStateRoot(project).resolve("classpath.txt");
        if (!Files.isRegularFile(file)) return List.of();
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
        } catch (IOException failure) {
            return List.of();
        }
    }

    private static boolean containsExisting(List<String> entries) {
        for (String entry : entries) {
            if (entry != null && !entry.isBlank() && Files.exists(Path.of(entry))) return true;
        }
        return false;
    }

    private static boolean usableSnapshot(List<String> entries) {
        if (entries == null || entries.isEmpty()) return false;
        String code = codeSourcePath();
        boolean launcher = false;
        boolean exists = false;
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            Path path = Path.of(entry);
            if (!Files.exists(path)) continue;
            exists = true;
            String text = path.toAbsolutePath().normalize().toString();
            if (code != null && text.equals(code)) launcher = true;
            if (text.toLowerCase(Locale.ROOT).contains("pickleball")) launcher = true;
        }
        return exists && launcher;
    }

    private static List<String> existingOutputs(Path project) {
        List<String> found = new ArrayList<>();
        for (String relative : OUTPUT_DIRS) {
            Path path = project.resolve(relative);
            if (Files.isDirectory(path)) found.add(path.toAbsolutePath().normalize().toString());
        }
        return found;
    }

    private static void addCodeSource(List<String> entries) {
        String code = codeSourcePath();
        if (code != null) entries.add(code);
    }

    private static String codeSourcePath() {
        return launcherJar().map(path -> path.toAbsolutePath().normalize().toString()).orElseGet(() -> {
            try {
                var source = WorkbenchOneShotLaunch.class.getProtectionDomain().getCodeSource();
                if (source == null || source.getLocation() == null) return null;
                Path path = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
                return Files.exists(path) ? path.toString() : null;
            } catch (RuntimeException | java.net.URISyntaxException failure) {
                return null;
            }
        });
    }

    static Optional<Path> launcherJar() {
        try {
            var source = PickleballWorkbenchLauncher.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return Optional.empty();
            Path path = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
            if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
                return Optional.of(path);
            }
        } catch (RuntimeException | java.net.URISyntaxException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static List<String> existingUnique(List<String> entries) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            Path path = Path.of(entry).toAbsolutePath().normalize();
            if (Files.exists(path)) unique.add(path.toString());
        }
        return List.copyOf(unique);
    }

    private static List<Path> controllerJars(Path project) throws IOException {
        Path root = PickleballLocalLayout.workbenchStateRoot(project);
        List<Path> jars = new ArrayList<>();
        for (String child : List.of("controller", "lib")) {
            Path directory = root.resolve(child);
            if (!Files.isDirectory(directory)) continue;
            try (var walk = Files.walk(directory, 6)) {
                walk.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"))
                        .forEach(jars::add);
            }
        }
        return jars;
    }

    private static List<String> resolveWithWrapper(Path project, Appendable warnings) throws IOException {
        Path root = project.toAbsolutePath().normalize();
        boolean maven = Files.isRegularFile(root.resolve("pom.xml"))
                || !Files.isRegularFile(root.resolve("build.gradle"))
                && !Files.isRegularFile(root.resolve("build.gradle.kts"));
        Path output = Files.createTempFile("pickleball-oneshot-", ".classpath");
        try {
            List<String> command;
            if (maven && !Files.isRegularFile(root.resolve("build.gradle")) && !Files.isRegularFile(root.resolve("build.gradle.kts"))) {
                command = mavenClasspathCommand(root, output);
            } else if (!maven) {
                command = gradleClasspathCommand(root, output);
            } else {
                command = mavenClasspathCommand(root, output);
            }
            int exit = ConsumerMavenTestRunner.run(root, command, nullStream(), nullStream());
            if (exit != 0 || !Files.isRegularFile(output)) {
                warn(warnings, "WARNING: dependency classpath resolve exited " + exit + ". One-shots stay on the wrapper.");
                return List.of();
            }
            String text = Files.readString(output, StandardCharsets.UTF_8).trim();
            List<String> entries = new ArrayList<>();
            if (!text.isBlank()) {
                for (String entry : text.split(java.io.File.pathSeparator, -1)) {
                    if (!entry.isBlank()) entries.add(entry.trim());
                }
            }
            return entries;
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static List<String> mavenClasspathCommand(Path project, Path output) {
        List<String> command = new ArrayList<>(ConsumerMavenTestRunner.wrapperLaunchPrefix(
                ConsumerMavenTestRunner.wrapperPath(project)
        ));
        command.add("-q");
        command.add("org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath");
        command.add("-Dmdep.includeScope=test");
        command.add("-Dmdep.pathSeparator=" + java.io.File.pathSeparator);
        command.add("-Dmdep.outputFile=" + output.toAbsolutePath().normalize());
        return command;
    }

    private static List<String> gradleClasspathCommand(Path project, Path output) throws IOException {
        Path init = Files.createTempFile("pickleball-oneshot-", ".gradle");
        Files.writeString(init, gradleInitScript(), StandardCharsets.UTF_8);
        List<String> command = new ArrayList<>(ConsumerMavenTestRunner.wrapperLaunchPrefix(
                ConsumerMavenTestRunner.wrapperPath(project)
        ));
        command.add("-I");
        command.add(init.toAbsolutePath().normalize().toString());
        command.add("-Dpickleball.oneshot.projectDir=" + project.toAbsolutePath().normalize());
        command.add("-Dpickleball.oneshot.outputFile=" + output.toAbsolutePath().normalize());
        command.add("pickleballOneShotClasspath");
        command.add("--console=plain");
        command.add("-q");
        return command;
    }

    private static String gradleInitScript() {
        return """
                gradle.projectsEvaluated {
                    def selectedDir = new File(System.getProperty('pickleball.oneshot.projectDir')).canonicalFile
                    def selected = gradle.rootProject.allprojects.find { it.projectDir.canonicalFile == selectedDir }
                    if (selected == null) {
                        throw new GradleException("No Gradle project found at ${selectedDir}")
                    }
                    def sourceSets = selected.extensions.findByName('sourceSets')
                    if (sourceSets == null || sourceSets.findByName('test') == null) {
                        throw new GradleException("Selected project must expose a test source set: ${selected.path}")
                    }
                    def testSet = sourceSets.findByName('test')
                    selected.rootProject.tasks.register('pickleballOneShotClasspath') {
                        doLast {
                            def cp = testSet.runtimeClasspath.files.collect { it.canonicalPath }.join(File.pathSeparator)
                            new File(System.getProperty('pickleball.oneshot.outputFile')).setText(cp, 'UTF-8')
                        }
                    }
                }
                """;
    }

    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String unquote(String text) {
        String body = text == null ? "" : text.trim();
        if (body.length() < 2 || !body.startsWith("\"") || !body.endsWith("\"")) return body;
        String inner = body.substring(1, body.length() - 1);
        StringBuilder out = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char current = inner.charAt(i);
            if (current == '\\' && i + 1 < inner.length()) {
                out.append(inner.charAt(++i));
            } else {
                out.append(current);
            }
        }
        return out.toString();
    }

    private static void warn(Appendable warnings, String message) {
        if (warnings == null) return;
        try {
            warnings.append(message).append(System.lineSeparator());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static PrintStream nullStream() {
        return new PrintStream(java.io.OutputStream.nullOutputStream());
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path bin = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        return Files.isRegularFile(bin) ? bin.toString() : (windows ? "java.exe" : "java");
    }
}
