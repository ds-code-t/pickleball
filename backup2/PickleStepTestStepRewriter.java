//package io.cucumber.core.runner;
//
//import io.cucumber.core.gherkin.Step;
//import io.cucumber.core.runner.util.PickleStepArgUtils;
//import io.cucumber.gherkin.GherkinDialect;
//import io.cucumber.messages.types.PickleDocString;
//import io.cucumber.messages.types.PickleStep;
//import io.cucumber.messages.types.PickleStepArgument;
//import io.cucumber.messages.types.PickleStepType;
//import io.cucumber.messages.types.PickleTable;
//import io.cucumber.plugin.event.Location;
//import tools.dscode.common.mappings.ParsingMap;
//import tools.dscode.pickleruntime.CucumberOptionResolver;
//
//import java.lang.reflect.Constructor;
//import java.lang.reflect.Field;
//import java.net.URI;
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.List;
//import java.util.Objects;
//import java.util.UUID;
//import java.util.function.UnaryOperator;
//
//import static io.cucumber.core.runner.GlobalState.getGherkinDialect;
//import static io.cucumber.core.runner.RunnerRuntimeContext.matchTextWithGlue;
//
///**
// * Clone a PickleStepTestStep, overriding the step text and (optionally) the DocString/DataTable argument,
// * and re-match the step definition using the cached Cucumber runtime (glue).
// * <p>
// * Place this class in the io.cucumber.core.runner package.
// */
//public final class PickleStepTestStepRewriter {
//
//    private static final String GMS_FQN = "io.cucumber.core.gherkin.messages.GherkinMessagesStep";
//
//    private PickleStepTestStepRewriter() {
//    }
//
//    /* ───────────────────────────────
//     * Public API (two overloads)
//     * ─────────────────────────────── */
//
//    public static PickleStepTestStep resolveAndClone(PickleStepTestStep original,String stepText,  ParsingMap parsingMap) {
//        PickleStepArgument pickleStepArgument = original.getPickleStep().getArgument().orElse(null);
//        return resolveAndClone(original, stepText, pickleStepArgument, parsingMap);
//    }
//
//    public static PickleStepTestStep resolveAndClone(PickleStepTestStep original, ParsingMap parsingMap) {
//        PickleStepArgument pickleStepArgument = original.getPickleStep().getArgument().orElse(null);
//        return resolveAndClone(original, original.getStepText(), pickleStepArgument, parsingMap);
//    }
//
//    public static PickleStepTestStep resolveAndClone(PickleStepTestStep original, String stepText, PickleStepArgument pickleStepArgument, ParsingMap parsingMap) {
//        String newStepText = parsingMap.resolveWholeText(stepText);
//        PickleStepArgument newPickleStepArgument = null;
//        if (pickleStepArgument != null) {
//            UnaryOperator<String> external = parsingMap::resolveWholeText;
//            newPickleStepArgument = PickleStepArgUtils.transformPickleArgument(pickleStepArgument, external);
//        }
//        return cloneWithOverride(original, newStepText, newPickleStepArgument);
//    }
//
//    /**
//     * Keep the original argument; only change the text and re-match.
//     */
//    public static PickleStepTestStep cloneWithOverride(
//            PickleStepTestStep original,
//            String newStepText,
//            String... cliArgs
//    ) {
//        return cloneInternal(original, newStepText, /*newArgOrNull*/ null, /*overrideArg*/ false, cliArgs);
//    }
//
//    /**
//     * Override DocString/DataTable (or remove it by passing null).
//     * newArgOrNull must be PickleDocString, PickleTable, or null.
//     */
//    public static PickleStepTestStep cloneWithOverride(
//            PickleStepTestStep original,
//            String newStepText,
//            Object newArgOrNull,
//            String... cliArgs
//    ) {
//        return cloneInternal(original, newStepText, newArgOrNull, /*overrideArg*/ true, cliArgs);
//    }
//
//    /* ───────────────────────────────
//     * Implementation
//     * ─────────────────────────────── */
//
//    private static PickleStepTestStep cloneInternal(
//            PickleStepTestStep original,
//            String newStepText,
//            Object newArgOrNull,
//            boolean overrideArg,
//            String[] cliArgs
//    ) {
//        Objects.requireNonNull(original, "original");
//        Objects.requireNonNull(newStepText, "newStepText");
//
//        // Preserve identity & wiring from original
//        UUID id = (UUID) readFieldFromHierarchy(original, "id"); // from TestStep
//        URI uri = original.getUri();
//        Step origStep = original.getStep();
//        Location location = origStep.getLocation();
//        String keyword = origStep.getKeyword();
//
//        // Prepare new PickleStep content
//        String rawText = rawStepText(newStepText);
//        PickleStepType type = extractPickleStepType(origStep);
//        PickleStepArgument argument = overrideArg
//                ? mapOverrideArgument(newArgOrNull)               // may be null to remove
//                : originalPickle(origStep).getArgument().orElse(null);
//
//        // Reuse astNodeIds & id from original inner PickleStep
//        PickleStep originalPickle = originalPickle(origStep);
//        List<String> astNodeIdsCopy = new ArrayList<>(originalPickle.getAstNodeIds());
//        String idCopy = originalPickle.getId();
//
//        PickleStep newPickle = new PickleStep(
//                argument,
//                astNodeIdsCopy,
//                idCopy,
//                type,
//                rawText
//        );
//
//        // Build a dialect so the new GMS evaluates the same StepType bucket
//        GherkinDialect dialect = getGherkinDialect();
//
//        // Rebuild the private GherkinMessagesStep wrapper around our new PickleStep
//        Step newStep = rebuildGherkinMessagesStep(newPickle, dialect,
//                origStep.getPreviousGivenWhenThenKeyword(), location, keyword);
////
////        // Resolve a new definition match for the NEW text via the cached runtime
////        RunnerRuntimeContext ctx = getGlobalContext(cliArgs);
////        PickleStepTestStep matched = ctx.createStepFromText(ctx.firstPickle(), rawText);
////        PickleStepDefinitionMatch newDef = matched.getDefinitionMatch();
//
//        PickleStepDefinitionMatch newDef = matchTextWithGlue(rawText, cliArgs);
//
//        // Preserve hooks
//        List<HookTestStep> before = new ArrayList<>(original.getBeforeStepHookSteps());
//        List<HookTestStep> after = new ArrayList<>(original.getAfterStepHookSteps());
//
//        // Construct the final clone
//        return new PickleStepTestStep(id, uri, newStep, before, after, newDef);
//    }
//
//    /* ───────────────────────────────
//     * Helpers
//     * ─────────────────────────────── */
//
//    private static PickleStepType extractPickleStepType(Step step) {
//        try {
//            return originalPickle(step).getType().orElse(null);
//        } catch (Exception ignore) {
//            return null;
//        }
//    }
//
//    private static PickleStepArgument mapOverrideArgument(Object newArgOrNull) {
//        if (newArgOrNull == null) return null;
//        if (newArgOrNull instanceof PickleDocString ds) {
//            return new PickleStepArgument(ds, null);
//        }
//        if (newArgOrNull instanceof PickleTable tbl) {
//            return new PickleStepArgument(null, tbl);
//        }
//        throw new IllegalArgumentException(
//                "Argument must be PickleDocString, PickleTable, or null. Was: " + newArgOrNull.getClass());
//    }
//
//
//    private static Step rebuildGherkinMessagesStep(
//            PickleStep pickle,
//            GherkinDialect dialect,
//            String previousGwtKeyword,
//            Location location,
//            String keyword
//    ) {
//        try {
//            Class<?> gmsClass = Class.forName(GMS_FQN);
//            Constructor<?> ctor = gmsClass.getDeclaredConstructor(
//                    PickleStep.class, GherkinDialect.class, String.class, Location.class, String.class
//            );
//            ctor.setAccessible(true);
//            return (Step) ctor.newInstance(pickle, dialect, previousGwtKeyword, location, keyword);
//        } catch (ReflectiveOperationException e) {
//            throw new IllegalStateException("Failed to rebuild GherkinMessagesStep", e);
//        }
//    }
//
//    private static PickleStep originalPickle(Step step) {
//        if (!step.getClass().getName().equals(GMS_FQN)) {
//            throw new IllegalArgumentException(
//                    "Unsupported Step type. Expected " + GMS_FQN + " but was " + step.getClass());
//        }
//        try {
//            Field f = step.getClass().getDeclaredField("pickleStep");
//            f.setAccessible(true);
//            return (PickleStep) f.get(step);
//        } catch (ReflectiveOperationException e) {
//            throw new IllegalStateException("Cannot access GherkinMessagesStep.pickleStep", e);
//        }
//    }
//
//    private static String rawStepText(String stepText) {
//        String t = stepText.strip();
//        if (startsWithKeyword(t)) {
//            int idx = t.indexOf(' ');
//            return (idx > 0 && idx + 1 < t.length()) ? t.substring(idx + 1) : "";
//        }
//        return t.startsWith("* ") ? t.substring(2) : t;
//    }
//
//    private static boolean startsWithKeyword(String t) {
//        return t.startsWith("Given ") || t.startsWith("When ")
//                || t.startsWith("Then ") || t.startsWith("And ")
//                || t.startsWith("But ") || t.startsWith("* ");
//    }
//
//    private static Object readFieldFromHierarchy(Object target, String fieldName) {
//        Class<?> c = target.getClass();
//        while (c != null && c != Object.class) {
//            try {
//                Field f = c.getDeclaredField(fieldName);
//                f.setAccessible(true);
//                return f.get(target);
//            } catch (NoSuchFieldException ignored) {
//                c = c.getSuperclass();
//            } catch (ReflectiveOperationException e) {
//                throw new IllegalStateException("Cannot read field '" + fieldName + "'", e);
//            }
//        }
//        throw new IllegalStateException("Field '" + fieldName + "' not found on " + target.getClass());
//    }
//
//    /* ───────────────────────── Cached runtime/glue ───────────────────────── */
//
//    // Uses your global accessor pattern (RunnerRuntimeRegistry cache)
//    private static RunnerRuntimeContext getGlobalContext(String[] cliArgs) {
//        printDebug("@cliArgs: " + Arrays.toString(cliArgs));
//        if (cliArgs == null || cliArgs.length == 0)
//            return CucumberOptionResolver.getGlobalContext();
//        return RunnerRuntimeRegistry.getOrInit(cliArgs);
//    }
//
//
//}
