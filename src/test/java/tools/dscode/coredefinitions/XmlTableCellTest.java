package tools.dscode.coredefinitions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A data-table cell that is XML must not be dropped as an unresolved reference.
 * A genuine {@code <ref>} placeholder still is.
 */
class XmlTableCellTest {

    @Test
    void xmlCellsCountAsValuesAndAReferenceDoesNot() {
        assertTrue(MappingSteps.hasNonNullValue("<R> <name>Adax</name></R>"));
        assertTrue(MappingSteps.hasNonBlankValue("<R> <name>Adax</name></R>"));
        assertTrue(MappingSteps.hasNonNullValue(
                "<ns1:R xmlns:ns1=\"urn:pickleball:test\"><name>Adax</name></ns1:R>"));
        assertTrue(MappingSteps.hasNonNullValue("<ns1:R><name>Adax</name></ns1:R>"));
        assertTrue(MappingSteps.hasNonNullValue("<R/>"));
        assertFalse(MappingSteps.hasNonNullValue("<no.such.ref>"));
        assertFalse(MappingSteps.hasNonNullValue("<Probe.missing>"));
        assertFalse(MappingSteps.hasNonBlankValue("<Probe.missing>"));
        assertTrue(MappingSteps.hasNonNullValue("C:\\Users\\Dan She'll\\page.html"));
        assertTrue(MappingSteps.hasNonNullValue("C:/Program Files/Pickleball/body.json"));
        assertTrue(MappingSteps.hasNonNullValue("plain"));
        assertFalse(MappingSteps.hasNonNullValue(null));
    }
}
