package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.WorkbenchFeatureIndex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchValidateTest {
    @TempDir
    Path project;

    @Test
    void reportsUndefinedAmbiguousAndUnresolvedWithoutRunning() throws Exception {
        Path feature = project.resolve("src/test/resources/features/shop.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                @shop
                Feature: Shop
                  Scenario: Buy
                    Given buy "milk"
                    Given missing step
                    Then RUN SCENARIO: Nowhere
                    Then TRACE: kept
                """);
        Path glue = project.resolve("src/test/java/ShopSteps.java");
        Files.createDirectories(glue.getParent());
        Files.writeString(glue, """
                public class ShopSteps {
                  @Given("buy {string}") void a(String item) {}
                  @When("buy {string}") void b(String item) {}
                }
                """);
        long before = Files.size(feature);
        WorkbenchFeatureIndex.Selection selection = WorkbenchFeatureIndex.select(
                new WorkbenchFeatureIndex.Request(project, List.of(), null, null, null, List.of(), true)
        );
        List<WorkbenchValidate.Finding> findings = WorkbenchValidate.scan(
                project, feature, 1, Integer.MAX_VALUE, List.of("buy {string}", "buy {string}"), selection
        );
        assertTrue(findings.stream().anyMatch(found -> "ambiguous".equals(found.kind()) && found.text().contains("buy")));
        assertTrue(findings.stream().anyMatch(found -> "undefined".equals(found.kind()) && found.text().contains("missing")));
        assertTrue(findings.stream().anyMatch(found -> "unresolved".equals(found.kind()) && found.text().contains("Nowhere")));
        assertFalse(findings.stream().anyMatch(found -> found.text().startsWith("TRACE")));
        assertEquals(before, Files.size(feature));
        assertTrue(WorkbenchValidate.framework("TRACE: hello"));
        assertTrue(WorkbenchValidate.patternMatches("buy {string}", "buy \"milk\""));
        assertFalse(WorkbenchValidate.patternMatches("buy {string}", "missing step"));
    }
}
