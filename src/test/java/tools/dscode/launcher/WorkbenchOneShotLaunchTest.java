package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.PickleballLocalLayout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchOneShotLaunchTest {
    @TempDir
    Path tempDir;

    @Test
    void freshCacheLaunchesWithJavaClasspathAndDoesNotStartMaven() throws Exception {
        Path project = projectWithWrapper();
        WorkbenchOneShotLaunch.capture(project, List.of(), new StringBuilder());
        assertTrue(Files.isRegularFile(project.resolve("wrapper-args")), "build-classpath should run when no snapshot exists");
        String wrapper = Files.readString(project.resolve("wrapper-args"));
        assertTrue(wrapper.contains("build-classpath"), wrapper);
        List<String> stored = WorkbenchOneShotLaunch.readCache(WorkbenchOneShotLaunch.cacheFile(project));
        assertTrue(stored.stream().anyMatch(entry -> entry.endsWith("dependency.jar")), stored.toString());
        assertTrue(stored.stream().anyMatch(entry -> entry.endsWith("pickleball-workbench.jar")), stored.toString());

        Files.delete(project.resolve("wrapper-args"));
        List<String> command = WorkbenchOneShotLaunch.command(project, List.of("hint", "--json"));
        assertFalse(WorkbenchOneShotLaunch.usesWrapper(command), command.toString());
        assertTrue(command.contains("-cp"), command.toString());
        assertTrue(command.stream().anyMatch(arg -> arg.startsWith("@") && arg.endsWith("classpath.cache")), command.toString());
        assertTrue(command.contains(WorkbenchOneShotLaunch.MAIN_CLASS), command.toString());
        assertTrue(command.contains("hint") && command.contains("--json"), command.toString());
        assertFalse(Files.isRegularFile(project.resolve("wrapper-args")));
    }

    @Test
    void missingOrStaleCacheUsesJavaClasspathWhenItResolves() throws Exception {
        Path project = projectWithWrapper();
        List<String> missing = WorkbenchOneShotLaunch.command(project, List.of("query", "runs"));
        assertFalse(WorkbenchOneShotLaunch.usesWrapper(missing), missing.toString());
        assertTrue(missing.contains("-cp"), missing.toString());
        assertTrue(missing.contains(WorkbenchOneShotLaunch.MAIN_CLASS), missing.toString());
        assertFalse(missing.stream().anyMatch(arg -> arg.contains("exec-maven-plugin")), missing.toString());

        Path cache = WorkbenchOneShotLaunch.cacheFile(project);
        Path gone = project.resolve("missing.jar");
        Files.writeString(cache, WorkbenchOneShotLaunch.quote(gone.toString()) + "\n");
        List<String> broken = WorkbenchOneShotLaunch.command(project, List.of("status"));
        assertFalse(WorkbenchOneShotLaunch.usesWrapper(broken), broken.toString());
        assertTrue(broken.contains("-cp"), broken.toString());

        Files.writeString(cache, WorkbenchOneShotLaunch.quote(project.resolve("dependency.jar").toAbsolutePath().normalize().toString()) + "\n");
        Files.setLastModifiedTime(project.resolve("pom.xml"), FileTime.from(Instant.now().plusSeconds(120)));
        assertFalse(WorkbenchOneShotLaunch.fresh(project));
        List<String> stale = WorkbenchOneShotLaunch.command(project, List.of("plan"));
        assertFalse(WorkbenchOneShotLaunch.usesWrapper(stale), stale.toString());
        assertTrue(stale.contains("-cp"), stale.toString());
    }

    @Test
    void unresolvableClasspathStillFallsBackToTheWrapper() throws Exception {
        Path project = tempDir.resolve("no-classpath");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Path mvnw = project.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\nexit 1\n");
        mvnw.toFile().setExecutable(true);
        List<String> missing = WorkbenchOneShotLaunch.command(project, List.of("query", "runs"));
        assertTrue(WorkbenchOneShotLaunch.usesWrapper(missing), missing.toString());
        assertTrue(missing.stream().anyMatch(arg -> arg.contains("exec-maven-plugin")), missing.toString());
    }

    @Test
    void oneShotWithoutAFreshCacheKeepsJavaExit2() throws Exception {
        Path project = tempDir.resolve("exit-two");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Files.writeString(project.resolve("real.classpath"), System.getProperty("java.class.path"));
        Path mvnw = project.resolve("mvnw");
        Files.writeString(mvnw, """
                #!/bin/sh
                echo "$@" >> "$PWD/mvn-args"
                out=""
                for arg in "$@"; do
                  case "$arg" in
                    -Dmdep.outputFile=*) out="${arg#-Dmdep.outputFile=}" ;;
                  esac
                done
                if [ -n "$out" ]; then
                  cp "$PWD/real.classpath" "$out"
                  exit 0
                fi
                exit 1
                """);
        mvnw.toFile().setExecutable(true);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        List<String> command = WorkbenchOneShotLaunch.command(project, List.of("status", "--step-timeout=nope"));
        assertFalse(command.stream().anyMatch(arg -> arg.contains("exec-maven-plugin")), command.toString());
        assertTrue(command.contains("-cp"), command.toString());
        int exit = WorkbenchOneShotLaunch.launch(
                project,
                List.of("status", "--step-timeout=nope"),
                new java.io.PrintStream(out),
                new java.io.PrintStream(err)
        );
        String text = err.toString(StandardCharsets.UTF_8) + out.toString(StandardCharsets.UTF_8);
        assertEquals(2, exit, text);
        assertTrue(text.contains("error:"), text);
        assertTrue(text.contains("fix:"), text);
        String maven = Files.readString(project.resolve("mvn-args"));
        assertFalse(maven.contains("exec-maven-plugin"), maven);
    }

    @Test
    void helpWithoutACacheUsesTheLauncherJarNotMavenWhenTheJarIsTheCodeSource() {
        if (WorkbenchOneShotLaunch.launcherJar().isEmpty()) return;
        List<String> command = WorkbenchOneShotLaunch.command(tempDir.resolve("no-cache"), List.of("help"));
        assertFalse(WorkbenchOneShotLaunch.usesWrapper(command), command.toString());
        assertTrue(command.contains("-jar"), command.toString());
        assertTrue(command.contains("help"), command.toString());
    }

    @Test
    void cachedHelpFinishesWithoutMaven() throws Exception {
        Path project = tempDir.resolve("timed");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Path mvnw = project.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\necho maven >> \"$PWD/maven-ran\"\nexit 9\n");
        mvnw.toFile().setExecutable(true);
        StringBuilder warnings = new StringBuilder();
        WorkbenchOneShotLaunch.capture(project, warnings);
        assertTrue(warnings.toString().isBlank() || !warnings.toString().contains("not written"), warnings.toString());
        assertTrue(WorkbenchOneShotLaunch.fresh(project), WorkbenchOneShotLaunch.cacheFile(project).toString());
        long started = System.nanoTime();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int exit = WorkbenchOneShotLaunch.launch(
                project,
                List.of("help"),
                new java.io.PrintStream(out),
                new java.io.PrintStream(err)
        );
        long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(exit == 0, err.toString(StandardCharsets.UTF_8) + text);
        assertTrue(text.contains("Pickleball Workbench"), text);
        assertTrue(millis < 8_000, "help via classpath cache took " + millis + "ms");
        assertFalse(Files.isRegularFile(project.resolve("maven-ran")), "maven wrapper must not start");
    }

    private Path projectWithWrapper() throws Exception {
        Path project = tempDir.resolve("consumer");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Files.write(project.resolve("dependency.jar"), new byte[]{1});
        Path controller = PickleballLocalLayout.workbenchStateRoot(project)
                .resolve("controller")
                .resolve("sha")
                .resolve("pickleball-workbench.jar");
        Files.createDirectories(controller.getParent());
        Files.write(controller, new byte[]{2});
        Path mvnw = project.resolve("mvnw");
        Files.writeString(mvnw, """
                #!/bin/sh
                echo "$@" >> "$PWD/wrapper-args"
                out=""
                for arg in "$@"; do
                  case "$arg" in
                    -Dmdep.outputFile=*) out="${arg#-Dmdep.outputFile=}" ;;
                  esac
                done
                if [ -n "$out" ]; then
                  printf '%s' "$PWD/dependency.jar" > "$out"
                fi
                exit 0
                """);
        mvnw.toFile().setExecutable(true);
        return project;
    }
}
