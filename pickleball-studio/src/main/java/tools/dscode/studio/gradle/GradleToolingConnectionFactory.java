package tools.dscode.studio.gradle;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

import java.nio.file.Path;

final class GradleToolingConnectionFactory {
    private final Path gradleInstallation;

    GradleToolingConnectionFactory() {
        this(null);
    }

    GradleToolingConnectionFactory(Path gradleInstallation) {
        this.gradleInstallation = gradleInstallation;
    }

    ProjectConnection connect(Path projectDirectory) {
        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(projectDirectory.toFile());

        if (gradleInstallation != null) {
            connector.useInstallation(gradleInstallation.toFile());
        }

        return connector.connect();
    }
}
