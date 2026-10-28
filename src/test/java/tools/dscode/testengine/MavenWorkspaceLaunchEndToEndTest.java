package tools.dscode.testengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner;
import tools.dscode.control.protocol.Workspace;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Maven-mode launch. Seeds the fixture with {@code WorkspaceSeedMain} on the
 * compiled test classpath, edits the workspace, then runs the command
 * {@link ConsumerMavenTestRunner} builds ({@code mvnw test} with
 * {@code -Dpkb_workspace} and selection keys only).
 */
class MavenWorkspaceLaunchEndToEndTest {
    private static final String UNIQUE_ARG = "--pkb-phase7-workspace-arg";
    private static final String CHROME_YAML = """
            constructor: CREATE_LOCAL_DRIVER
            browser: chrome
            driver:
              capabilities:
                "goog:loggingPrefs":
                  browser: ALL
                  performance: SEVERE
              options:
                args:
                  - "--pkb-phase7-workspace-arg"
                  - "--disable-gpu"
            """;

    @TempDir
    Path temp;

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void mavenTestLaunchesTheEditedWorkspace() throws Exception {
        Path published = Path.of(
                System.getProperty("user.home"),
                ".m2", "repository", "tools", "dscode", "pickleball", "2.1.18", "pickleball-2.1.18.jar"
        );
        assertTrue(Files.isRegularFile(published),
                "publishToMavenLocal tools.dscode:pickleball:2.1.18 before this test: " + published);

        Path project = project();
        Run prepared = run(project, List.of(
                project.resolve("mvnw").toString(),
                "-B",
                "test",
                "-DskipTests",
                "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath",
                "-DincludeScope=test",
                "-Dmdep.outputFile=target/cp.txt"
        ), 8);
        assertEquals(0, prepared.exit, prepared.text());
        assertTrue(Files.isDirectory(project.resolve("target/test-classes")), prepared.text());

        Run seeded = run(project, seedCommand(project), 2);
        assertEquals(0, seeded.exit, seeded.text());
        Path workspace = Workspace.directory(project, "maven-1");
        Map<String, String> captured = Workspace.readRunVars(workspace);
        assertEquals("firefox", captured.get(PKB_props.PKB_BROWSER), seeded.text());
        assertEquals("from-hook", captured.get("pkb_hook_marker"), seeded.text());
        assertEquals(Workspace.PARALLEL_AUTO, captured.get(PKB_props.PKB_PARALLEL));
        assertTrue(Files.isRegularFile(Workspace.configsDirectory(workspace).resolve("firefox.yaml")));

        Map<String, String> edited = new LinkedHashMap<>(captured);
        edited.put(PKB_props.PKB_BROWSER, "chrome");
        edited.put("pkb_hook_marker", "edited-by-agent");
        Workspace.writeRunVars(workspace, edited);
        Workspace.atomicWriteString(Workspace.configsDirectory(workspace).resolve("chrome.yaml"), CHROME_YAML);

        Map<String, String> selection = new LinkedHashMap<>();
        selection.put(PKB_props.PKB_TAGS, "@phase7");
        selection.put(PKB_props.PKB_RUN_ID, "maven-1");
        selection.put(PKB_props.PKB_AGENT_ID, "agent-maven");
        List<String> command = ConsumerMavenTestRunner.command(project, workspace, selection);
        assertTrue(command.stream().anyMatch(argument -> argument.endsWith("mvnw") || argument.contains("/mvnw")), command.toString());
        assertTrue(command.contains("test"), command.toString());
        assertTrue(command.stream().anyMatch(argument -> argument.startsWith("-Dpkb_workspace=")), command.toString());
        assertTrue(command.contains("-Dpkb_tags=@phase7"), command.toString());
        assertTrue(command.contains("-Dpkb_run_purpose=workbench-discover"), command.toString());
        for (String argument : command) {
            assertFalse(argument.contains("pkb_browser"), argument);
            assertFalse(argument.contains("pkb_hook_marker"), argument);
            assertFalse(argument.contains("CHROME_HEADLESS"), argument);
            assertFalse(argument.contains("pkb_overriderunvars"), argument);
            assertFalse(argument.contains("pkb_runvars"), argument);
        }

        Run launched = run(project, command, 4);
        String evidence = launched.text() + "\n" + surefireText(project) + "\n" + probeFile(project);
        String probe = probeFile(project);
        assertEquals(0, launched.exit, evidence);
        assertTrue(probe.contains("PKB_PROBE browser=chrome"), evidence);
        assertTrue(probe.contains("PKB_PROBE resolvedBrowser=chrome"), evidence);
        assertTrue(probe.contains("PKB_PROBE sysBrowser=chrome"), evidence);
        assertTrue(probe.contains(UNIQUE_ARG), evidence);
        assertTrue(probe.contains("--disable-gpu"), evidence);
        assertFalse(probe.contains("--headless"), probe);
        assertFalse(probe.contains("CHROME_HEADLESS"), probe);
        assertTrue(probe.contains("ALL"), probe);
        assertTrue(probe.contains("SEVERE"), probe);
        assertTrue(probe.contains("PKB_PROBE hook=edited-by-agent"), evidence);
        assertFalse(probe.contains("from-hook"), probe);
        assertFalse(probe.contains("hook-ran-at-launch"), probe);
        int parallel = Integer.parseInt(line(evidence, "PKB_PROBE parallel=").substring("PKB_PROBE parallel=".length()).trim());
        assertTrue(parallel >= 2, evidence);
        assertEquals(Workspace.PARALLEL_AUTO, Workspace.readRunVars(workspace).get(PKB_props.PKB_PARALLEL));
        assertEquals("chrome", Workspace.readRunVars(workspace).get(PKB_props.PKB_BROWSER));
        assertEquals("edited-by-agent", Workspace.readRunVars(workspace).get("pkb_hook_marker"));
    }

