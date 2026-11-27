package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.adapter.cucumber.RowFeatureSupplier;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A file is store-owned only when it sits under a hydrate root or an existing row.
 * {@code src/test/resources/until-pass} is inside the project and stays on the file loader.
 */
class StoreFeatureOwnershipTest {
    @TempDir
    Path project;

    private final Map<String, String> previous = new LinkedHashMap<>();

    @AfterEach
    void release() {
        restore();
        StoreScope.clear();
    }

    @Test
    void aHydrateRootIsAddressableAndASiblingDirectoryIsNot() throws Exception {
        set("pkb_store", "true");
        set("pkb_store_project", project.toAbsolutePath().normalize().toString());
        Path feature = project.resolve("src/test/resources/features/rows.feature");
        Path loop = project.resolve("src/test/resources/until-pass/loop.feature");
        Files.createDirectories(feature.getParent());
        Files.createDirectories(loop.getParent());
        Files.writeString(feature, "Feature: Rows\n  Scenario: Kept\n    * ASSERT: \"a\" equals \"a\"\n");
        Files.writeString(loop, "Feature: Loop\n  Scenario: Once\n    * ASSERT: \"a\" equals \"a\"\n");

        assertTrue(StoreFeatureSource.addressable(feature.toUri()));
        assertFalse(StoreFeatureSource.addressable(loop.toUri()));
        assertTrue(RowFeatureSupplier.usesStore(List.of(feature.toUri())));
        assertFalse(RowFeatureSupplier.usesStore(List.of(loop.toUri())));
        assertTrue(StoreFeatureSource.addressable(URI.create("classpath:features")));
        assertTrue(StoreFeatureSource.keys(URI.create("classpath:until-pass")).isEmpty());
    }

    @Test
    void theEngineProjectOwnsASpecWhenTheConfiguredProjectIsElsewhere() throws Exception {
        Path elsewhere = project.resolve("elsewhere");
        Files.createDirectories(elsewhere);
        set("pkb_store_project", elsewhere.toAbsolutePath().normalize().toString());
        Path spec = project.resolve("src/test/resources/features/live.spec.ts");
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, "test.describe(\"live\", () => {});\n");
        assertFalse(StoreFeatureSource.addressable(spec.toUri()));
        assertTrue(StoreFeatureSource.addressable(project, spec.toUri()));
    }

    private void set(String key, String value) {
        previous.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private void restore() {
        for (Map.Entry<String, String> entry : previous.entrySet()) {
            if (entry.getValue() == null) System.clearProperty(entry.getKey());
            else System.setProperty(entry.getKey(), entry.getValue());
        }
        previous.clear();
    }
}
