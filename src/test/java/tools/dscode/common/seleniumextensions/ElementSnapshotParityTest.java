package tools.dscode.common.seleniumextensions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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
 * The composite producer does not shell out. A live dom-composite process is
 * a separate case and runs only when this repo vendors the binary. Host
 * PATH, ~/.m2, and sibling checkouts are ignored.
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
    void liveCompositeBinaryIsUsedOnlyWhenThisRepoVendorsIt() {
        Path binary = repoVendoredBinary();
        Assumptions.assumeTrue(
                binary != null,
                "skipped: dom-composite binary is not vendored in this repo "
                        + "(src/main/resources/dev/domcomposite/natives/<classifier>/composite). "
                        + "A host PATH, ~/.m2, or sibling checkout binary is ignored."
        );
        assertTrue(Files.isRegularFile(binary), binary.toString());
        assertTrue(Files.isExecutable(binary), binary.toString());
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

    /** Only a binary checked into this repo. Never a machine-local composite. */
    static Path repoVendoredBinary() {
        String classifier = tools.dscode.common.composite.CompositeBinaryLocator.classifier();
        String name = tools.dscode.common.composite.CompositeBinaryLocator.binaryName(classifier);
        Path root = Path.of("").toAbsolutePath();
        Path vendored = root.resolve("src/main/resources/dev/domcomposite/natives")
                .resolve(classifier)
                .resolve(name);
        if (Files.isRegularFile(vendored) && Files.isExecutable(vendored)) {
            return vendored.toAbsolutePath().normalize();
        }
        return null;
    }
}