    private Path project() throws Exception {
        Path project = temp.resolve("consumer");
        Files.createDirectories(project.resolve("src/test/java/com/example/probe"));
        Files.createDirectories(project.resolve("src/test/resources/features"));
        Files.createDirectories(project.resolve("configs"));
        Files.createDirectories(project.resolve("data"));
        Files.createDirectories(project.resolve("calls"));
        Files.createDirectories(project.resolve("component"));
        Files.createDirectories(project.resolve(".mvn/wrapper"));
        Path repo = Path.of("maven-consumer-project");
        assertTrue(Files.isRegularFile(repo.resolve("mvnw")), "missing " + repo.toAbsolutePath());
        copyExecutable(repo.resolve("mvnw"), project.resolve("mvnw"));
        Files.copy(repo.resolve("mvnw.cmd"), project.resolve("mvnw.cmd"));
        Files.copy(
                repo.resolve(".mvn/wrapper/maven-wrapper.properties"),
                project.resolve(".mvn/wrapper/maven-wrapper.properties")
        );
        Files.writeString(project.resolve("pom.xml"), POM);
        Files.writeString(project.resolve("src/test/java/com/example/probe/WorkspaceProbeTests.java"), RUNNER);
        Files.writeString(project.resolve("src/test/java/com/example/probe/ProbeSteps.java"), STEPS);
        Files.writeString(project.resolve("src/test/resources/features/probe.feature"), FEATURE);
        Files.writeString(project.resolve("configs/firefox.yaml"), """
                constructor: CREATE_LOCAL_DRIVER
                browser: firefox
                driver:
                  options:
                    args:
                      - "--project-firefox-arg"
                """);
        return project;
    }

