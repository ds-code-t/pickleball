package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.WorkbenchFeatureIndex;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
        assertEquals("the price is 2", WorkbenchValidate.expand("the price is <price>", List.of("price"), List.of("2")));
    }

    @Test
    void outlineRowsAndCoreStepsAreDefined() throws Exception {
        Path feature = project.resolve("src/test/resources/features/outline.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                Feature: Outline
                  Scenario Outline: Buy
                    Given buy "<item>"
                    Given the price is <price>
                    Given non-blank: kept
                    Given missing step
                  Examples:
                    | item | price |
                    | milk | 2 |
                """);
        Path glue = project.resolve("src/test/java/ShopSteps.java");
        Files.createDirectories(glue.getParent());
        Files.writeString(glue, """
                public class ShopSteps {
                  @Given("buy {string}") void a(String item) {}
                  @Given("the price is {int}") void b(int price) {}
                }
                """);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = WorkbenchValidate.run(project, null, null, new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(1, code, out);
        assertTrue(out.contains("validate progress stage=glue"), out);
        assertTrue(out.contains("capped=false"), out);
        assertTrue(out.contains("undefined:"), out);
        assertTrue(out.contains("missing step"), out);
        assertFalse(out.lines().anyMatch(line -> line.startsWith("undefined:") && (line.contains("price") || line.contains("non-blank") || line.contains("buy"))), out);
    }

    @Test
    void wholeSuitePrintsProgressAndStopsAtTheCap() throws Exception {
        Path feature = project.resolve("src/test/resources/features/many.feature");
        Files.createDirectories(feature.getParent());
        StringBuilder body = new StringBuilder("Feature: Many\n  Scenario: Lots\n");
        int steps = WorkbenchValidate.STEP_CAP + 25;
        for (int i = 0; i < steps; i++) body.append("    Given missing ").append(i).append('\n');
        Files.writeString(feature, body.toString());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = WorkbenchValidate.run(project, null, null, new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(1, code, out);
        assertTrue(out.contains("validate progress stage=steps"), out);
        assertTrue(out.contains("capped=true"), out);
        assertTrue(out.contains("fix: narrow validate with --tags or --name"), out);
        assertFalse(out.contains("missing " + (steps - 1)), out);
        long undefined = out.lines().filter(line -> line.startsWith("undefined:")).count();
        assertEquals(WorkbenchValidate.STEP_CAP, undefined, out);
    }
}
