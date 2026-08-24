package tools.dscode.workbench.catalog;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioFilterTest {
    @Test
    void nameMatchModesAreCaseInsensitiveAndDefaultToContains() {
        ScenarioFilter filter = new ScenarioFilter();
        assertEquals(ScenarioFilter.NameMatchMode.CONTAINS, filter.nameMatchMode());

        ConsumerFeatureCatalog.ScenarioEntry login = scenario("Valid Login User");
        ConsumerFeatureCatalog.ScenarioEntry logout = scenario("Logout path");
        List<ConsumerFeatureCatalog.ScenarioEntry> pool = List.of(login, logout);

        filter.setNameQuery("LOGIN");
        assertEquals(List.of(login), filter.apply(pool));

        filter.setNameMatchMode(ScenarioFilter.NameMatchMode.STARTS_WITH);
        filter.setNameQuery("valid");
        assertEquals(List.of(login), filter.apply(pool));
        filter.setNameQuery("user");
        assertTrue(filter.apply(pool).isEmpty());

        filter.setNameMatchMode(ScenarioFilter.NameMatchMode.ENDS_WITH);
        filter.setNameQuery("USER");
        assertEquals(List.of(login), filter.apply(pool));
        filter.setNameQuery("valid");
        assertTrue(filter.apply(pool).isEmpty());

        filter.setNameMatchMode(ScenarioFilter.NameMatchMode.FULL_MATCH);
        filter.setNameQuery("valid login user");
        assertEquals(List.of(login), filter.apply(pool));
        filter.setNameQuery("valid login");
        assertTrue(filter.apply(pool).isEmpty());
    }

    @Test
    void includeTagsAreAndAndExcludeTagsAreNot() {
        ConsumerFeatureCatalog.ScenarioEntry smokeLogin = scenario("A", "smoke", "login");
        ConsumerFeatureCatalog.ScenarioEntry smokeOnly = scenario("B", "smoke");
        ConsumerFeatureCatalog.ScenarioEntry wip = scenario("C", "wip", "login");
        List<ConsumerFeatureCatalog.ScenarioEntry> pool = List.of(smokeLogin, smokeOnly, wip);

        ScenarioFilter filter = new ScenarioFilter();
        filter.setIncludeTagsQuery("@smoke @login");
        assertEquals(List.of(smokeLogin), filter.apply(pool));

        filter.setIncludeTagsQuery("smoke, login");
        assertEquals(List.of(smokeLogin), filter.apply(pool));

        filter.setIncludeTagsQuery("");
        filter.setExcludeTagsQuery("@wip");
        assertEquals(List.of(smokeLogin, smokeOnly), filter.apply(pool));

        filter.setIncludeTagsQuery("login");
        filter.setExcludeTagsQuery("wip");
        assertEquals(List.of(smokeLogin), filter.apply(pool));
    }

    @Test
    void emptyNameAndTagFieldsImposeNoConstraint() {
        ConsumerFeatureCatalog.ScenarioEntry one = scenario("One", "alpha");
        ConsumerFeatureCatalog.ScenarioEntry two = scenario("Two");
        ScenarioFilter filter = new ScenarioFilter();
        assertEquals(List.of(one, two), filter.apply(List.of(one, two)));
    }

    @Test
    void nameAndTagFiltersCompose() {
        ConsumerFeatureCatalog.ScenarioEntry keep = scenario("Locked account", "wip");
        ConsumerFeatureCatalog.ScenarioEntry otherWip = scenario("Other wip", "wip");
        ConsumerFeatureCatalog.ScenarioEntry lockedClean = scenario("Locked account clean");
        ScenarioFilter filter = new ScenarioFilter();
        filter.setNameQuery("locked");
        filter.setIncludeTagsQuery("wip");
        assertEquals(List.of(keep), filter.apply(List.of(keep, otherWip, lockedClean)));
    }

    @Test
    void parseTagQueryAcceptsAtSignCommaAndWhitespace() {
        assertEquals(List.of("smoke", "login", "wip"),
                ScenarioFilter.parseTagQuery(" @smoke, login   wip "));
        assertTrue(ScenarioFilter.parseTagQuery("").isEmpty());
        assertTrue(ScenarioFilter.parseTagQuery("   ,  ").isEmpty());
        assertEquals("smoke", ScenarioFilter.canonicalTag("@@smoke"));
    }

    @Test
    void tagComparisonIsCaseSensitiveAfterCanonicalizingAt() {
        ConsumerFeatureCatalog.ScenarioEntry tagged = scenario("A", "Smoke");
        ScenarioFilter filter = new ScenarioFilter();
        filter.setIncludeTagsQuery("Smoke");
        assertEquals(List.of(tagged), filter.apply(List.of(tagged)));
        filter.setIncludeTagsQuery("smoke");
        assertTrue(filter.apply(List.of(tagged)).isEmpty());
    }

    @Test
    void gherkinTagLinesRequireAtPrefixedTokens() {
        assertTrue(ScenarioFilter.isGherkinTagLine("@smoke @login"));
        assertFalse(ScenarioFilter.isGherkinTagLine("Given @not-a-tag-line"));
        assertEquals(List.of("smoke", "login"), ScenarioFilter.parseGherkinTagLine("@smoke @login"));
    }

    private static ConsumerFeatureCatalog.ScenarioEntry scenario(String name, String... effectiveTags) {
        return new ConsumerFeatureCatalog.ScenarioEntry(
                name,
                "Demo",
                Path.of("demo.feature"),
                "demo.feature",
                1,
                3,
                List.of("Scenario: " + name),
                List.of(),
                List.of(effectiveTags)
        );
    }
}
