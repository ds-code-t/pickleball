package tools.dscode.coredefinitions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NavigationStepsTest {
    @Test
    void configKeyStillWinsAndALiteralUrlIsUsedOnlyWhenLookupFails() {
        assertEquals(
                "https://from-config.example/home",
                NavigationSteps.addressAfterConfigLookup("URL.home", "https://from-config.example/home")
        );
        assertEquals(
                "https://example.test/forms",
                NavigationSteps.addressAfterConfigLookup("https://example.test/forms", null)
        );
        assertEquals(
                "http://localhost:8080/a",
                NavigationSteps.addressAfterConfigLookup("  http://localhost:8080/a  ", "")
        );
        assertNull(NavigationSteps.addressAfterConfigLookup("URL.missing", null));
        assertNull(NavigationSteps.addressAfterConfigLookup("not a url", null));
    }
}
