package tools.dscode.studio.workspace;

import java.nio.file.Files;
import java.nio.file.Path;

public final class WorkspaceService {

    public WorkspaceInfo open(Path requestedRoot) {
        Path root = requestedRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Workspace directory does not exist: " + root);
        }

        boolean maven = Files.isRegularFile(root.resolve("pom.xml"));
        boolean gradle = Files.isRegularFile(root.resolve("build.gradle"))
                || Files.isRegularFile(root.resolve("build.gradle.kts"))
                || Files.isRegularFile(root.resolve("settings.gradle"))
                || Files.isRegularFile(root.resolve("settings.gradle.kts"));

        Path fileName = root.getFileName();
        String name = fileName == null ? root.toString() : fileName.toString();

        return new WorkspaceInfo(
                root,
                name,
                maven,
                gradle,
                Files.exists(root.resolve(".git"))
        );
    }
}
