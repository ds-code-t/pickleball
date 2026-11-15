package tools.dscode.dalton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaltonStepDefinitionCatalogTest {
    @TempDir
    Path project;

    @Test
    void hydrateCatalogsJavaGlueAndJsSourceAndSkipsComments() throws Exception {
        Path java = project.resolve("src/test/java/demo/Steps.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, """
                package demo;
                public class Steps {
                    // @Given("commented out")
                    @Given("a user {string}")
                    public void aUser(String name) {}

                    @When(value = "the total is {int}")
                    public void total(int amount) {}
                }
                """, StandardCharsets.UTF_8);
        Path script = project.resolve("glue/js/steps.js");
        Files.createDirectories(script.getParent());
        Files.writeString(script, """
                // Given('skipped')
                Given('open the app', function () {});
                When("pay {int}", function () {});
                """, StandardCharsets.UTF_8);
        Path typescript = project.resolve("glue/js/extra.ts");
        Files.writeString(typescript, "Then(`done`, function () {});\n", StandardCharsets.UTF_8);

        DaltonHydrator.Result result = DaltonHydrator.hydrate(project, DaltonHydrator.Options.full());
        assertEquals(0, result.conflicts());
        List<DaltonStepCatalog.Definition> rows = DaltonStepCatalog.list(project);
        assertEquals(5, rows.size(), rows.toString());
        assertTrue(rows.stream().anyMatch(row ->
                "java".equals(row.language())
                        && "a user {string}".equals(row.pattern())
                        && "aUser".equals(row.methodName())
                        && row.sourcePath().endsWith("src/test/java/demo/Steps.java")
                        && row.sourceLine() == 4));
        assertTrue(rows.stream().anyMatch(row -> "the total is {int}".equals(row.pattern()) && "total".equals(row.methodName())));
        assertTrue(rows.stream().anyMatch(row -> "js".equals(row.language()) && "open the app".equals(row.pattern())));
        assertTrue(rows.stream().anyMatch(row -> "js".equals(row.language()) && "pay {int}".equals(row.pattern())));
        assertTrue(rows.stream().anyMatch(row -> "ts".equals(row.language()) && "done".equals(row.pattern())));
        assertFalse(rows.stream().anyMatch(row -> row.pattern().contains("commented") || row.pattern().contains("skipped")));
        String expected = DaltonStepCatalog.definitionId("java", "a user {string}", "src/test/java/demo/Steps.java", 4);
        assertEquals(expected, rows.stream().filter(row -> "a user {string}".equals(row.pattern())).findFirst().orElseThrow().id());
        assertEquals(64, expected.length());
    }
}
