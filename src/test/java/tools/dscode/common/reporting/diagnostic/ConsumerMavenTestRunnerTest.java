package tools.dscode.common.reporting.diagnostic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import tools.dscode.testengine.DynamicSuiteEngine;
import tools.dscode.testengine.PKB_props;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerMavenTestRunnerTest {
    @TempDir
    Path project;

    @Test
    void wrapperPrefersTheOsScriptThenTheWrapperJar() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path script = project.resolve(windows ? "mvnw.cmd" : "mvnw");
        Files.writeString(script, windows ? "@echo off\r\n" : "#!/bin/sh\n");
        Path workspace = project.resolve(".pickleball/runs/run-1/workspace");
        List<String> command = ConsumerMavenTestRunner.command(project, workspace, Map.of(PKB_props.PKB_TAGS, "@one"));
        if (windows) {
            assertEquals(List.of("cmd.exe", "/d", "/c", script.toString()), command.subList(0, 4));
        } else {
            assertEquals(script.toString(), command.getFirst());
        }
        assertTrue(command.contains("test"));

        Files.delete(script);
        Path jar = project.resolve(".mvn/wrapper/maven-wrapper.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar");
        List<String> jarCommand = ConsumerMavenTestRunner.command(project, workspace, Map.of(PKB_props.PKB_TAGS, "@one"));
        assertEquals("-jar", jarCommand.get(1));
        assertEquals(jar.toString(), jarCommand.get(2));

        Files.delete(jar);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ConsumerMavenTestRunner.wrapper(project)
        );
        assertTrue(failure.getMessage().contains("mvnw"));
        assertTrue(failure.getMessage().contains("machine-wide"));
    }

    @Test
    void gradleWrapperIsUsedForAGradleProjectAndTheJarWhenTheScriptIsAbsent() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'java' }\n");
        Path jar = project.resolve("gradle/wrapper/gradle-wrapper.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar");

        Path workspace = project.resolve(".pickleball/runs/run-1/workspace");
        List<String> command = ConsumerMavenTestRunner.command(project, workspace, Map.of(PKB_props.PKB_TAGS, "@one"));
        assertEquals("-jar", command.get(1));
        assertEquals(jar.toString(), command.get(2));
        assertTrue(command.contains("test"));
        assertTrue(command.stream().anyMatch(item -> item.startsWith("-Dpkb_workspace=")));
        assertTrue(command.contains("-Dpkb_tags=@one"));
        assertFalse(command.stream().anyMatch(item -> item.startsWith("-Dpkb_runvars=")));

        Path script = project.resolve(windows ? "gradlew.bat" : "gradlew");
        Files.writeString(script, windows ? "@echo off\r\n" : "#!/bin/sh\n");
        List<String> scriptCommand = ConsumerMavenTestRunner.confirmCommand(
                project, workspace, Map.of(PKB_props.PKB_TAGS, "@one"));
        if (windows) {
            assertEquals(List.of("cmd.exe", "/d", "/c", script.toString()), scriptCommand.subList(0, 4));
        } else {
            assertEquals(script.toString(), scriptCommand.getFirst());
        }
        assertTrue(scriptCommand.contains("-Dpkb_run_purpose=workbench-confirm"));
        assertTrue(scriptCommand.stream().noneMatch(item -> item.startsWith("-Dsurefire.includeJUnit5Engines")));
    }

    @Test
    void mavenCommandIsQuietAndOfflineOnlyAfterAPreviousTestCompile() throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("mvnw"), "#!/bin/sh\n");
        Path workspace = project.resolve(".pickleball/runs/run-1/workspace");
        List<String> fresh = ConsumerMavenTestRunner.command(project, workspace, Map.of(PKB_props.PKB_TAGS, "@one"));
        assertTrue(fresh.contains("-q"));
        assertFalse(fresh.contains("-o"));
        assertTrue(fresh.contains("test"));

        Files.createDirectories(project.resolve("target/test-classes"));
        List<String> again = ConsumerMavenTestRunner.command(project, workspace, Map.of(PKB_props.PKB_TAGS, "@one"));
        assertTrue(again.contains("-o"));
        assertTrue(again.contains("-q"));
        assertFalse(again.contains("--offline"));
        int offline = again.indexOf("-o");
        int goal = again.indexOf("test");
        assertTrue(offline < goal);
    }

    @Test
    void mavenCommandSkipsPlainJUnitWhileTheConsumerPomDoesNot() throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Path script = project.resolve("mvnw");
        Files.writeString(script, "#!/bin/sh\n");
        Path workspace = project.resolve(".pickleball/runs/run-1/workspace");
        List<String> command = ConsumerMavenTestRunner.command(project, workspace, Map.of(PKB_props.PKB_TAGS, "@smoke"));
        assertTrue(command.contains(ConsumerMavenTestRunner.SUREFIRE_CUCUMBER_ENGINE));
        assertTrue(command.contains(ConsumerMavenTestRunner.PLATFORM_CUCUMBER_ENGINE));

        String pom = Files.readString(Path.of("maven-consumer-project/pom.xml"));
        assertFalse(pom.contains("includeJUnit5Engines"));
        assertFalse(pom.contains("junit.platform.discovery.includeEngines"));
    }

    @Test
    void featurePathsStayOutOfRunVarsAndNarrowOnlyTheTopLevel() throws Exception {
        String features = "wd-file:/tmp/Business Exception/a.feature,wd-file:C:/proj/Business Exception/b.feature";
        String embedded = ConsumerMavenTestRunner.embedFeaturePaths(
                "pkb_tags=@smoke, pkb_features=classpath:features",
                features
        );
        var parsed = PKB_props.parseAssignments(embedded);
        assertEquals("@smoke", parsed.get("pkb_tags"));
        assertEquals("classpath:features", parsed.get(PKB_props.PKB_FEATURES));
        assertEquals("pkb_tags=@smoke", ConsumerMavenTestRunner.embedFeaturePaths("pkb_tags=@smoke", " "));
        assertNull(ConsumerMavenTestRunner.topLevelFeaturesArgument(" "));

        String argument = ConsumerMavenTestRunner.topLevelFeaturesArgument(features);
        assertEquals("-D" + DynamicSuiteEngine.TOP_LEVEL_FEATURES_PROPERTY + "=" + features, argument);
        assertTrue(argument.contains("Business Exception"));
        assertTrue(argument.contains("C:/proj/Business Exception/b.feature"));

        Map<String, String> suite = new LinkedHashMap<>();
        suite.put(PKB_props.PKB_FEATURES, "classpath:features");
        suite.put("cucumber.features", "classpath:features");
        Map<String, String> overlay = DynamicSuiteEngine.overlayTopLevelFeatures(suite, features);
        assertEquals("classpath:features", suite.get(PKB_props.PKB_FEATURES));
        assertEquals("classpath:features", suite.get("cucumber.features"));
        assertEquals("classpath:features", overlay.get(PKB_props.PKB_FEATURES));
        assertEquals(features, overlay.get("cucumber.features"));
        assertTrue(suite == DynamicSuiteEngine.overlayTopLevelFeatures(suite, "  "));

        Path list = Files.createTempDirectory("top-features").resolve("list.txt");
        Files.writeString(list, features);
        Map<String, String> fromFile = DynamicSuiteEngine.overlayTopLevelFeatures(suite, "@" + list.toAbsolutePath());
        assertEquals(features, fromFile.get("cucumber.features"));
        assertEquals("classpath:features", suite.get("cucumber.features"));
    }

    @Test
    void multiFileFeaturePathsStayOneAssignmentAfterQuoteStripping() {
        String features = "wd-file:C:/proj/first.feature,wd-file:C:/proj/second.feature";
        String bare = "pkb_tags=@smoke, pkb_features=" + features;
        IllegalArgumentException bareFailure = assertThrows(
                IllegalArgumentException.class,
                () -> PKB_props.parseAssignments(bare)
        );
        assertTrue(bareFailure.getMessage().contains("Invalid profile assignment"), bareFailure.getMessage());
        assertTrue(bareFailure.getMessage().contains("wd-file:C:/proj/second.feature"), bareFailure.getMessage());

        String embedded = ConsumerMavenTestRunner.embedFeaturePaths(
                "pkb_tags=@smoke, pkb_features=classpath:features",
                features
        );
        String stripped = embedded.replace("\"", "").replace("'", "");
        var parsed = PKB_props.parseAssignments(stripped);
        assertEquals("@smoke", parsed.get("pkb_tags"));
        assertEquals("classpath:features", parsed.get(PKB_props.PKB_FEATURES));
        assertEquals(features, ConsumerMavenTestRunner.topLevelFeaturesArgument(features)
                .substring(("-D" + DynamicSuiteEngine.TOP_LEVEL_FEATURES_PROPERTY + "=").length()));

        var preserved = PKB_props.parseAssignments("pkb_name=C:\\data\\, pkb_browser=chrome");
        assertEquals("C:\\data\\", preserved.get("pkb_name"));
        assertEquals("chrome", preserved.get("pkb_browser"));

        Map<String, String> roundTrip = new LinkedHashMap<>();
        roundTrip.put("pkb_browser", "chrome");
        roundTrip.put("pkb_name", "  Bob's \"QA, phase 2; retry\" = green <orders #1,3> C:\\results  ");
        String serialized = PKB_props.serializeRunVars(roundTrip);
        assertEquals(roundTrip, PKB_props.parseAssignments(serialized));
    }

    @Test
    void aDirectoryThatIsBothMavenAndGradleIsRejected() throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'java' }\n");
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ConsumerMavenTestRunner.wrapper(project)
        );
        assertTrue(failure.getMessage().contains("one Maven or Gradle"));
        assertTrue(failure.getMessage().contains("machine-wide"));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void noTimeoutLetsTheProcessTreeExitOnItsOwn() throws Exception {
        Path classes = compileSleeper(project);
        Path pids = project.resolve("pids.txt");
        int exit = ConsumerMavenTestRunner.inheritIoLauncher().run(
                sleeperCommand(classes, pids, 400),
                project,
                System.out,
                System.err
        );
        assertEquals(0, exit);
        assertFalse(anyAlive(readPids(pids)));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void interruptWhileWaitingKillsTheProcessTree() throws Exception {
        Path classes = compileSleeper(project);
        Path pids = project.resolve("pids.txt");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                ConsumerMavenTestRunner.inheritIoLauncher().run(
                        sleeperCommand(classes, pids, 120_000),
                        project,
                        System.out,
                        System.err
                );
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        }, "pickleball-tree-wait");
        waiter.start();
        List<Long> tree = awaitPids(pids);
        waiter.interrupt();
        waiter.join(15_000);
        assertFalse(waiter.isAlive(), "waiter did not leave waitFor");
        assertTrue(thrown.get() instanceof InterruptedException, String.valueOf(thrown.get()));
        assertFalse(anyAlive(tree), "pids still alive: " + tree);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void wrapperOutputIsCopiedOntoTheStreamsTheCallerPassed() throws Exception {
        Path message = project.resolve("message.txt");
        Files.writeString(message, "C:\\Users\\Dan She'll\\body.html\n");
        Path script = project.resolve("speak.sh");
        Files.writeString(script, """
                #!/bin/sh
                printf '%s\\n' 'Scenario: Pend case NEW UI # C:/Program Files/Pickleball/page.feature:12'
                cat "$1" >&2
                """);
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int exit = ConsumerMavenTestRunner.inheritIoLauncher().run(
                List.of("/bin/sh", script.toString(), message.toString()),
                project,
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, exit);
        String out = outBytes.toString(StandardCharsets.UTF_8);
        String err = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("Pend case NEW UI"), out);
        assertTrue(out.contains("C:/Program Files/Pickleball/page.feature:12"), out);
        assertTrue(err.contains("C:\\Users\\Dan She'll\\body.html"), err);
    }

    private static List<String> sleeperCommand(Path classes, Path pids, long sleepMs) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(
                java,
                "-cp",
                classes.toString(),
                "TreeSleeper",
                classes.toString(),
                pids.toString(),
                Long.toString(sleepMs)
        );
    }

    private static Path compileSleeper(Path directory) throws Exception {
        Path source = directory.resolve("TreeSleeper.java");
        Files.writeString(source, """
                import java.nio.file.Files;
                import java.nio.file.Path;
                public class TreeSleeper {
                    public static void main(String[] args) throws Exception {
                        if (args.length > 0 && "leaf".equals(args[0])) {
                            Thread.sleep(Long.parseLong(args[1]));
                            return;
                        }
                        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                        Process leaf = new ProcessBuilder(
                                javaBin, "-cp", args[0], "TreeSleeper", "leaf", args[2]
                        ).start();
                        Files.writeString(Path.of(args[1]), ProcessHandle.current().pid() + "\\n" + leaf.pid() + "\\n");
                        Thread.sleep(Long.parseLong(args[2]));
                        leaf.destroyForcibly();
                        leaf.waitFor();
                    }
                }
                """);
        Process compile = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "javac").toString(),
                source.toString()
        ).directory(directory.toFile()).inheritIO().start();
        assertEquals(0, compile.waitFor());
        return directory;
    }

    private static List<Long> awaitPids(Path file) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (Files.isRegularFile(file)) {
                List<Long> pids = readPids(file);
                if (pids.size() >= 2) return pids;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("process tree did not publish pids");
    }

    private static List<Long> readPids(Path file) throws Exception {
        return Files.readString(file).lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(Long::parseLong)
                .toList();
    }

    private static boolean anyAlive(List<Long> pids) {
        for (Long pid : pids) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) return true;
        }
        return false;
    }

    @Test
    void offlineResolutionFailureTriggersOneOnlineRetryCommand() {
        String surefire = "[ERROR] Cannot access central (https://repo.maven.apache.org/maven2) in offline mode and the artifact "
                + "org.apache.maven.plugins:maven-surefire-plugin:jar:3.5.2 has not been downloaded from it before.";
        assertTrue(ConsumerMavenTestRunner.offlineResolutionFailure(surefire));
        assertTrue(ConsumerMavenTestRunner.offlineResolutionFailure("artifact x has not been downloaded from it before."));
        assertFalse(ConsumerMavenTestRunner.offlineResolutionFailure("[ERROR] Tests run: 1, Failures: 1"));
        assertFalse(ConsumerMavenTestRunner.offlineResolutionFailure(null));

        List<String> maven = List.of("/p/mvnw", "-o", "-q", "test", "-Dpkb_tags=@one");
        assertTrue(ConsumerMavenTestRunner.hasOfflineFlag(maven));
        assertEquals(List.of("/p/mvnw", "-q", "test", "-Dpkb_tags=@one"), ConsumerMavenTestRunner.withoutOffline(maven));
        List<String> gradle = List.of("/p/gradlew", "--offline", "test");
        assertTrue(ConsumerMavenTestRunner.hasOfflineFlag(gradle));
        assertEquals(List.of("/p/gradlew", "test"), ConsumerMavenTestRunner.withoutOffline(gradle));
        assertFalse(ConsumerMavenTestRunner.hasOfflineFlag(List.of("/p/mvnw", "-q", "test")));
    }
}
