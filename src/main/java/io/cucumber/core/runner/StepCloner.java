package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Step;
import io.cucumber.gherkin.GherkinDialect;
import io.cucumber.gherkin.GherkinDialectProvider;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.messages.types.PickleStepType;
import io.cucumber.plugin.event.Location;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.*;

/**
 * StepCloner: clone utilities that rebuild new instances via StepBuilder
 * rather than copying references. Supports a shared overrides map:
 *
 * Recognized override keys (use the same map for all 3 clone methods):
 *   Common / PickleStep:
 *     "text" (String), "argument" (PickleStepArgument),
 *     "astNodeIds" (List<String>), "id" (String), "type" (PickleStepType)
 *
 *   GherkinMessagesStep (returned as Step):
 *     "keyword" (String), "previousGwtKeyword" (String),
 *     "location" (Location), "dialect" (GherkinDialect),
 *     "pickleStep" (PickleStep)  // if provided, used directly
 *
 *   PickleStepTestStep:
 *     All of the above + "uri" (URI), "gluePaths" (String[])
 *
 * Notes:
 * - We accept/return the public Step interface for the middle layer. Internally
 *   we reflect into GherkinMessagesStep to retrieve the underlying PickleStep.
 * - An override key that exists with a null value explicitly nulls that field.
 * - If no "dialect" override is provided, the default dialect is used.
 */
public final class StepCloner {

    private StepCloner() {}

    /* ======================================================================
     * Public API: Clone PickleStep
     * ====================================================================== */

    public static PickleStep clonePickleStep(PickleStep original, Map<String, Object> overrides) {
        Objects.requireNonNull(original, "original PickleStep");

        final String text = pick(overrides, "text", original.getText());
        final PickleStepArgument argument = pick(overrides, "argument", original.getArgument().orElse(null));
        @SuppressWarnings("unchecked")
        final List<String> astNodeIds = copyListOrNull(pick(overrides, "astNodeIds", original.getAstNodeIds()));
        final String id = pick(overrides, "id", original.getId());
        final PickleStepType type = pick(overrides, "type", original.getType().orElse(null));

        return StepBuilder.buildPickleStep(text, argument, safeList(astNodeIds), id, type);
    }

    /* Convenience: override only text */
    public static PickleStep clonePickleStep(PickleStep original, String newText) {
        Map<String, Object> map = new HashMap<>();
        map.put("text", newText);
        return clonePickleStep(original, map);
    }

    /* Convenience: override text + argument */
    public static PickleStep clonePickleStep(PickleStep original, String newText, PickleStepArgument newArg) {
        Map<String, Object> map = new HashMap<>();
        map.put("text", newText);
        map.put("argument", newArg);
        return clonePickleStep(original, map);
    }

    /* ======================================================================
     * Public API: Clone GherkinMessagesStep (as Step)
     * ====================================================================== */

    /**
     * Clones a Step (backed by GherkinMessagesStep). We reflect the underlying
     * PickleStep if available; otherwise we reconstruct from the Step’s visible data.
     */
    public static Step cloneGherkinMessagesStepAsStep(Step original, Map<String, Object> overrides) {
        Objects.requireNonNull(original, "original Step");

        final String keyword = pick(overrides, "keyword", original.getKeyword());
        final String previousGwt = pick(overrides, "previousGwtKeyword", original.getPreviousGivenWhenThenKeyword());
        final Location location = pick(overrides, "location", original.getLocation());
        GherkinDialect dialect = pick(overrides, "dialect", null);
        if (dialect == null) {
            dialect = new GherkinDialectProvider().getDefaultDialect();
        }

        // Prefer explicit override for the PickleStep if given
        PickleStep sourcePickle = pick(overrides, "pickleStep", null);
        if (sourcePickle == null) {
            sourcePickle = extractUnderlyingPickleStep(original);
            if (sourcePickle == null) {
                // fallback reconstruction: text + (no argument/type/ids)
                sourcePickle = StepBuilder.buildPickleStep(original.getText(), null);
            }
        }

        // Allow PickleStep-level overrides to cascade through (text, argument, etc.)
        PickleStep clonedPickle = clonePickleStep(sourcePickle, overrides);

        return StepBuilder.buildGherkinMessagesStepAsStep(clonedPickle, location, keyword, previousGwt, dialect);
    }

    /* Convenience: override only text */
    public static Step cloneGherkinMessagesStepAsStep(Step original, String newText) {
        Map<String, Object> map = new HashMap<>();
        map.put("text", newText);
        return cloneGherkinMessagesStepAsStep(original, map);
    }

    /* Convenience: override text + PickleStepArgument */
    public static Step cloneGherkinMessagesStepAsStep(Step original, String newText, PickleStepArgument newArg) {
        Map<String, Object> map = new HashMap<>();
        map.put("text", newText);
        map.put("argument", newArg);
        return cloneGherkinMessagesStepAsStep(original, map);
    }

    /* ======================================================================
     * Public API: Clone PickleStepTestStep
     * ====================================================================== */

