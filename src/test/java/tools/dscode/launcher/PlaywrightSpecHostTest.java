package tools.dscode.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.match.ForeignStepHost;
import tools.dscode.engine.match.ForeignStepHosts;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.SourceLocation;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.source.ScenarioSource;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PlaywrightSpecHostTest {
    private ExecutionContext seen;

    @AfterEach
    void release() {
        ForeignStepHosts.end();
        ForeignStepHosts.install(null);
    }

    @Test
    void stubRecordsAHandleStringAndTheGraphHasNoPlaywrightType() {
        ForeignStepHosts.install(List.of(new Stub()));
        EngineStep step = EngineStep.of("*", "await page.goto(\"http://127.0.0.1/pay\")")
                .withMetadata("definition", "def-1");
        EngineScenario scenario = new EngineScenario(
                "Playwright",
                "pays",
                "checkout",
                List.of(),
                List.of(step),
                List.of(),
                SourceLocation.unknown(),
                Map.of());
        EngineFeature feature = EngineFeature.of("checkout.spec.ts", List.of(scenario));
        ScenarioSource source = new ScenarioSource() {
            @Override
            public String id() {
                return "memory:checkout.spec.ts";
            }

            @Override
            public List<EngineFeature> load() {
                return List.of(feature);
            }
        };
        RunResult result = PickleballEngine.run(source, tools.dscode.engine.EngineOptions.of());
        assertEquals(Status.PASSED, result.status(), result.message());
        assertEquals("page:stub", seen.resources().get("playwright.page"));
        assertTrue(seen.resources().get("playwright.page") instanceof String);
        List<String> hits = new ArrayList<>();
        Set<Object> seenObjects = Collections.newSetFromMap(new IdentityHashMap<>());
        walk(result, seenObjects, hits);
        walk(seen, seenObjects, hits);
        if (!hits.isEmpty()) fail("engine graph contains " + hits);
    }

    private final class Stub implements ForeignStepHost {
        @Override
        public String language() {
            return "playwright";
        }

        @Override
        public boolean supports(String host) {
            return "playwright".equals(host);
        }

        @Override
        public Object invoke(String definitionId, String stepText, List<String> argsJson) {
            seen = ExecutionContext.current();
            seen.resource("playwright.page", "page:stub");
            return "page:stub";
        }
    }

    private static void walk(Object value, Set<Object> seen, List<String> hits) {
        if (value == null || !seen.add(value)) return;
        Class<?> type = value.getClass();
        String name = type.getName();
        if (name.startsWith("com.microsoft.playwright")) hits.add(name);
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) walk(item, seen, hits);
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                walk(entry.getKey(), seen, hits);
                walk(entry.getValue(), seen, hits);
            }
        }
        if (type.isArray()) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) walk(Array.get(value, index), seen, hits);
            return;
        }
        if (name.startsWith("java.") || name.startsWith("jdk.")) return;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            if (current.getName().startsWith("java.") || current.getName().startsWith("jdk.")) continue;
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    walk(field.get(value), seen, hits);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // A field the test cannot read is not a Playwright object this walk can see.
                }
            }
        }
    }
}
