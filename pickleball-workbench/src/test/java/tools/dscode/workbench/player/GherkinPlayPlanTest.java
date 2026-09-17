package tools.dscode.workbench.player;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GherkinPlayPlanTest {
    @Test
    void prependsBackgroundAndSubstitutesSelectedOutlineRow() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Feature: Checkout",
                "  Background:",
                "    Given open shop",
                "  Scenario Outline: Buy",
                "    When buy <item>",
                "    Then see <item>",
                "    Examples:",
                "      | item  |",
                "      | apple |",
                "      | pear  |"
        ));
        ScenarioOrigin origin = new ScenarioOrigin(
                Path.of("checkout.feature"), "Buy", 4, 10, 2, "pear"
        );
        GherkinPlayPlan plan = GherkinPlayPlan.from(player, origin);
        assertEquals(3, plan.steps().size());
        assertTrue(plan.steps().get(0).executeText().contains("open shop"));
        assertEquals("    When buy pear", plan.steps().get(1).executeText());
        assertEquals("    Then see pear", plan.steps().get(2).executeText());
        assertTrue(plan.steps().get(1).sourceText().contains("<item>"));
    }

    @Test
    void demoBufferPlansEveryExecutableLine() {
        LiveScenarioPlayer player = LiveScenarioPlayer.interactiveBuffer();
        GherkinPlayPlan plan = GherkinPlayPlan.from(player, ScenarioOrigin.none());
        assertTrue(plan.steps().size() >= 3);
        assertEquals(plan.steps().get(0).sourceText(), plan.steps().get(0).executeText());
    }
}