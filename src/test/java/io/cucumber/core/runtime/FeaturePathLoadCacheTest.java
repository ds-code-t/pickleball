package io.cucumber.core.runtime;

import io.cucumber.core.feature.FeatureParser;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.options.CucumberPropertiesParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.attribute.FileTime;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeaturePathLoadCacheTest {
    @TempDir
    Path temp;

    @AfterEach
    void resetCache() {
        FeaturePathLoadCache.bypass = false;
        FeaturePathLoadCache.clear();
    }

    @Test
    void secondLoadDoesNotReparseAndKeepsUriOrder() throws Exception {
        Path features = temp.resolve("features");
        Files.createDirectories(features);
        for (int index = 0; index < 12; index++) {
            Files.writeString(features.resolve(String.format("n-%02d.feature", index)), feature("N" + index, "Scenario " + index));
        }
        FeaturePathFeatureSupplier supplier = supplier("file:" + features.toAbsolutePath());

        FeaturePathLoadCache.bypass = true;
        List<String> original = fingerprint(supplier.get());
        FeaturePathLoadCache.bypass = false;
        FeaturePathLoadCache.clear();

        List<Feature> first = supplier.get();
        int parses = FeaturePathLoadCache.parseCount();
        assertEquals(12, parses);
        assertEquals(original, fingerprint(first));
        List<String> uris = first.stream().map(feature -> feature.getUri().toString()).toList();
        assertEquals(uris.stream().sorted().toList(), uris);

        List<Feature> second = supplier.get();
        assertEquals(parses, FeaturePathLoadCache.parseCount());
        assertEquals(original, fingerprint(second));
    }

    @Test
    void pathWithSpacesAndApostropheIsCached() throws Exception {
        Path features = temp.resolve("Program Files").resolve("Dan She'll");
        Files.createDirectories(features);
        Files.writeString(features.resolve("page.feature"), feature("Page", "Windows style"));
        FeaturePathFeatureSupplier supplier = supplier(features.toUri().toString());

        List<Feature> first = supplier.get();
        assertEquals(1, first.size());
        assertTrue(first.getFirst().getUri().toString().contains("Dan%20She'll")
                || first.getFirst().getUri().getPath().contains("Dan She'll"));
        assertEquals(1, FeaturePathLoadCache.parseCount());
        supplier.get();
        assertEquals(1, FeaturePathLoadCache.parseCount());
    }

    @Test
    void windowsPathWithSpacesRoundTripsThroughTheCache() throws Exception {
        Path features = temp.resolve("C").resolve("Users").resolve("Dan She'll");
        Files.createDirectories(features);
        Path featureFile = features.resolve("page.feature");
        Files.writeString(featureFile, feature("Drive", "Kept"));
        String windows = featureFile.toUri().toString();
        assertTrue(windows.contains("C/Users/Dan"), windows);
        assertTrue(windows.contains("She"), windows);
        FeaturePathFeatureSupplier supplier = supplier(windows);

        assertEquals(1, supplier.get().size());
        assertEquals(1, FeaturePathLoadCache.parseCount());
        assertEquals("Kept", supplier.get().getFirst().getPickles().getFirst().getName());
        assertEquals(1, FeaturePathLoadCache.parseCount());
    }

    @Test
    void classpathFeaturesAreCachedUntilTheFileChanges() throws Exception {
        Path root = temp.resolve("cp");
        Path features = root.resolve("features");
        Files.createDirectories(features);
        Path file = features.resolve("one.feature");
        Files.writeString(file, feature("Classpath", "First"));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, getClass().getClassLoader())) {
            FeaturePathFeatureSupplier supplier = supplier(loader, "classpath:features");
            assertEquals("First", supplier.get().getFirst().getPickles().getFirst().getName());
            int parses = FeaturePathLoadCache.parseCount();
            assertEquals("First", supplier.get().getFirst().getPickles().getFirst().getName());
            assertEquals(parses, FeaturePathLoadCache.parseCount());

            Files.writeString(file, feature("Classpath", "Second"));
            assertEquals("Second", supplier.get().getFirst().getPickles().getFirst().getName());
            assertTrue(FeaturePathLoadCache.parseCount() > parses);
        }
    }

    @Test
    void identicalCopyIsDroppedAndAMissingFeatureStillFails() throws Exception {
        Path left = temp.resolve("left");
        Path right = temp.resolve("right");
        Files.createDirectories(left);
        Files.createDirectories(right);
        String body = feature("Same", "Only once");
        Files.writeString(left.resolve("same.feature"), body);
        Files.writeString(right.resolve("same.feature"), body);
        String paths = left.toUri() + "," + right.toUri();
        List<Feature> features = supplier(paths).get();
        assertEquals(1, features.size());

        Path missing = temp.resolve("missing.feature");
        assertThrows(IllegalArgumentException.class, () -> supplier(missing.toUri().toString()).get());
    }

    @Test
    void secondParseResourceDoesNotReparseUntilTheFileChanges() throws Exception {
        Path file = temp.resolve("Program Files").resolve("Dan She'll").resolve("page.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, feature("Page", "First line"));
        FeatureParser parser = new FeatureParser(UUID::randomUUID);

        Feature first = parser.parseResource(fileResource(file)).orElseThrow();
        assertEquals("First line", first.getPickles().getFirst().getName());
        int parses = FeaturePathLoadCache.resourceParseCount();
        assertEquals(1, parses);
        assertTrue(file.toUri().toString().contains("Program%20Files"), file.toUri().toString());

        Feature second = parser.parseResource(fileResource(file)).orElseThrow();
        assertEquals(parses, FeaturePathLoadCache.resourceParseCount());
        assertEquals("First line", second.getPickles().getFirst().getName());

        Files.writeString(file, feature("Page", "Second line is longer"));
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000));
        Feature third = parser.parseResource(fileResource(file)).orElseThrow();
        assertEquals("Second line is longer", third.getPickles().getFirst().getName());
        assertTrue(FeaturePathLoadCache.resourceParseCount() > parses);
    }

    private static io.cucumber.core.resource.Resource fileResource(Path file) {
        return new io.cucumber.core.resource.Resource() {
            @Override
            public URI getUri() {
                return file.toUri();
            }

            @Override
            public InputStream getInputStream() throws java.io.IOException {
                return Files.newInputStream(file);
            }
        };
    }

    private static List<String> fingerprint(List<Feature> features) {
        List<String> rows = new ArrayList<>();
        for (Feature feature : features) {
            rows.add(feature.getUri() + "\n" + feature.getSource());
            feature.getPickles().forEach(pickle -> rows.add(pickle.getName()));
        }
        return rows;
    }

    private static String feature(String name, String scenario) {
        return "Feature: " + name + "\n  Scenario: " + scenario + "\n    * INFO: \"ok\"\n";
    }

    private FeaturePathFeatureSupplier supplier(String features) {
        return supplier(getClass().getClassLoader(), features);
    }

    private FeaturePathFeatureSupplier supplier(ClassLoader loader, String features) {
        Map<String, String> properties = new HashMap<>();
        properties.put("cucumber.features", features);
        var options = new CucumberPropertiesParser().parse(properties).build();
        return new FeaturePathFeatureSupplier(loader == null ? null : () -> loader, options, new FeatureParser(UUID::randomUUID));
    }
}
