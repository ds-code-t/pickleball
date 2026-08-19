package tools.dscode.workbench.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchGradleSynchronizerIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void synchronizesGradleThroughWrapperWithoutToolingApi() throws Exception {
        Path fixture = tempDir.resolve("gradle-consumer");
        Files.createDirectories(fixture);
        Files.writeString(fixture.resolve("settings.gradle"), "rootProject.name = 'workbench-sync-fixture'\n");
        Files.writeString(fixture.resolve("build.gradle"), "plugins { id 'java' }\n");

        write(fixture.resolve("src/main/java/example/MainValue.java"), """
                package example;
                public final class MainValue { public static String value() { return "main"; } }
                """);
        write(fixture.resolve("src/test/java/example/TestValue.java"), """
                package example;
                public final class TestValue { public static String value() { return MainValue.value(); } }
                """);
        write(fixture.resolve("src/main/resources/precedence.txt"), "main\n");
        write(fixture.resolve("src/test/resources/precedence.txt"), "test\n");
        copyGradleWrapper(fixture);

        WorkbenchManifest manifest = new WorkbenchSynchronizer().sync(fixture);
        Path stateRoot = WorkbenchManifest.workbenchRoot(fixture);
        Path live = stateRoot.resolve("live/classes").toAbsolutePath().normalize();

        assertEquals("GRADLE", manifest.projectType());
        assertTrue(Files.isRegularFile(live.resolve("example/MainValue.class")));
        assertTrue(Files.isRegularFile(live.resolve("example/TestValue.class")));
        assertEquals("test\n", Files.readString(live.resolve("precedence.txt")));

        List<String> classpath = WorkbenchSynchronizer.readWorkerClasspath(fixture);
        assertEquals(live.toString(), classpath.getFirst());
        Path base = stateRoot.resolve("base").toAbsolutePath().normalize();
        assertFalse(classpath.stream()
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .anyMatch(path -> path.startsWith(base)));
    }

    private static void copyGradleWrapper(Path fixture) throws Exception {
        Path repository = repositoryRoot();
        copy(repository.resolve("gradlew"), fixture.resolve("gradlew"));
        copy(repository.resolve("gradlew.bat"), fixture.resolve("gradlew.bat"));
        copy(repository.resolve("gradle/wrapper/gradle-wrapper.jar"), fixture.resolve("gradle/wrapper/gradle-wrapper.jar"));
        copy(repository.resolve("gradle/wrapper/gradle-wrapper.properties"), fixture.resolve("gradle/wrapper/gradle-wrapper.properties"));
        if (!WorkbenchProject.isWindows()) fixture.resolve("gradlew").toFile().setExecutable(true);
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("gradlew"))
                    && Files.isRegularFile(current.resolve("gradle/wrapper/gradle-wrapper.jar"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository Gradle wrapper from " + System.getProperty("user.dir"));
    }

    private static void copy(Path source, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }
}
