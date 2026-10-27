package tools.dscode.testengine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Byte-for-byte baseline of plain non-Workbench resolution.
 * Neither runner sets {@code pkb_workspace}.
 */
class PlainResolutionGoldenTest {
    private static final Path GOLDEN = Path.of("src/test/resources/workspace-golden/plain-resolution.txt");

    @AfterEach
    void clearRunner() throws Exception {
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

    /** Example project: no hooks. Framework defaults only. */
    public static final class ExampleRunner extends PickleballRunner {
    }

    /**
     * Consumer-style project: hooks supply the six context keys, a browser,
     * a fixed parallel count, and a marker that must survive plain resolution.
     */
    public static final class ConsumerStyleRunner extends PickleballRunner {
        @Override
        public void globalTestDefaults() {
            values.put(PKB_props.PKB_GLUE, "com.example.steps");
            values.put(PKB_props.PKB_FEATURES, "classpath:features");
            values.put(PKB_props.PKB_DATA_PATH, "data");
            values.put(PKB_props.PKB_CALL_PATH, "src/test/resources/calls");
            values.put(PKB_props.PKB_COMPONENT_PATH, "src/test/resources/component");
            values.put(PKB_props.PKB_CONFIG_PATH, "configs");
            values.put(PKB_props.PKB_PARALLEL, "4");
            values.put(PKB_props.PKB_BROWSER, "firefox");
        }

        @Override
        public void globalTestProperties() {
            values.put("pkb_environment", "qa");
            values.put("pkb_hook_marker", "from-hook");
        }
    }
}
