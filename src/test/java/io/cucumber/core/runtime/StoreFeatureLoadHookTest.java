package io.cucumber.core.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.StorePaths;
import tools.dscode.store.StoreRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreFeatureLoadHookTest {
    @TempDir
    Path project;

    @Test
    void fileLoadDoesNotHydrateWhenDoltIsOff() {
        String previous = System.getProperty("pkb_store");
        String previousProject = System.getProperty("pkb_store_project");
        try {
            System.setProperty("pkb_store", "false");
            System.setProperty("pkb_store_project", project.toString());
            StoreRuntime.reset();
            FeaturePathLoadCache.load(List.of(), () -> StoreFeatureLoadHookTest.class.getClassLoader(), List::of);
            assertFalse(Files.exists(StorePaths.database(project)));
        } finally {
            if (previous == null) System.clearProperty("pkb_store");
            else System.setProperty("pkb_store", previous);
            if (previousProject == null) System.clearProperty("pkb_store_project");
            else System.setProperty("pkb_store_project", previousProject);
            StoreRuntime.reset();
        }
    }

    @Test
    void fileLoadHydratesWhenDoltIsOn() throws Exception {
        String previous = System.getProperty("pkb_store");
        String previousProject = System.getProperty("pkb_store_project");
        try {
            Files.createDirectories(project.resolve("features"));
            Files.writeString(project.resolve("features/hook.feature"), "Feature: Hook\n");
            System.setProperty("pkb_store", "true");
            System.setProperty("pkb_store_project", project.toString());
            StoreRuntime.reset();
            FeaturePathLoadCache.load(List.of(), () -> StoreFeatureLoadHookTest.class.getClassLoader(), List::of);
            assertTrue(StorePaths.ready(project));
        } finally {
            if (previous == null) System.clearProperty("pkb_store");
            else System.setProperty("pkb_store", previous);
            if (previousProject == null) System.clearProperty("pkb_store_project");
            else System.setProperty("pkb_store_project", previousProject);
            StoreRuntime.reset();
        }
    }
}