    private static void copyExecutable(Path source, Path destination) throws Exception {
        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
        Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(destination));
        permissions.add(PosixFilePermission.OWNER_EXECUTE);
        permissions.add(PosixFilePermission.GROUP_EXECUTE);
        permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(destination, permissions);
    }

    private static List<String> seedCommand(Path project) throws Exception {
        String dependencies = Files.readString(project.resolve("target/cp.txt")).trim();
        String classpath = project.resolve("target/test-classes").toAbsolutePath() + java.io.File.pathSeparator + dependencies;
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(classpath);
        command.add("tools.dscode.testengine.WorkspaceSeedMain");
        command.add("--project=" + project.toAbsolutePath().normalize());
        command.add("--run=maven-1");
        return command;
    }

    private static String surefireText(Path project) throws Exception {
        Path reports = project.resolve("target/surefire-reports");
        if (!Files.isDirectory(reports)) return "";
        StringBuilder text = new StringBuilder();
        try (Stream<Path> walk = Files.walk(reports)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList()) {
                if (file.toString().endsWith(".class")) continue;
                text.append('\n').append(Files.readString(file));
            }
        }
        return text.toString();
    }

    private static String probeFile(Path project) throws Exception {
        Path file = project.resolve("target/pkb-probe.txt");
        return Files.isRegularFile(file) ? Files.readString(file) : "";
    }

    private static String line(String text, String prefix) {
        for (String row : text.split("\\R")) {
            if (row.startsWith(prefix)) return row;
        }
        return "";
    }

    private static Run run(Path directory, List<String> command, int minutes) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process process = builder.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread stdout = new Thread(() -> transfer(process.getInputStream(), out), "maven-e2e-stdout");
        Thread stderr = new Thread(() -> transfer(process.getErrorStream(), err), "maven-e2e-stderr");
        stdout.start();
        stderr.start();
        boolean finished = process.waitFor(minutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            stdout.join(2000);
            stderr.join(2000);
            throw new AssertionError("timed out: " + command + "\n" + out + "\n" + err);
        }
        stdout.join(5000);
        stderr.join(5000);
        return new Run(process.exitValue(), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static void transfer(java.io.InputStream in, ByteArrayOutputStream out) {
        try {
            in.transferTo(out);
        } catch (java.io.IOException ignored) {
            // The process is being destroyed.
        }
    }

    private record Run(int exit, String out, String err) {
        String text() {
            return out + "\n" + err;
        }
    }

    private static final String POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>pickleball-workspace-maven-e2e</artifactId>
              <version>1.0.0-SNAPSHOT</version>
              <properties>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                <maven.compiler.release>21</maven.compiler.release>
                <tagArgLine></tagArgLine>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>tools.dscode</groupId>
                  <artifactId>pickleball</artifactId>
                  <version>2.1.18</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.13.0</version>
                    <configuration>
                      <release>${maven.compiler.release}</release>
                    </configuration>
                  </plugin>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.2</version>
                    <configuration>
                      <argLine>${tagArgLine}</argLine>
                      <redirectTestOutputToFile>false</redirectTestOutputToFile>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    private static final String RUNNER = """
            package com.example.probe;

            import tools.dscode.control.protocol.Workspace;
            import tools.dscode.testengine.PKB_props;
            import tools.dscode.testengine.PickleballRunner;

            public final class WorkspaceProbeTests extends PickleballRunner {
                @Override
                public void globalTestDefaults() {
                    values.put(PKB_props.PKB_GLUE, "com.example.probe");
                    values.put(PKB_props.PKB_FEATURES, "classpath:features");
                    values.put(PKB_props.PKB_DATA_PATH, "data");
                    values.put(PKB_props.PKB_CALL_PATH, "calls");
                    values.put(PKB_props.PKB_COMPONENT_PATH, "component");
                    values.put(PKB_props.PKB_CONFIG_PATH, "wd-file:configs");
                    values.put(PKB_props.PKB_PARALLEL, Workspace.PARALLEL_AUTO);
                    values.put(PKB_props.PKB_BROWSER, "firefox");
                }

                @Override
                public void globalTestProperties() {
                    values.put(PKB_props.PKB_BROWSER, "firefox");
                    values.put(PKB_props.PKB_PARALLEL, Workspace.PARALLEL_AUTO);
                    values.put("pkb_hook_marker", "from-hook");
                }
            }
            """;

    private static final String STEPS = """
            package com.example.probe;

            import com.fasterxml.jackson.databind.JsonNode;
            import com.fasterxml.jackson.databind.node.ObjectNode;
            import io.cucumber.java.en.Given;
            import org.openqa.selenium.chrome.ChromeOptions;
            import tools.dscode.common.driver.DriverConstruction;
            import tools.dscode.common.mappings.GlobalMappings;
            import tools.dscode.common.mappings.ParsingMap;
            import tools.dscode.testengine.PKB_props;
            import tools.dscode.testengine.PickleballRunner;

            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.util.ArrayList;
            import java.util.List;

            public final class ProbeSteps {
                @Given("the maven workspace probe reports driver options")
                public void report() throws Exception {
                    PickleballRunner runner = PickleballRunner.getInstance();
                    String browserKey = runner.get(PKB_props.PKB_BROWSER);
                    JsonNode configsNode = GlobalMappings.GLOBALS.getRoot().get(ParsingMap.CONFIGS_MAP_ROOT);
                    ObjectNode configs = configsNode instanceof ObjectNode object ? object : null;
                    ObjectNode config = find(configs, browserKey);
                    if (config == null) {
                        throw new IllegalStateException("missing config " + browserKey + " keys=" + fieldNames(configs));
                    }
                    ChromeOptions options = DriverConstruction.buildChromeOptions(
                            DriverConstruction.getDriverSection(config),
                            config,
                            false
                    );
                    String text = "PKB_PROBE browser=" + browserKey + "\\n"
                            + "PKB_PROBE resolvedBrowser=" + DriverConstruction.resolveBrowserName(config) + "\\n"
                            + "PKB_PROBE chromeOptions=" + options.getCapability("goog:chromeOptions") + "\\n"
                            + "PKB_PROBE logging=" + options.getCapability("goog:loggingPrefs") + "\\n"
                            + "PKB_PROBE hook=" + runner.get("pkb_hook_marker") + "\\n"
                            + "PKB_PROBE parallel=" + runner.get(PKB_props.PKB_PARALLEL) + "\\n"
                            + "PKB_PROBE sysBrowser=" + System.getProperty(PKB_props.PKB_BROWSER) + "\\n";
                    Path out = Path.of("target");
                    Files.createDirectories(out);
                    Files.writeString(out.resolve("pkb-probe.txt"), text, StandardCharsets.UTF_8);
                    System.out.print(text);
                }

                private static ObjectNode find(ObjectNode configs, String browserKey) {
                    if (configs == null || browserKey == null) return null;
                    JsonNode exact = configs.get(browserKey);
                    if (exact instanceof ObjectNode object) return object;
                    var names = configs.fieldNames();
                    while (names.hasNext()) {
                        String name = names.next();
                        if (name.equalsIgnoreCase(browserKey) && configs.get(name) instanceof ObjectNode object) {
                            return object;
                        }
                    }
                    return null;
                }

                private static List<String> fieldNames(ObjectNode configs) {
                    List<String> names = new ArrayList<>();
                    if (configs == null) return names;
                    configs.fieldNames().forEachRemaining(names::add);
                    return names;
                }
            }
            """;

    private static final String FEATURE = """
            @phase7
            Feature: Maven workspace launch

              Scenario: edited workspace is what Maven runs
                Given the maven workspace probe reports driver options
            """;
}
