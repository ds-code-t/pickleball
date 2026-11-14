package tools.dscode.common.seleniumextensions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import tools.dscode.common.composite.CompositeElements;
import tools.dscode.common.composite.CompositeWebElement;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Selenium and composite producers must return the same snapshot fields.
 * The composite producer does not shell out. The live engine case uses the
 * in-repo Java control engine (dev.domengine). Host PATH, ~/.m2, and sibling checkouts are ignored.
 */
class ElementSnapshotParityTest {
    private HtmlUnitDriver driver;

    @BeforeEach
    void setUp() {
        SnapshotPages.pinSelenium();
        driver = SnapshotPages.open();
    }

    @AfterEach
    void tearDown() {
        if (driver != null) driver.quit();
        SnapshotPages.resetPin();
    }

    @Test
    void producersMatchOnTheFixtureExceptNodeIdAndBounds() {
        assertParity("target");
        assertParity("account");
        assertParity("qty");
    }

    @Test
    void compositeProducerReadsTheLiveDomNotTheCachedHit() {
        CompositeWebElement hit = CompositeElements.of("target", "div", "STALE-CACHED", 8, 9, 10, 11);
        ElementSnapshot shot = CompositeSnapshotProducer.INSTANCE.snapshot(driver, hit);
        assertTrue(shot.text().contains("Hello"), shot.text());
        assertFalse(shot.text().contains("STALE-CACHED"), shot.text());
        assertEquals(3, shot.siblingIndex());
        assertEquals(1, shot.sameTagIndex());
        assertEquals("sv", shot.selectedValue());
        assertEquals("main:42", shot.nodeId());
        assertNotNull(shot.bounds());
        assertEquals(8, shot.bounds().x());
        assertEquals(11, shot.bounds().height());
    }

    @Test
    void compositeProducerFailsClearlyWhenTheNodeCannotBeAddressed() {
        CompositeWebElement missing = CompositeElements.of("no-such-element", "div", "gone", 0, 0, 1, 1);
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> CompositeSnapshotProducer.INSTANCE.snapshot(driver, missing)
        );
        assertTrue(error.getMessage().contains("mode=composite"), error.getMessage());
        assertTrue(error.getMessage().contains("no-such-element"), error.getMessage());
        assertTrue(error.getMessage().startsWith("error:"), error.getMessage());
        assertTrue(error.getMessage().contains("fix:"), error.getMessage());
    }

    @Test
    void liveEngineIsTheInRepoJavaEngineWithoutAVendoredBinary(@org.junit.jupiter.api.io.TempDir Path project)
            throws Exception {
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("PATH", System.getenv().getOrDefault("PATH", ""));
        Path launcher = tools.dscode.common.composite.CompositeBinaryLocator.locate(
                project, env, getClass().getClassLoader()
        ).orElseThrow(() -> new AssertionError("in-repo Java engine did not resolve"));
        assertTrue(Files.isRegularFile(launcher), launcher.toString());
        assertTrue(Files.isExecutable(launcher), launcher.toString());
        String script = Files.readString(launcher);
        assertTrue(script.contains("dev.domengine.cli.DomEngineMain"), script);
        Process process = new ProcessBuilder(launcher.toString(), "version").redirectErrorStream(true).start();
        assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "engine version timed out");
        String out = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), out);
        assertTrue(out.contains("\"version\""), out);
    }

    private void assertParity(String id) {
        ElementSnapshot selenium = SeleniumSnapshotProducer.INSTANCE.snapshot(driver, SnapshotPages.byId(driver, id));
        String tag = selenium.tag();
        CompositeWebElement hit = CompositeElements.of(id, tag, "STALE-CACHED", 2, 3, 4, 5);
        ElementSnapshot composite = CompositeSnapshotProducer.INSTANCE.snapshot(driver, hit);
        assertEquals(selenium.tag(), composite.tag(), id);
        assertEquals(selenium.text(), composite.text(), id);
        assertEquals(selenium.value(), composite.value(), id);
        assertEquals(selenium.selectedValue(), composite.selectedValue(), id);
        assertEquals(selenium.attributes(), composite.attributes(), id);
        assertEquals(selenium.siblingIndex(), composite.siblingIndex(), id);
        assertEquals(selenium.sameTagIndex(), composite.sameTagIndex(), id);
        assertEquals(selenium.stableSelector(), composite.stableSelector(), id);
        assertNull(selenium.nodeId(), id);
        assertNull(selenium.bounds(), id);
        assertNull(selenium.geometry(), id);
        assertEquals("main:42", composite.nodeId(), id);
        assertNotNull(composite.bounds(), id);
        assertNull(composite.geometry(), id);
    }

    @Test
    void compositeHitWithGeometryCopiesSpacesAndSeleniumDoesNotInventThem() {
        ElementSnapshot selenium = SeleniumSnapshotProducer.INSTANCE.snapshot(driver, SnapshotPages.byId(driver, "target"));
        CompositeWebElement hit = CompositeElements.withGeometry("target", selenium.tag(), "STALE-CACHED", 8, 9, 10, 11);
        ElementSnapshot composite = CompositeSnapshotProducer.INSTANCE.snapshot(driver, hit);
        assertNull(selenium.geometry());
        assertNull(selenium.bounds());
        assertNotNull(composite.geometry());
        assertTrue(composite.geometry().matches(composite.bounds()));
        assertEquals(8, composite.bounds().x());
        assertEquals(4, composite.geometry().documentCssPx().corners().size());
        assertEquals(6.0, composite.geometry().visualViewportRelative().x());
        assertEquals("viewport", composite.geometry().nearestScrollport().kind());
        assertFalse(composite.geometry().closedShadow());
        assertTrue(composite.geometry().closedShadowLimit().contains("retargets"));
        var described = tools.dscode.common.composite.ElementOperations.describe(null, java.util.List.of(hit));
        assertEquals(1, described.size());
        assertNotNull(described.get(0).geometry());
        assertTrue(described.get(0).geometry().matches(new ElementSnapshot.Bounds(8, 9, 10, 11)));
    }
}
