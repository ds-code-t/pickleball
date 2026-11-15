package io.cucumber.core.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.dolt.DoltPaths;
import tools.dscode.dolt.DoltRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltFeatureLoadHookTest {
    @TempDir
    Path project;

    @Test
    void fileLoadDoesNotHydrateWhenDoltIsOff() {
        String previous = System.getProperty("pkb_dolt");
        String previousProject = System.getProperty("pkb_dolt_project");
        try {
            System.setProperty("pkb_dolt", "false");
            System.setProperty("pkb_dolt_project", project.toString());
            DoltRuntime.reset();
            FeaturePathLoadCache.load(List.of(), () -> DoltFeatureLoadHookTest.class.getClassLoader(), List::of);
            assertFalse(Files.exists(DoltPaths.database(project)));
        } finally {
            if (previous == null) System.clearProperty("pkb_dolt");
            else System.setProperty("pkb_dolt", previous);
            if (previousProject == null) System.clearProperty("pkb_dolt_project");
            else System.setProperty("pkb_dolt_project", previousProject);
            DoltRuntime.reset();
        }
    }

    @Test
    void fileLoadHydratesWhenDoltIsOn() throws Exception {
        String previous = System.getProperty("pkb_dolt");
        String previousProject = System.getProperty("pkb_dolt_project");
        try {
            Files.createDirectories(project.resolve("features"));
            Files.writeString(project.resolve("features/hook.feature"), "Feature: Hook\n");
            System.setProperty("pkb_dolt", "true");
            System.setProperty("pkb_dolt_project", project.toString());
            DoltRuntime.reset();
            FeaturePathLoadCache.load(List.of(), () -> DoltFeatureLoadHookTest.class.getClassLoader(), List::of);
            assertTrue(DoltPaths.ready(project));
        } finally {
            if (previous == null) System.clearProperty("pkb_dolt");
            else System.setProperty("pkb_dolt", previous);
            if (previousProject == null) System.clearProperty("pkb_dolt_project");
            else System.setProperty("pkb_dolt_project", previousProject);
            DoltRuntime.reset();
        }
    }
}
