package tools.dscode.workbench.sync;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkbenchSyncPlannerTest {

    @Test
    void missingPreviousOrSnapshotRequiresFullCompile() {
        WorkbenchSyncInputs inputs = inputs("java", "res", "build", "dep");
        assertEquals(WorkbenchSyncMode.FULL, WorkbenchSyncPlanner.decide(null, inputs, true));
        assertEquals(WorkbenchSyncMode.FULL, WorkbenchSyncPlanner.decide(manifest(inputs), inputs, false));
    }

    @Test
    void blankInputFingerprintsRequireFullCompile() {
        WorkbenchManifest previous = manifest(new WorkbenchSyncInputs("", "", "", "", List.of()));
        WorkbenchSyncInputs current = inputs("java", "res", "build", "dep");
        assertEquals(WorkbenchSyncMode.FULL, WorkbenchSyncPlanner.decide(previous, current, true));
    }

    @Test
    void matchingInputFingerprintsSkipTheWrapper() {
        WorkbenchSyncInputs inputs = inputs("java", "res", "build", "dep");
        assertEquals(WorkbenchSyncMode.SKIPPED, WorkbenchSyncPlanner.decide(manifest(inputs), inputs, true));
    }

    @Test
    void resourceOnlyChangeSelectsResourcesOnly() {
        WorkbenchManifest previous = manifest(inputs("java", "res", "build", "dep"));
        WorkbenchSyncInputs current = inputs("java", "res-changed", "build", "dep");
        assertEquals(WorkbenchSyncMode.RESOURCES_ONLY, WorkbenchSyncPlanner.decide(previous, current, true));
    }

    @Test
    void javaBuildOrDependencyChangeSelectsFullCompile() {
        WorkbenchManifest previous = manifest(inputs("java", "res", "build", "dep"));
        assertEquals(
                WorkbenchSyncMode.FULL,
                WorkbenchSyncPlanner.decide(previous, inputs("java-changed", "res", "build", "dep"), true)
        );
        assertEquals(
                WorkbenchSyncMode.FULL,
                WorkbenchSyncPlanner.decide(previous, inputs("java", "res", "build-changed", "dep"), true)
        );
        assertEquals(
                WorkbenchSyncMode.FULL,
                WorkbenchSyncPlanner.decide(previous, inputs("java", "res", "build", "dep-changed"), true)
        );
    }

    @Test
    void javaChangeWinsOverResourceChange() {
        WorkbenchManifest previous = manifest(inputs("java", "res", "build", "dep"));
        WorkbenchSyncInputs current = inputs("java-changed", "res-changed", "build", "dep");
        assertEquals(WorkbenchSyncMode.FULL, WorkbenchSyncPlanner.decide(previous, current, true));
    }

    private static WorkbenchSyncInputs inputs(String java, String resource, String build, String dependency) {
        return new WorkbenchSyncInputs(java, resource, build, dependency, List.of(Path.of("src/test/java")));
    }

    private static WorkbenchManifest manifest(WorkbenchSyncInputs inputs) {
        return new WorkbenchManifest(
                WorkbenchManifest.CURRENT_SCHEMA,
                "/project",
                "MAVEN",
                "mvn",
                List.of("/project/src/test/java"),
                List.of(new WorkbenchManifest.OutputRoot("TEST", "/project/target/test-classes")),
                List.of(),
                "/project/.pickleball/workbench/live/classes",
                "2026-08-25T00:00:00Z",
                "output-fp",
                List.of("/repo/dep.jar"),
                "2.1.9",
                "21",
                "/usr/lib/jvm",
                WorkbenchSyncMode.FULL.name(),
                inputs.javaFingerprint(),
                inputs.resourceFingerprint(),
                inputs.buildFingerprint(),
                inputs.dependencyFingerprint()
        );
    }
}
