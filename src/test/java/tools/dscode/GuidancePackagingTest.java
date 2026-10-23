package tools.dscode;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuidancePackagingTest {
    @Test
    void experimentalFeaturesArePackagedWithTheJdbcContract() throws Exception {
        try (var input = GuidancePackagingTest.class.getClassLoader().getResourceAsStream(
                "META-INF/pickleball/guidance/docs/experimental-features.md"
        )) {
            assertNotNull(input, "experimental-features.md is missing from packaged guidance");
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("does not fail the step"), text);
            assertTrue(text.contains("RESPONSE.error"), text);
            assertTrue(text.contains("[B@"), text);
        }
    }
}
