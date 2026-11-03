package tools.dscode.testengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.control.protocol.Workspace;
import tools.dscode.launcher.PickleballWorkbenchLauncher;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workbench {@code resolve-runvars} against the run profile a real scenario observed.
 * Same JVM inputs. Not a comparison of {@code resolveInto} with {@code apply}.
 */
class RunVarLaunchParityTest {
    @TempDir
    Path temp;

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void dryRunMatchesARealScenarioAcrossTheMatrix() throws Exception {
        Path outputRoot = installRunner();
        assertCell(outputRoot, "no-hooks", false, "none", Map.of(), null, profile -> {
            assertFalse(profile.contains("from-defaults"), profile);
            assertFalse(profile.contains("from-properties"), profile);
            assertFalse(profile.contains("from-file"), profile);
        });
        assertCell(outputRoot, "defaults", true, "defaults", Map.of(), null, profile -> {
            assertTrue(profile.contains("pkb_browser=from-defaults"), profile);
            assertTrue(profile.contains("pkb_hook_marker=from-defaults"), profile);
            assertFalse(profile.contains("pkb_hook_prop="), profile);
        });
        assertCell(outputRoot, "properties", true, "properties", Map.of(), null, profile -> {
            assertTrue(profile.contains("pkb_browser=from-properties"), profile);
            assertTrue(profile.contains("pkb_hook_prop=from-properties"), profile);
            assertFalse(profile.contains("pkb_hook_marker="), profile);
        });
        assertCell(outputRoot, "both", true, "both", Map.of(), null, profile -> {
            assertTrue(profile.contains("pkb_browser=from-properties"), profile);
            assertTrue(profile.contains("pkb_hook_marker=from-defaults"), profile);
            assertTrue(profile.contains("pkb_hook_prop=from-properties"), profile);
        });
        assertCell(outputRoot, "file-overrides-defaults", true, "file", Map.of(),
                "pkb_browser=from-file\npkb_name=caf\u00e9\n", profile -> {
                    assertTrue(profile.contains("pkb_browser=from-file"), profile);
                    assertTrue(profile.contains("pkb_hook_marker=from-defaults"), profile);
                    assertTrue(profile.contains("caf\u00e9"), profile);
                    assertFalse(profile.contains("pkb_browser=from-defaults"), profile);
                });
        assertCell(outputRoot, "properties-hook-overrides-file", true, "file-and-properties", Map.of(),
                "pkb_browser=from-file\npkb_hook_prop=from-file\n", profile -> {
                    assertTrue(profile.contains("pkb_browser=from-properties"), profile);
                    assertTrue(profile.contains("pkb_hook_prop=from-properties"), profile);
                    assertFalse(profile.contains("pkb_browser=from-file"), profile);
                    assertFalse(profile.contains("pkb_hook_prop=from-file"), profile);
                });
        assertCell(outputRoot, "jvm-overrides-hooks", true, "both",
                Map.of(PKB_props.PKB_BROWSER, "from-jvm"), null, profile -> {
                    assertTrue(profile.contains("pkb_browser=from-jvm"), profile);
                    assertFalse(profile.contains("pkb_browser=from-properties"), profile);
                });
        assertCell(outputRoot, "tags-and-windows-path", true, "none", Map.of(
                PKB_props.PKB_TAGS, "@launch-parity and @fast or not @slow",
                PKB_props.PKB_CONFIG_PATH, "wd-file:C:\\Users\\Dan\\My Projects\\configs",
                PKB_props.PKB_NAME, "caf\u00e9"
        ), null, profile -> {
            assertTrue(profile.contains("@launch-parity and @fast or not @slow"), profile);
            assertTrue(profile.contains("wd-file:C:\\Users\\Dan\\My Projects\\configs"), profile);
            assertTrue(profile.contains("caf\u00e9"), profile);
        });
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void savedCopyKeepsTheEditAndDoesNotReapplyHooks() throws Exception {
        Path outputRoot = installRunner();
        Path project = temp.resolve("saved");
        Files.createDirectories(project.resolve("src/test/resources"));
        Path hookFile = project.resolve("hooks.txt");
        Path workspace = Workspace.directory(project, "saved-1");
        List<String> seedProps = baseProperties(outputRoot, project, "both");
        seedProps.add("-Dpickleball.parity.hookfile=" + hookFile);
        Run seed = fork(project, java(seedProps, "tools.dscode.testengine.parity.LaunchSeedMain",
                List.of(workspace.toString())));
        assertEquals(0, seed.exit, seed.text());
        assertTrue(Files.isRegularFile(hookFile), seed.text());
        assertTrue(Files.readString(hookFile).contains("defaults"), Files.readString(hookFile));
        assertTrue(Files.readString(hookFile).contains("properties"), Files.readString(hookFile));
        Map<String, String> captured = new LinkedHashMap<>(Workspace.readRunVars(workspace));
        assertEquals("from-properties", captured.get(PKB_props.PKB_BROWSER));
        assertEquals("from-defaults", captured.get("pkb_hook_marker"));
        captured.put(PKB_props.PKB_BROWSER, "edited-browser");
        captured.put("pkb_hook_marker", "edited-marker");
        Workspace.writeRunVars(workspace, captured);
        Files.delete(hookFile);

        Path observed = project.resolve("observed.txt");
        List<String> launchProps = List.of(
                "-Dpkb_workspace=" + workspace.toAbsolutePath().normalize(),
                "-Dpkb_tags=@launch-parity",
                "-Dpickleball.parity.mode=both",
                "-Dpickleball.parity.hookfile=" + hookFile,
                "-Dpickleball.parity.out=" + observed,
                "-D" + ControlProtocol.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY + "=" + outputRoot
        );
        Run scenario = fork(project, java(launchProps, "tools.dscode.testengine.parity.LaunchObservationMain",
                List.of(observed.toString())));
        assertEquals(0, scenario.exit, scenario.text());
        assertFalse(Files.exists(hookFile), "hooks were reapplied\n" + scenario.text());
        String profile = Files.readString(observed).trim();
        assertTrue(profile.contains("pkb_browser=edited-browser"), profile);
        assertTrue(profile.contains("pkb_hook_marker=edited-marker"), profile);
        assertFalse(profile.contains("pkb_browser=from-properties"), profile);
        assertFalse(profile.contains("pkb_hook_marker=from-defaults"), profile);

        Run preview = fork(project, java(baseProperties(outputRoot, project, "both"),
                PickleballWorkbenchLauncher.class.getName(),
                List.of("resolve-runvars", project.toString())));
        assertEquals(0, preview.exit, preview.text());
        String dry = profileLine(preview.out);
        assertTrue(dry.contains("pkb_browser=from-properties"), dry);
        assertTrue(dry.contains("pkb_hook_marker=from-defaults"), dry);
        assertFalse(dry.equals(profile), "saved copy must not match a fresh hook resolve\n" + dry);
    }

    @FunctionalInterface
    private interface ProfileCheck {
        void check(String profile);
    }

    private void assertCell(
            Path outputRoot,
            String name,
            boolean withRunner,
            String mode,
            Map<String, String> extra,
            String properties,
            ProfileCheck check
    ) throws Exception {
        Path project = temp.resolve(name);
        Files.createDirectories(project.resolve("src/test/resources"));
        if (!withRunner) {
            Path runnerClass = outputRoot.resolve("tools/dscode/testengine/fixtures/ParityHookRunner.class");
            Files.deleteIfExists(runnerClass);
        } else {
            installRunner();
        }
        Path classpathProps = null;
        if (properties != null) {
            classpathProps = project.resolve("props-cp");
            Files.createDirectories(classpathProps);
            Files.writeString(classpathProps.resolve("pickleball.properties"), properties, StandardCharsets.ISO_8859_1);
        }
        Path observed = project.resolve("observed.txt");
        Path hookFile = project.resolve("hooks.txt");
        List<String> shared = baseProperties(outputRoot, project, withRunner ? mode : "none");
        if (!withRunner) {
            shared.add("-Dpkb_glue=tools.dscode.testengine.parity");
            shared.add("-Dpkb_features=classpath:launch-parity");
            shared.add("-Dpkb_tags=@launch-parity");
            shared.add("-Dpkb_parallel=1");
            shared.add("-Dpkb_loglevel=error");
            shared.add("-Dpkb_plugins=pretty");
            shared.add("-Dpkb_datapath=data");
            shared.add("-Dpkb_callpath=calls");
            shared.add("-Dpkb_componentpath=component");
            shared.add("-Dpkb_configpath=configs");
        }
        for (Map.Entry<String, String> entry : extra.entrySet()) {
            shared.add("-D" + entry.getKey() + "=" + entry.getValue());
        }
        List<String> scenarioProps = new ArrayList<>(shared);
        scenarioProps.add("-Dpickleball.parity.out=" + observed);
        if (withRunner) scenarioProps.add("-Dpickleball.parity.hookfile=" + hookFile);
        Run scenario = fork(project, java(scenarioProps, "tools.dscode.testengine.parity.LaunchObservationMain",
                List.of(observed.toString()), classpathProps));
        assertEquals(0, scenario.exit, name + "\n" + scenario.text());
        Run dry = fork(project, java(shared, PickleballWorkbenchLauncher.class.getName(),
                List.of("resolve-runvars", project.toString()), classpathProps));
        assertEquals(0, dry.exit, name + "\n" + dry.text());
        String observedProfile = Files.readString(observed, StandardCharsets.UTF_8).trim();
        String dryProfile = profileLine(dry.out);
        assertEquals(observedProfile, dryProfile, name + "\nscenario:\n" + scenario.text() + "\ndry:\n" + dry.text());
        check.check(observedProfile);
        if (!withRunner) {
            assertFalse(Files.exists(hookFile), name);
        } else if ("none".equals(mode)) {
            String hooks = Files.readString(hookFile);
            assertTrue(hooks.contains("defaults"), hooks);
            assertTrue(hooks.contains("properties"), hooks);
        }
    }

    private Path installRunner() throws Exception {
        Path source = Path.of("build/classes/java/runnerFixtures/tools/dscode/testengine/fixtures/ParityHookRunner.class");
        assertTrue(Files.isRegularFile(source), "compile runnerFixtures first: " + source);
        Path outputRoot = temp.resolve("target/test-classes");
        Path dest = outputRoot.resolve("tools/dscode/testengine/fixtures/ParityHookRunner.class");
        Files.createDirectories(dest.getParent());
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        return outputRoot.toAbsolutePath().normalize();
    }

    private static List<String> baseProperties(Path outputRoot, Path project, String mode) {
        List<String> properties = new ArrayList<>();
        properties.add("-D" + ControlProtocol.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY + "=" + outputRoot);
        properties.add("-Dpickleball.parity.mode=" + mode);
        properties.add("-Duser.dir=" + project.toAbsolutePath().normalize());
        return properties;
    }

    private static List<String> java(List<String> properties, String main, List<String> args) {
        return java(properties, main, args, null);
    }

    private static List<String> java(List<String> properties, String main, List<String> args, Path extraClasspathLast) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(properties);
        command.add("-cp");
        String output = null;
        for (String property : properties) {
            String prefix = "-D" + ControlProtocol.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY + "=";
            if (property.startsWith(prefix)) output = property.substring(prefix.length());
        }
        String classpath = System.getProperty("java.class.path");
        if (output != null) classpath = output + java.io.File.pathSeparator + classpath;
        if (extraClasspathLast != null) {
            classpath = classpath + java.io.File.pathSeparator + extraClasspathLast.toAbsolutePath().normalize();
        }
        command.add(classpath);
        command.add(main);
        command.addAll(args);
        return command;
    }

    private static String profileLine(String stdout) {
        for (String line : stdout.split("\\R")) {
            if (line.startsWith("pkb_run_profile=")) return line.substring("pkb_run_profile=".length());
        }
        throw new AssertionError("no pkb_run_profile line\n" + stdout);
    }

    private static Run fork(Path directory, List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        Process process = builder.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread stdout = pump(process.getInputStream(), out);
        Thread stderr = pump(process.getErrorStream(), err);
        boolean finished = process.waitFor(90, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        stdout.join(5_000);
        stderr.join(5_000);
        return new Run(
                finished ? process.exitValue() : -1,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8)
        );
    }

    private static Thread pump(java.io.InputStream input, ByteArrayOutputStream target) {
        Thread thread = new Thread(() -> {
            try (input) {
                input.transferTo(target);
            } catch (Exception ignored) {
                // The process ending closes the pipe.
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private record Run(int exit, String out, String err) {
        String text() {
            return out + "\n" + err;
        }
    }
}