    /**
     * Fully clones a PickleStepTestStep by rebuilding:
     *  - underlying PickleStep
     *  - Step (GherkinMessagesStep) wrapper
     *  - PickleStepTestStep with a (re)matched PickleStepDefinitionMatch via StepBuilder
     *
     * Required values (derived unless overridden):
     *   stepText, keyword, previousGwtKeyword, location, uri, gluePaths[]
     */
    public static PickleStepTestStep clonePickleStepTestStep(
            PickleStepTestStep original,
            Map<String, Object> overrides
    ) {
        Objects.requireNonNull(original, "original PickleStepTestStep");

        // Extract original state via reflection
        final Step origStep = getField(original, "step", Step.class);
        final URI origUri = getField(original, "uri", URI.class);

        // Derive values from original
        final String origText = origStep != null ? origStep.getText() : null;
        final String origKeyword = origStep != null ? origStep.getKeyword() : "Given";
        final String origPrevGwt = origStep != null ? origStep.getPreviousGivenWhenThenKeyword() : "";
        final Location origLoc = origStep != null ? origStep.getLocation() : new Location(1, 1);

        // Apply overrides (explicit null honored when key is present)
        final String stepText = pick(overrides, "text", origText);
        final String keyword = pick(overrides, "keyword", origKeyword);
        final String prevGwt = pick(overrides, "previousGwtKeyword", origPrevGwt);
        final Location location = pick(overrides, "location", origLoc);
        final URI uri = pick(overrides, "uri", origUri);

        // Build/clone the underlying PickleStep (allow cascading overrides)
        PickleStep underlying = extractUnderlyingPickleStep(origStep);
        if (underlying == null) {
            // Fallback if we couldn't reflect the internal field
            underlying = StepBuilder.buildPickleStep(origText != null ? origText : stepText, null);
        }
        PickleStep clonedPickle = clonePickleStep(underlying, overrides);

        // Now rebuild the Step wrapper (as public Step)
        Step clonedStep = StepCloner.cloneGherkinMessagesStepAsStep(
                // Provide a synthetic Step built from the original Pickle for consistent defaults
                StepBuilder.buildGherkinMessagesStepAsStep(underlying, origLoc, origKeyword, origPrevGwt, null),
                // Pass through the same overrides
                overrides
        );

        // Glue paths for (re)matching
        String[] gluePaths = pick(overrides, "gluePaths", null);
        if (gluePaths == null) {
            gluePaths = new String[0];
        }

        // Build final PickleStepTestStep via StepBuilder
        // We also allow PickleStepArgument override to flow from the map
        PickleStepArgument arg = clonedPickle.getArgument().orElse(null);

        return StepBuilder.buildPickleStepTestStep(
                stepText, uri, location, arg, keyword, prevGwt,
                clonedPickle.getAstNodeIds(), clonedPickle.getId(), clonedPickle.getType().orElse(null),
                gluePaths
        );
    }

    /* Convenience: override only text (and optional glue paths) */
    public static PickleStepTestStep clonePickleStepTestStep(
            PickleStepTestStep original, String newText, String... gluePaths
    ) {
        Map<String, Object> map = new HashMap<>();
        map.put("text", newText);
        if (gluePaths != null && gluePaths.length > 0) {
            map.put("gluePaths", gluePaths);
        }
        return clonePickleStepTestStep(original, map);
    }

    /* Convenience: override text + argument (and optional glue paths) */
    public static PickleStepTestStep clonePickleStepTestStep(
            PickleStepTestStep original, String newText, PickleStepArgument newArg, String... gluePaths
    ) {
        Map<String, Object> map = new HashMap<>();
        map.put("text", newText);
        map.put("argument", newArg);
        if (gluePaths != null && gluePaths.length > 0) {
            map.put("gluePaths", gluePaths);
        }
        return clonePickleStepTestStep(original, map);
    }

    /* ======================================================================
     * Helpers
     * ====================================================================== */

    /** Honor explicit-null overrides: if key is present, return its (possibly null) value; else defaultVal. */
    @SuppressWarnings("unchecked")
    private static <T> T pick(Map<String, Object> overrides, String key, T defaultVal) {
        if (overrides != null && overrides.containsKey(key)) {
            return (T) overrides.get(key);
        }
        return defaultVal;
    }

    private static <T> T getField(Object target, String name, Class<T> type) {
        if (target == null) return null;
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(target);
                return type.cast(v);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot access field: " + name, e);
            }
        }
        return null;
    }

    /** Try to extract the underlying PickleStep from a GherkinMessagesStep via reflection. */
    private static PickleStep extractUnderlyingPickleStep(Step step) {
        if (step == null) return null;
        // Only the specific impl has this field.
        if (!"io.cucumber.core.gherkin.messages.GherkinMessagesStep".equals(step.getClass().getName())) {
            return null;
        }
        return getField(step, "pickleStep", PickleStep.class);
    }

    private static <T> List<T> copyListOrNull(List<T> src) {
        return src == null ? null : new ArrayList<>(src);
    }

    private static <T> List<T> safeList(List<T> src) {
        return src == null ? Collections.emptyList() : src;
    }
}
