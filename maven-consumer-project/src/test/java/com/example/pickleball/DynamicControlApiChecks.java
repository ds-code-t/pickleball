package com.example.pickleball;

import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;
import org.junit.jupiter.api.Test;
import tools.dscode.common.control.ControlDecision;
import tools.dscode.common.control.ControlHook;
import tools.dscode.common.control.ControlHookHandler;
import tools.dscode.common.control.ControlValueEvent;
import tools.dscode.common.control.ControlRuntime;
import tools.dscode.control.api.ControlCallResult;
import tools.dscode.control.api.ControlCallStatus;
import tools.dscode.control.api.DynamicControl;
import tools.dscode.control.api.GherkinControl;
import tools.dscode.control.api.MappingContext;
import tools.dscode.control.api.MappingControl;
import tools.dscode.control.api.MappingSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DynamicControlApiChecks {

    @Test
    void executesDetachedStepAgainstCurrentGlueWithoutChangingScenarioTraversal() {
        ControlApiTestSteps.reset();

        ControlCallResult<Object> result = DynamicControl.executeStep("CONTROL API TEST STEP");

        assertTrue(result.successful(), () -> String.valueOf(result.error()));
        assertEquals(1, ControlApiTestSteps.invocationCount());
    }

    @Test
    void exploratoryFailureIsReturnedWithoutFailingTheScenario() {
        CurrentScenarioState state = getCurrentScenarioState();
        assertNotNull(state);
        assertFalse(state.isScenarioFailed());

        ControlCallResult<Object> result = DynamicControl.executeStep(
                ", verify \"left\" equals \"right\""
        );

        assertEquals(ControlCallStatus.FAILED, result.status());
        assertNotNull(result.error());
        assertFalse(state.isScenarioFailed());
    }

    @Test
    void dynamicPhraseCanRunDetachedFromTheScenarioTree() {
        ControlCallResult<Object> result = DynamicControl.executeStep(
                ", verify \"same\" equals \"same\""
        );

        assertTrue(result.successful(), () -> String.valueOf(result.error()));
    }

    @Test
    void stepHooksObserveDetachedExecution() {
        List<ControlHook> hooks = new ArrayList<>();

        ControlCallResult<Object> result = ControlRuntime.withThreadHandler(
                event -> {
                    hooks.add(event.hook());
                    return ControlDecision.CONTINUE;
                },
                () -> DynamicControl.executeStep("CONTROL API TEST STEP")
        );

        assertTrue(result.successful(), () -> String.valueOf(result.error()));
        assertTrue(hooks.contains(ControlHook.BEFORE_STEP));
        assertTrue(hooks.contains(ControlHook.AFTER_STEP));
    }

    @Test
    void stepHookCanSkipExploratoryExecution() {
        ControlApiTestSteps.reset();

        ControlCallResult<Object> result = ControlRuntime.withThreadHandler(
                event -> event.hook() == ControlHook.BEFORE_STEP
                        ? ControlDecision.SKIP
                        : ControlDecision.CONTINUE,
                () -> DynamicControl.executeStep("CONTROL API TEST STEP")
        );

        assertTrue(result.successful(), () -> String.valueOf(result.error()));
        assertEquals(0, ControlApiTestSteps.invocationCount());
    }

    @Test
    void phraseHookCanSkipAUsuallyFailingDynamicPhrase() {
        ControlCallResult<Object> result = ControlRuntime.withThreadHandler(
                event -> event.hook() == ControlHook.BEFORE_PHRASE
                        ? ControlDecision.SKIP
                        : ControlDecision.CONTINUE,
                () -> DynamicControl.executeStep(", verify \"left\" equals \"right\"")
        );

        assertTrue(result.successful(), () -> String.valueOf(result.error()));
        assertFalse(getCurrentScenarioState().isScenarioFailed());
    }


    @Test
    void fixedWaitHooksCanSuppressFrameworkPhraseDelays() {
        int[] waits = {0};

        ControlCallResult<Object> result = ControlRuntime.withThreadHandler(
                event -> {
                    if (event.hook() == ControlHook.BEFORE_FIXED_WAIT) {
                        waits[0]++;
                        return ControlDecision.SKIP;
                    }
                    return ControlDecision.CONTINUE;
                },
                () -> DynamicControl.executeStep(", verify \"same\" equals \"same\"")
        );

        assertTrue(result.successful(), () -> String.valueOf(result.error()));
        assertTrue(waits[0] > 0);
    }

    @Test
    void canCreateAndRelateTemporaryStepsWithoutTouchingTheScenarioTree() {
        StepExtension parent = DynamicControl.createStep("CONTROL API TEST STEP").value();
        StepExtension child = DynamicControl.createStep("CONTROL API TEST STEP").value();
        assertNotNull(parent);
        assertNotNull(child);

        ControlCallResult<StepExtension> result = DynamicControl.addChild(parent, child);

        assertTrue(result.successful(), () -> String.valueOf(result.error()));
        assertSame(parent, child.parentStep);
        assertSame(child, parent.childSteps.getFirst());
        assertEquals(parent.getNestingLevel() + 1, child.getNestingLevel());
    }


    @Test
    void executesParsedPickleAsRetryFriendlyDetachedSteps() {
        ControlApiTestSteps.reset();
        String source = """
                Feature: Execute parsed pickle
                  Scenario: Execute me
                    Given CONTROL API TEST STEP
                    And CONTROL API TEST STEP
                """;

        var parsed = GherkinControl.parseFeature(source);
        assertTrue(parsed.successful(), () -> String.valueOf(parsed.error()));
        var results = DynamicControl.executePickle(
                GherkinControl.scenarios(parsed.value()).getFirst()
        );

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(ControlCallResult::successful));
        assertEquals(2, ControlApiTestSteps.invocationCount());
    }

    @Test
    void executesTemporaryTreeInPreOrder() {
        ControlApiTestSteps.reset();
        StepExtension parent = DynamicControl.createStep("CONTROL API TEST STEP").value();
        StepExtension child = DynamicControl.createStep("CONTROL API TEST STEP").value();
        assertTrue(DynamicControl.addChild(parent, child).successful());

        var results = DynamicControl.executeTree(parent);

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(ControlCallResult::successful));
        assertEquals(2, ControlApiTestSteps.invocationCount());
    }

    @Test
    void parsesFeatureScenarioStepsAndArgumentsFromText() {
        String source = """
                Feature: Control API parser
                  Scenario: Parse me
                    Given a control step
                      | key | value |
                      | one | two   |
                """;

        ControlCallResult<Feature> parsed = GherkinControl.parseFeature(source);

        assertTrue(parsed.successful(), () -> String.valueOf(parsed.error()));
        assertEquals(1, GherkinControl.scenarios(parsed.value()).size());
        var steps = GherkinControl.steps(GherkinControl.scenarios(parsed.value()).getFirst());
        assertEquals(1, steps.size());
        assertTrue(GherkinControl.argumentText(steps.getFirst()).contains("| key | value |"));
    }

    @Test
    void isolatedSingleNodeMapControlsDetachedResolutionWithoutChangingLiveMap() {
        ParsingMap live = ParsingMap.getRunningParsingMap();
        String before = live.toString();
        NodeMap onlyMap = MappingControl.nodeMap(
                MapConfigurations.MapType.OVERRIDE_MAP,
                Map.of("controlValue", "isolated")
        );
        MappingContext context = MappingControl.single(onlyMap);

        assertEquals(1, context.parsingMap().getMapsForResolution().size());
        ControlCallResult<Object> result = DynamicControl.executeStep(
                ", verify \"<controlValue>\" equals \"isolated\"",
                context
        );

        assertTrue(result.successful(), () -> String.valueOf(result.error()));
        assertEquals(before, live.toString());
    }

    @Test
    void overrideWithGlobalsUsesOnlyThoseTwoMappingSources() {
        MappingContext context = MappingControl.overrideWithGlobals(
                Map.of("controlValue", "override")
        );

        assertEquals(2, context.maps().size());
        assertEquals(MapConfigurations.MapType.OVERRIDE_MAP, context.maps().get(0).getMapType());
        assertEquals(MapConfigurations.MapType.GLOBAL_NODE, context.maps().get(1).getMapType());
        assertEquals("override", MappingControl.resolveText(context, "<controlValue>").value());
    }

    @Test
    void scopedLiveOverrideRestoresTheExistingOverrideMap() {
        NodeMap override = MappingProcessor.getOverridesMap();
        assertNotNull(override);
        Object previous = override.get("controlScopedValue");

        var scopeResult = MappingControl.overrideScope(
                Map.of("controlScopedValue", "temporary")
        );
        assertTrue(scopeResult.successful(), () -> String.valueOf(scopeResult.error()));
        try (var ignored = scopeResult.value()) {
            assertEquals("temporary", override.get("controlScopedValue"));
        }

        assertEquals(previous, override.get("controlScopedValue"));
    }

    @Test
    void mappingSnapshotRoundTripsThroughJson() throws Exception {
        MappingContext original = MappingControl.overrideOnly(
                Map.of("snapshotValue", "round-trip")
        );
        MappingSnapshot snapshot = MappingControl.snapshot(original.parsingMap()).value();
        assertNotNull(snapshot);
        Path file = Files.createTempFile("pkb-mapping-", ".json");
        try {
            assertTrue(MappingControl.saveSnapshot(snapshot, file).successful());
            var loaded = MappingControl.loadSnapshot(file);
            assertTrue(loaded.successful(), () -> String.valueOf(loaded.error()));
            var restored = MappingControl.fromSnapshot(loaded.value());
            assertTrue(restored.successful(), () -> String.valueOf(restored.error()));
            assertEquals(
                    "round-trip",
                    MappingControl.resolveText(restored.value(), "<snapshotValue>").value()
            );
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void mappingLookupHookCanRedirectAResolutionKey() {
        MappingContext context = MappingControl.overrideOnly(
                Map.of("actualControlKey", "redirected")
        );
        ControlHookHandler handler = new ControlHookHandler() {
            @Override
            public ControlDecision onHook(tools.dscode.common.control.ControlEvent event) {
                return ControlDecision.CONTINUE;
            }

            @Override
            public Object onValue(ControlValueEvent event) {
                if (event.hook() == ControlHook.BEFORE_MAPPING_LOOKUP
                        && "key".equals(event.role())
                        && "aliasControlKey".equals(event.value())) {
                    return "actualControlKey";
                }
                return event.value();
            }
        };

        String resolved = ControlRuntime.withThreadHandler(
                handler,
                () -> MappingControl.resolveText(context, "<aliasControlKey>").value()
        );

        assertEquals("redirected", resolved);
    }

    @Test
    void mappingResolveHookCanReplaceAResolvedValue() {
        MappingContext context = MappingControl.overrideOnly(
                Map.of("controlHookValue", "original")
        );
        ControlHookHandler handler = new ControlHookHandler() {
            @Override
            public ControlDecision onHook(tools.dscode.common.control.ControlEvent event) {
                return ControlDecision.CONTINUE;
            }

            @Override
            public Object onValue(ControlValueEvent event) {
                if (event.hook() == ControlHook.AFTER_MAPPING_RESOLVE
                        && "result".equals(event.role())
                        && "original".equals(event.value())) {
                    return "modified";
                }
                return event.value();
            }
        };

        String resolved = ControlRuntime.withThreadHandler(
                handler,
                () -> MappingControl.resolveText(context, "<controlHookValue>").value()
        );

        assertEquals("modified", resolved);
    }

    @Test
    void mappingWriteHookCanRewriteKeyAndValue() {
        NodeMap map = MappingControl.nodeMap(MapConfigurations.MapType.OVERRIDE_MAP);
        ControlHookHandler handler = new ControlHookHandler() {
            @Override
            public ControlDecision onHook(tools.dscode.common.control.ControlEvent event) {
                return ControlDecision.CONTINUE;
            }

            @Override
            public Object onValue(ControlValueEvent event) {
                if (event.hook() != ControlHook.BEFORE_MAPPING_WRITE) {
                    return event.value();
                }
                if ("key".equals(event.role()) && "originalKey".equals(event.value())) {
                    return "rewrittenKey";
                }
                if ("value".equals(event.role()) && "originalValue".equals(event.value())) {
                    return "rewrittenValue";
                }
                return event.value();
            }
        };

        ControlRuntime.withThreadHandler(handler, () -> {
            map.put("originalKey", "originalValue");
            return map;
        });

        assertNull(map.get("originalKey"));
        assertEquals("rewrittenValue", map.get("rewrittenKey"));
    }


    @Test
    void liveMappingScopeRestoresExactNodeMapReferences() {
        ParsingMap live = ParsingMap.getRunningParsingMap();
        List<NodeMap> beforeMaps = new ArrayList<>(live.getMaps().values());
        List<MapConfigurations.MapType> beforeOrder = new ArrayList<>(live.keyOrder());
        MappingContext context = MappingControl.overrideOnly(
                Map.of("scopeValue", "isolated")
        );

        var scopeResult = MappingControl.useCurrent(context);
        assertTrue(scopeResult.successful(), () -> String.valueOf(scopeResult.error()));
        try (var ignored = scopeResult.value()) {
            assertEquals(1, live.getMapsForResolution().size());
            assertSame(context.maps().getFirst(), live.getMapsForResolution().getFirst());
        }

        assertEquals(beforeOrder, live.keyOrder());
        List<NodeMap> restored = new ArrayList<>(live.getMaps().values());
        assertEquals(beforeMaps.size(), restored.size());
        for (int index = 0; index < beforeMaps.size(); index++) {
            assertSame(beforeMaps.get(index), restored.get(index));
        }
    }


    @Test
    void mappingHandlerCanInspectOrWriteMappingsWithoutRecursiveHookDispatch() {
        NodeMap map = MappingControl.nodeMap(MapConfigurations.MapType.OVERRIDE_MAP);
        int[] beforeWrites = {0};
        ControlHookHandler handler = new ControlHookHandler() {
            @Override
            public ControlDecision onHook(tools.dscode.common.control.ControlEvent event) {
                if (event.hook() == ControlHook.BEFORE_MAPPING_WRITE) {
                    beforeWrites[0]++;
                    map.put("handlerObservation", "safe");
                }
                return ControlDecision.CONTINUE;
            }
        };

        ControlRuntime.withThreadHandler(handler, () -> {
            map.put("outerWrite", "value");
            return map;
        });

        assertEquals(1, beforeWrites[0]);
        assertEquals("safe", map.get("handlerObservation"));
        assertEquals("value", map.get("outerWrite"));
    }

}
