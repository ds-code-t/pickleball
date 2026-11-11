package io.cucumber.core.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.dalton.DaltonPaths;
import tools.dscode.dalton.DaltonRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaltonFeatureLoadHookTest {
    @TempDir
    Path project;

    @Test
    void fileLoadDoesNotHydrateWhenDaltonIsOff() {
        String previous = System.getProperty("pkb_dalton");
        String previousProject = System.getProperty("pkb_dalton_project");
        try {
            System.setProperty("pkb_dalton", "false");
            System.setProperty("pkb_dalton_project", project.toString());
            DaltonRuntime.reset();
            FeaturePathLoadCache.load(List.of(), () -> DaltonFeatureLoadHookTest.class.getClassLoader(), List::of);
            assertFalse(Files.exists(DaltonPaths.database(project)));
        } finally {
            if (previous == null) System.clearProperty("pkb_dalton");
            else System.setProperty("pkb_dalton", previous);
            if (previousProject == null) System.clearProperty("pkb_dalton_project");
            else System.setProperty("pkb_dalton_project", previousProject);
            DaltonRuntime.reset();
        }
    }

    @Test
    void fileLoadHydratesWhenDaltonIsOn() throws Exception {
        String previous = System.getProperty("pkb_dalton");
        String previousProject = System.getProperty("pkb_dalton_project");
        try {
            Files.createDirectories(project.resolve("features"));
            Files.writeString(project.resolve("features/hook.feature"), "Feature: Hook\n");
            System.setProperty("pkb_dalton", "true");
            System.setProperty("pkb_dalton_project", project.toString());
            DaltonRuntime.reset();
            FeaturePathLoadCache.load(List.of(), () -> DaltonFeatureLoadHookTest.class.getClassLoader(), List::of);
            assertTrue(Files.isRegularFile(DaltonPaths.database(project)));
        } finally {
            if (previous == null) System.clearProperty("pkb_dalton");
            else System.setProperty("pkb_dalton", previous);
            if (previousProject == null) System.clearProperty("pkb_dalton_project");
            else System.setProperty("pkb_dalton_project", previousProject);
            DaltonRuntime.reset();
        }
    }
}
