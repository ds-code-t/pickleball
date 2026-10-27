package tools.dscode.testengine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.dscode.testengine.fixtures.ConsumerStyleRunner;
import tools.dscode.testengine.fixtures.ExampleRunner;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Byte-for-byte baseline of plain non-Workbench resolution.
 * Neither runner sets {@code pkb_workspace}.
 */
class PlainResolutionGoldenTest {
    private static final Path GOLDEN = Path.of("src/test/resources/workspace-golden/plain-resolution.txt");
    private Properties saved;

    @BeforeEach
    void isolate() {
        saved = new Properties();
        saved.putAll(System.getProperties());
        for (String key : System.getProperties().stringPropertyNames().toArray(String[]::new)) {
            if (key.regionMatches(true, 0, "pkb_", 0, 4)) System.clearProperty(key);
        }
    }

    @AfterEach
    void clearRunner() throws Exception {
        Properties live = System.getProperties();
        live.clear();
        live.putAll(saved);
        Field instance = PickleballRunner.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    void exampleAndConsumerStyleResolutionMatchThePhase0Baseline() throws Exception {
        String actual = "EXAMPLE\n" + dump(new ExampleRunner())
                + "CONSUMER\n" + dump(new ConsumerStyleRunner());
        if (!Files.isRegularFile(GOLDEN) || Boolean.getBoolean("pkb.update.golden")) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, actual, StandardCharsets.UTF_8);
        }
        String expected = Files.readString(GOLDEN, StandardCharsets.UTF_8);
        assertEquals(expected, actual);
    }

    private static String dump(PickleballRunner runner) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : runner.values().entrySet()) {
            String key = entry.getKey();
            if (key == null || !PKB_props.isRunVariableKey(key)) continue;
            if (PKB_props.PKB_OPTIONS.equals(key) || PKB_props.PKB_RUN_PROFILE.equals(key)) continue;
            sorted.put(key, entry.getValue() == null ? "" : entry.getValue());
        }
        StringBuilder out = new StringBuilder();
        sorted.forEach((key, value) -> out.append(key).append('=').append(value).append('\n'));
        return out.toString();
    }
}
