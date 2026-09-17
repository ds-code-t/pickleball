package tools.dscode.workbench.player;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveBufferSidecarTest {
    @TempDir
    Path project;

    @Test
    void writesSidecarOnlyWhenLiveGherkinDiffersFromDisk() throws Exception {
        Path feature = project.resolve("src/test/resources/features/login.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, "Feature: Login\nScenario: Valid\n  Given stay\n");
        LiveBufferSidecar.publish(project, feature, "Feature: Login\nScenario: Valid\n  Given stay\n");
        assertFalse(Files.exists(LiveBufferSidecar.path(project)));

        LiveBufferSidecar.publish(project, feature, "Feature: Login\nScenario: Valid\n  Given stay\n  And extra\n");
        Path sidecar = LiveBufferSidecar.path(project);
        assertTrue(Files.isRegularFile(sidecar));
        assertTrue(Files.readString(sidecar).contains("And extra"));

        LiveBufferSidecar.publish(project, feature, "Feature: Login\nScenario: Valid\n  Given stay\n");
        assertFalse(Files.exists(sidecar));

        LiveBufferSidecar.publish(project, feature, "Feature: Login\nScenario: Valid\n  Given stay\n  And extra\n");
        assertTrue(Files.isRegularFile(sidecar));
        LiveBufferSidecar.publish(project, null, "Feature: Demo\n");
        assertFalse(Files.exists(sidecar));
    }
}
