package tools.dscode.workbench.sync;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Persisted synchronization provenance for one selected consumer project/module. */
@JsonIgnoreProperties(ignoreUnknown = true)
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
        String javaHome,
        String syncMode,
        String javaInputFingerprint,
        String resourceInputFingerprint,
        String buildInputFingerprint,
        String dependencyInputFingerprint
) {
    public static final int CURRENT_SCHEMA = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    public WorkbenchManifest {
        sourceRoots = List.copyOf(sourceRoots == null ? List.of() : sourceRoots);
        outputRoots = List.copyOf(outputRoots == null ? List.of() : outputRoots);
        outputMappings = List.copyOf(outputMappings == null ? List.of() : outputMappings);
        dependencyClasspath = List.copyOf(dependencyClasspath == null ? List.of() : dependencyClasspath);
        syncMode = syncMode == null || syncMode.isBlank() ? WorkbenchSyncMode.FULL.name() : syncMode;
        javaInputFingerprint = javaInputFingerprint == null ? "" : javaInputFingerprint;
        resourceInputFingerprint = resourceInputFingerprint == null ? "" : resourceInputFingerprint;
        buildInputFingerprint = buildInputFingerprint == null ? "" : buildInputFingerprint;
        dependencyInputFingerprint = dependencyInputFingerprint == null ? "" : dependencyInputFingerprint;
    }

    public boolean hasInputFingerprints() {
        return !javaInputFingerprint.isBlank()
                && !resourceInputFingerprint.isBlank()
                && !buildInputFingerprint.isBlank()
                && !dependencyInputFingerprint.isBlank();
    }

    public WorkbenchManifest withSkip(String synchronizedAt, WorkbenchSyncInputs inputs) {
        return new WorkbenchManifest(
                schemaVersion,
                projectRoot,
                projectType,
                buildTool,
                sourceRoots,
                outputRoots,
                outputMappings,
                liveOutput,
                synchronizedAt,
                fingerprint,
                dependencyClasspath,
                pickleballVersion,
                javaVersion,
                javaHome,
                WorkbenchSyncMode.SKIPPED.name(),
                inputs.javaFingerprint(),
                inputs.resourceFingerprint(),
                inputs.buildFingerprint(),
                inputs.dependencyFingerprint()
        );
    }

    static WorkbenchManifest readIfPresent(Path stateRoot) {
        Path file = stateRoot.resolve("manifest.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            WorkbenchManifest manifest = JSON.readValue(file.toFile(), WorkbenchManifest.class);
            if (manifest.schemaVersion() != CURRENT_SCHEMA) return null;
            return manifest;
        } catch (IOException ignored) {
            return null;
        }
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
