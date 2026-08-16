package tools.dscode.studio.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.studio.workspace.WorkspaceInfo;
import tools.dscode.studio.workspace.WorkspaceService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleProjectModelServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void readsProjectHierarchySourcesAndTasksThroughToolingApi() throws Exception {
        Files.writeString(tempDir.resolve("settings.gradle"), """
                rootProject.name = 'studio-model-fixture'
                include 'app'
                """);
        Files.writeString(tempDir.resolve("build.gradle"), """
                allprojects {
                    description = "project " + path
                }
                """);

        Path app = tempDir.resolve("app");
        Files.createDirectories(app.resolve("src/main/java"));
        Files.createDirectories(app.resolve("src/test/java"));
        Files.writeString(app.resolve("build.gradle"), """
                plugins {
                    id 'java'
                }

                tasks.register('studioHello') {
                    group = 'studio'
                    description = 'Studio model fixture task'
                }
                """);

        WorkspaceInfo workspace = new WorkspaceService().open(tempDir);
        GradleProjectModelService service = new GradleProjectModelService(
                workspace,
                new GradleToolingConnectionFactory(testGradleHome())
        );

        GradleWorkspaceModel model = service.model();

        assertEquals(":", model.rootProjectPath());
        assertTrue(model.gradleVersion() != null && !model.gradleVersion().isBlank());
        assertEquals(List.of(":", ":app"), model.projects().stream().map(GradleProjectInfo::path).toList());

        GradleProjectInfo appProject = model.projects().stream()
                .filter(project -> ":app".equals(project.path()))
                .findFirst()
                .orElseThrow();

        assertEquals("app", appProject.projectDirectory());
        assertTrue(
                appProject.sourceDirectories().stream()
                        .anyMatch(source -> "SOURCE".equals(source.kind())
                                && "app/src/main/java".equals(source.path())),
                appProject.sourceDirectories().toString()
        );
        assertTrue(
                appProject.sourceDirectories().stream()
                        .anyMatch(source -> "TEST_SOURCE".equals(source.kind())
                                && "app/src/test/java".equals(source.path())),
                appProject.sourceDirectories().toString()
        );

        List<GradleTaskInfo> tasks = service.tasks(":app");
        GradleTaskInfo hello = tasks.stream()
                .filter(task -> ":app:studioHello".equals(task.path()))
                .findFirst()
                .orElseThrow();

        assertEquals("studio", hello.group());
        assertEquals("Studio model fixture task", hello.description());
    }

    private static Path testGradleHome() {
        String value = System.getProperty("pickleball.studio.test.gradle.home");
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Test Gradle installation was not provided");
        }
        return Path.of(value);
    }
}
