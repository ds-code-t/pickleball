package tools.dscode.workbench.sync;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Persisted synchronization provenance for one selected consumer project/module. */
public record WorkbenchManifest(
        int schemaVersion,
        String projectRoot,
        String projectType,
        String buildTool,
        List<String> sourceRoots,
        List<OutputRoot> outputRoots,
        List<OutputMapping> outputMappings,
        String liveOutput,
        String synchronizedAt,
        String fingerprint,
        List<String> dependencyClasspath,
        String pickleballVersion,
        String javaVersion,
        String javaHome
) {
    public static final int CURRENT_SCHEMA = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    public WorkbenchManifest {
        sourceRoots = List.copyOf(sourceRoots);
        outputRoots = List.copyOf(outputRoots);
        outputMappings = List.copyOf(outputMappings);
        dependencyClasspath = List.copyOf(dependencyClasspath);
    }

    public static WorkbenchManifest read(Path projectRoot) {
        Path file = workbenchRoot(projectRoot).resolve("manifest.json");
        try {
            WorkbenchManifest manifest = JSON.readValue(file.toFile(), WorkbenchManifest.class);
            if (manifest.schemaVersion() != CURRENT_SCHEMA) {
                throw new IllegalStateException(
                        "Unsupported Workbench manifest schema " + manifest.schemaVersion()
                );
            }
            return manifest;
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not read Workbench manifest. Run sync first: " + file,
                    failure
            );
        }
    }

    void write(Path file) {
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), this);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write Workbench manifest: " + file, failure);
        }
    }

    public Path projectPath() {
        return Path.of(projectRoot).toAbsolutePath().normalize();
    }

    public Path liveOutputPath() {
        return Path.of(liveOutput).toAbsolutePath().normalize();
    }

    public static Path workbenchRoot(Path projectRoot) {
        return projectRoot.toAbsolutePath().normalize().resolve(".pickleball").resolve("workbench");
    }

    public record OutputRoot(String kind, String path) { }

    public record OutputMapping(String kind, String originalOutput, String baseOutput, String liveOutput) { }
}
