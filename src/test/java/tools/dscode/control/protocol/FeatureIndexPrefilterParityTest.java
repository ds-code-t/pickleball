package tools.dscode.control.protocol;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.modularexecutions.CucumberScanUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.testengine.PKB_props.PKB_FEATURES;
import static tools.dscode.testengine.PKB_props.PKB_TAGS;

/**
 * Tag pre-filter passes only matching feature URIs into {@link CucumberScanUtil#listPickles}.
 * {@code CucumberScanUtil} is not modified.
 */
class FeatureIndexPrefilterParityTest {
    @TempDir
    Path project;

    @Test
    void tagPrefilterMatchesCucumberPicklesOnAFixture() throws Exception {
        Path features = project.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("search.feature"), """
                @smoke
                Feature: Search
                  Scenario Outline: Find <q>
                    Given <q>
                    Examples:
                      | q |
                      | a |
                      | b |
                """);
        Files.writeString(features.resolve("other.feature"), """
                @wip
                Feature: Other
                  Scenario: Stay
                    Given stay
                """);

        WorkbenchFeatureIndex.Selection selection = WorkbenchFeatureIndex.select(new WorkbenchFeatureIndex.Request(
                project, List.of(), "@smoke", null, null, List.of(), true
        ));
        assertFalse(selection.fullScanFallback(), selection.fallbackReason());
        assertTrue(selection.narrowed());
        assertTrue(selection.elapsedMillis() >= 0);
        List<String> uris = WorkbenchFeatureIndex.prefilteredFeatures(selection);
        assertEquals(1, uris.size(), uris.toString());
        assertTrue(uris.getFirst().contains("search.feature"), uris.toString());
        assertFalse(uris.getFirst().contains("other.feature"), uris.toString());

        List<String> narrowed = canonical(scan(selection.featureProperty(), "@smoke"));
        List<String> full = canonical(scan(both(features), "@smoke"));
        assertEquals(full, narrowed);
        assertEquals(2, narrowed.size(), narrowed.toString());
        assertTrue(narrowed.stream().anyMatch(line -> line.startsWith("Find a|")), narrowed.toString());
        assertTrue(narrowed.stream().anyMatch(line -> line.startsWith("Find b|")), narrowed.toString());
        assertTrue(narrowed.getFirst().contains("smoke"), narrowed.toString());
    }

    private static String both(Path features) {
        return "wd-file:" + features.resolve("search.feature").toAbsolutePath().normalize().toString().replace('\\', '/')
                + ",wd-file:" + features.resolve("other.feature").toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static List<Pickle> scan(String features, String tags) {
        CucumberScanUtil.clearCache();
        return CucumberScanUtil.listPickles(Map.of(PKB_FEATURES, features, PKB_TAGS, tags));
    }

    private static List<String> canonical(List<Pickle> pickles) {
        List<String> lines = new ArrayList<>();
        for (Pickle pickle : pickles) {
            List<String> tags = new ArrayList<>();
            for (String tag : pickle.getTags()) {
                String value = tag;
                while (value.startsWith("@")) value = value.substring(1);
                if (!value.isEmpty()) tags.add(value);
            }
            tags.sort(Comparator.naturalOrder());
            lines.add(pickle.getName() + "|" + String.join(",", tags));
        }
        lines.sort(Comparator.naturalOrder());
        return lines;
    }
}
