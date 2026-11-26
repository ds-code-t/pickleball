package tools.dscode.engine.adapter.playwright;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.StoreDatabase;
import tools.dscode.store.StoreHydrator;
import tools.dscode.store.StoreQuery;
import tools.dscode.store.StoreStepCatalog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaywrightTestIngestTest {
    private static final String SPEC = """
            const test = base.extend({
              locale: ["en-US", { option: true }],
            });
            test.describe("checkout", () => {
              test("pays", async ({ page }) => {
                await page.goto("https://example.test/pay");
                const title = await page.locator("h1").textContent();
              });
              test("sees the title", async ({ page }) => {
                await expect(page).toHaveTitle("Pay");
              });
            });
            """;

    @TempDir
    Path project;

    private String previousLog;

    @AfterEach
    void restoreLog() {
        if (previousLog == null) System.clearProperty("pkb_engine_log");
        else System.setProperty("pkb_engine_log", previousLog);
    }

    @Test
    void twoTestsBecomePlaywrightScenariosAndPageIsBuiltin() throws Exception {
        previousLog = System.getProperty("pkb_engine_log");
        System.setProperty("pkb_engine_log", "ingest");
        Path spec = project.resolve("src/test/resources/features/checkout.spec.ts");
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, SPEC, StandardCharsets.UTF_8);
        StoreHydrator.hydrate(project, StoreHydrator.Options.full());

        List<StoreQuery.ScenarioRow> scenarios = StoreQuery.scenarios(project, "src/test/resources/features/checkout.spec.ts");
        assertEquals(2, scenarios.size());
        assertEquals("Playwright", scenarios.get(0).keyword());
        assertEquals("Playwright", scenarios.get(1).keyword());
        assertEquals("pays", scenarios.get(0).name());
        assertEquals("sees the title", scenarios.get(1).name());
        assertEquals("checkout", scenarios.get(0).description());

        List<StoreQuery.StepRow> steps = StoreQuery.steps(project, "src/test/resources/features/checkout.spec.ts", null);
        assertFalse(steps.isEmpty());
        for (StoreQuery.StepRow step : steps) {
            assertFalse(step.keyword().equals("Given") || step.keyword().equals("When") || step.keyword().equals("Then"), step.keyword());
            assertFalse(step.text().startsWith("Given ") || step.text().startsWith("When ") || step.text().startsWith("Then "), step.text());
        }
        assertTrue(steps.stream().anyMatch(step -> step.text().equals("await page.goto(\"https://example.test/pay\");")));
        assertTrue(steps.stream().anyMatch(step -> "*".equals(step.keyword())));

        try (StoreDatabase database = StoreDatabase.open(project);
             var statement = database.connection().prepareStatement(
                     "SELECT name, kind, source FROM fixtures WHERE file_path = ? ORDER BY name")) {
            statement.setString(1, "src/test/resources/features/checkout.spec.ts");
            try (var rows = statement.executeQuery()) {
                boolean page = false;
                boolean locale = false;
                while (rows.next()) {
                    if ("page".equals(rows.getString(1))) {
                        page = true;
                        assertEquals("builtin", rows.getString(2));
                        assertEquals("", rows.getString(3));
                    }
                    if ("locale".equals(rows.getString(1))) {
                        locale = true;
                        assertEquals("option", rows.getString(2));
                    }
                }
                assertTrue(page, "page builtin fixture");
                assertTrue(locale, "locale option fixture");
            }
        }

        StoreStepCatalog.Definition definition = StoreStepCatalog.list(project).stream()
                .filter(row -> "playwright".equals(row.host()) && "pays".equals(row.pattern()))
                .findFirst()
                .orElseThrow();
        assertEquals("playwright", definition.language());
        assertEquals("pays", definition.pattern());
        assertFalse(definition.pattern().startsWith("^"));

        String stored = StoreQuery.file(project, "src/test/resources/features/checkout.spec.ts").text();
        assertTrue(stored.contains("test.describe"));
        assertFalse(stored.contains("Given"));

        String log = Files.readString(project.resolve(".pickleball/engine/engine.log"), StandardCharsets.UTF_8);
        assertTrue(log.contains("playwright spec src/test/resources/features/checkout.spec.ts tests=2"), log);
    }
}
