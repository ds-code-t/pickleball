package tools.dscode.common.resources;

import com.fasterxml.jackson.databind.JsonNode;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.modularexecutions.CucumberScanUtil;
import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.FileAndDataParsing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static tools.dscode.testengine.PKB_props.PKB_FEATURES;
import static tools.dscode.testengine.PKB_props.PKB_FEATURE_NAME;
import static tools.dscode.testengine.PKB_props.PKB_NAME;

public final class PathPrefixChecks {
    @Test
    void featurePrefixesResolveOnTheClasspathAndWdFileReadsADirectory() throws IOException {
        CucumberScanUtil.clearCache();
        try {
            for (String configured : List.of("features", "file:features", "classpath:features")) {
                assertEquals(
                        List.of("A file reference reads customers.yaml"),
                        names(configured, "Plain file and ampersand references", "^A file reference reads customers.yaml$"),
                        configured
                );
            }
            Path dir = Files.createTempDirectory("pkb-features-");
            try {
                Files.writeString(dir.resolve("one.feature"), """
                        Feature: Temp
                          Scenario: Working directory feature
                            Given stay
                        """);
                String configured = "wd-file:" + forward(dir);
                assertEquals(
                        List.of("Working directory feature"),
                        names(configured, "Temp", "^Working directory feature$")
                );
            } finally {
                deleteTree(dir);
            }
        } finally {
            CucumberScanUtil.clearCache();
        }
    }

    @Test
    void dataPrefixesAndFileReferencesStayOnTheClasspath() throws IOException {
        assertEquals("Ava", text(FileAndDataParsing.buildJsonFromPath("files/customers #1.name", false)));
        for (String root : List.of("data", "file:data", "classpath:data")) {
            assertEquals(
                    "Ava",
                    text(FileAndDataParsing.buildJsonFromPathUnderRoot(
                            root,
                            "files/customerPayload.customer.name",
                            false
                    )),
                    root
            );
        }
        Path dir = Files.createTempDirectory("pkb-data-");
        try {
            Path payload = dir.resolve("files/customerPayload.yaml");
            Files.createDirectories(payload.getParent());
            Files.writeString(payload, """
                    customer:
                      name: Disk
                    """);
            String root = "wd-file:" + forward(dir);
            assertEquals(
                    "Disk",
                    text(FileAndDataParsing.buildJsonFromPathUnderRoot(
                            root,
                            "files/customerPayload.customer.name",
                            false
                    ))
            );
            assertNull(FileAndDataParsing.buildJsonFromPathUnderRoot(
                    "wd-file:" + forward(dir.resolve("missing")),
                    "files/customerPayload.customer.name",
                    false
            ));
        } finally {
            deleteTree(dir);
        }
    }

    private static List<String> names(String features, String featureName, String scenarioName) {
        CucumberScanUtil.clearCache();
        Map<String, String> props = new HashMap<>();
        props.put(PKB_FEATURES, features);
        props.put(PKB_FEATURE_NAME, featureName);
        props.put(PKB_NAME, scenarioName);
        return CucumberScanUtil.listPickles(props).stream().map(Pickle::getName).toList();
    }

    private static String text(JsonNode node) {
        return node == null ? null : node.asText();
    }

    private static String forward(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
