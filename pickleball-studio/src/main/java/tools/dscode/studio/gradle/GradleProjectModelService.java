package tools.dscode.studio.gradle;

import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.model.GradleTask;
import org.gradle.tooling.model.UnsupportedMethodException;
import org.gradle.tooling.model.build.BuildEnvironment;
import org.gradle.tooling.model.idea.BasicIdeaProject;
import org.gradle.tooling.model.idea.IdeaContentRoot;
import org.gradle.tooling.model.idea.IdeaModule;
import org.gradle.tooling.model.idea.IdeaSourceDirectory;
import tools.dscode.studio.workspace.WorkspaceInfo;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GradleProjectModelService {
    private final WorkspaceInfo workspace;
    private final GradleToolingConnectionFactory connections;

    public GradleProjectModelService(WorkspaceInfo workspace) {
        this(workspace, new GradleToolingConnectionFactory());
    }

    GradleProjectModelService(
            WorkspaceInfo workspace,
            GradleToolingConnectionFactory connections
    ) {
        this.workspace = workspace;
        this.connections = connections;
    }

    public GradleWorkspaceModel model() {
        requireGradleProject();

        try (ProjectConnection connection = connections.connect(workspace.root())) {
            BuildEnvironment environment = connection.getModel(BuildEnvironment.class);
            GradleProject rootProject = connection.getModel(GradleProject.class);
            BasicIdeaProject ideaProject = connection.getModel(BasicIdeaProject.class);

            Map<String, List<GradleSourceDirectoryInfo>> sources = sourceDirectories(ideaProject);
            List<GradleProjectInfo> projects = new ArrayList<>();
            collectProjects(rootProject, sources, projects);
            projects.sort(Comparator.comparing(GradleProjectInfo::path));

            return new GradleWorkspaceModel(
                    environment.getGradle().getGradleVersion(),
                    normalize(environment.getJava().getJavaHome()),
                    normalize(environment.getGradle().getGradleUserHome()),
                    rootProject.getPath(),
                    projects
            );
        } catch (GradleConnectionException error) {
            throw new IllegalStateException(
                    "Unable to read Gradle project model for " + workspace.root() + ": " + error.getMessage(),
                    error
            );
        }
    }

    public List<GradleTaskInfo> tasks(String requestedProjectPath) {
        requireGradleProject();
        String projectPath = normalizeProjectPath(requestedProjectPath);

        try (ProjectConnection connection = connections.connect(workspace.root())) {
            GradleProject rootProject = connection.getModel(GradleProject.class);
            GradleProject project = rootProject.findByPath(projectPath);
            if (project == null) {
                throw new IllegalArgumentException("Unknown Gradle project path: " + projectPath);
            }

            return project.getTasks().stream()
                    .map(this::taskInfo)
                    .sorted(Comparator.comparing(GradleTaskInfo::path))
                    .toList();
        } catch (GradleConnectionException error) {
            throw new IllegalStateException(
                    "Unable to read Gradle tasks for " + workspace.root() + ": " + error.getMessage(),
                    error
            );
        }
    }

    private void collectProjects(
            GradleProject project,
            Map<String, List<GradleSourceDirectoryInfo>> sources,
            List<GradleProjectInfo> result
    ) {
        File buildScript = project.getBuildScript().getSourceFile();
        result.add(new GradleProjectInfo(
                project.getPath(),
                project.getName(),
                project.getDescription(),
                workspacePath(project.getProjectDirectory()),
                workspacePath(project.getBuildDirectory()),
                workspacePath(buildScript),
                project.getTasks().size(),
                sources.getOrDefault(project.getPath(), List.of())
        ));

        for (GradleProject child : project.getChildren()) {
            collectProjects(child, sources, result);
        }
    }

    private Map<String, List<GradleSourceDirectoryInfo>> sourceDirectories(BasicIdeaProject ideaProject) {
        Map<String, List<GradleSourceDirectoryInfo>> result = new HashMap<>();

        for (IdeaModule module : ideaProject.getModules()) {
            Set<GradleSourceDirectoryInfo> directories = new LinkedHashSet<>();
            for (IdeaContentRoot contentRoot : module.getContentRoots()) {
                addSources(directories, contentRoot.getSourceDirectories(), "SOURCE");
                addSources(directories, contentRoot.getTestDirectories(), "TEST_SOURCE");

                try {
                    addSources(directories, contentRoot.getResourceDirectories(), "RESOURCE");
                    addSources(directories, contentRoot.getTestResourceDirectories(), "TEST_RESOURCE");
                } catch (UnsupportedMethodException ignored) {
                    // Gradle versions before 4.7 do not expose resource directories.
                }

                for (File excluded : contentRoot.getExcludeDirectories()) {
                    directories.add(new GradleSourceDirectoryInfo(
                            workspacePath(excluded),
                            "EXCLUDED",
                            false
                    ));
                }
            }

            List<GradleSourceDirectoryInfo> sorted = directories.stream()
                    .sorted(Comparator
                            .comparing(GradleSourceDirectoryInfo::kind)
                            .thenComparing(GradleSourceDirectoryInfo::path))
                    .toList();

            result.put(module.getGradleProject().getPath(), sorted);
        }

        return result;
    }

    private void addSources(
            Set<GradleSourceDirectoryInfo> result,
            Iterable<? extends IdeaSourceDirectory> directories,
            String kind
    ) {
        for (IdeaSourceDirectory directory : directories) {
            result.add(new GradleSourceDirectoryInfo(
                    workspacePath(directory.getDirectory()),
                    kind,
                    directory.isGenerated()
            ));
        }
    }

    private GradleTaskInfo taskInfo(GradleTask task) {
        return new GradleTaskInfo(
                task.getPath(),
                task.getName(),
                task.getGroup(),
                task.getDescription(),
                task.isPublic()
        );
    }

    private void requireGradleProject() {
        if (!workspace.gradleProject()) {
            throw new IllegalArgumentException("Workspace is not a Gradle project: " + workspace.root());
        }
    }

    private static String normalizeProjectPath(String requested) {
        if (requested == null || requested.isBlank() || ":".equals(requested)) {
            return ":";
        }
        return requested.startsWith(":") ? requested : ":" + requested;
    }

    private String workspacePath(File file) {
        if (file == null) {
            return null;
        }

        Path absolute = file.toPath().toAbsolutePath().normalize();
        Path root = workspace.root().toAbsolutePath().normalize();
        String value = absolute.startsWith(root)
                ? root.relativize(absolute).toString()
                : absolute.toString();

        return value.isEmpty() ? "." : value.replace('\\', '/');
    }

    private static String normalize(File file) {
        return file == null ? null : file.toPath().toAbsolutePath().normalize().toString();
    }
}
