//package io.cucumber.core.runner;
//
//import io.cucumber.core.gherkin.Step;
//import io.cucumber.gherkin.GherkinDialect;
//import io.cucumber.gherkin.GherkinDialectProvider;
//import io.cucumber.messages.types.PickleStep;
//import io.cucumber.messages.types.PickleStepArgument;
//import io.cucumber.messages.types.PickleStepType;
//import io.cucumber.plugin.event.Location;
//
//import java.lang.reflect.Constructor;
//import java.lang.reflect.InvocationTargetException;
//import java.net.URI;
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.List;
//import java.util.Objects;
//import java.util.UUID;
//import java.util.stream.Stream;
//
//import static io.cucumber.core.runner.GlobalState.getGivenKeyword;
//import static io.cucumber.core.runner.RunnerRuntimeContext.findPickleStepDefinitionMatch;
//import static java.util.Collections.emptyList;
//// Adjust this import to wherever your helper lives:
//
///**
// * Builders for Cucumber step objects without exposing internal classes.
// *
// * Build order:
// *   PickleStep  -> Step (backed by GherkinMessagesStep) -> PickleStepTestStep
// *
// * Notes:
// * - We construct the internal GherkinMessagesStep reflectively and
// *   return it as the public io.cucumber.core.gherkin.Step interface.
// * - We resolve PickleStepDefinitionMatch via your existing
// *   matchTextWithGlue(stepText, gluePaths) utility.
// */
//public final class StepBuilder {
//
//    private StepBuilder() {}
//
//    /* ============================================================
//     * PickleStep builders
//     * ============================================================ */
//
//    public static PickleStep buildPickleStep(
//            String stepText,
//            PickleStepArgument argument,
//            List<String> astNodeIds,
//            String id,
//            PickleStepType type
//    ) {
//        Objects.requireNonNull(stepText, "stepText");
//        return new PickleStep(
//                argument,
//                astNodeIds != null ? new ArrayList<>(astNodeIds) : emptyList(),
//                id != null ? id : UUID.randomUUID().toString(),
//                type, // may be null
//                stepText
//        );
//    }
//
//    public static PickleStep buildPickleStep(String stepText, PickleStepArgument argument) {
//        return buildPickleStep(stepText, argument, emptyList(), null, null);
//    }
//
//    /* ============================================================
//     * Step (backed by GherkinMessagesStep) builders
//     * ============================================================ */
//
//    /**
//     * Builds a {@link Step} backed by the internal
//     * io.cucumber.core.gherkin.messages.GherkinMessagesStep using reflection.
//     */
//    public static Step buildGherkinMessagesStepAsStep(
//            PickleStep pickleStep,
//            Location location,
//            String keyword,
//            String previousGwtKeyword,
//            GherkinDialect dialect
//    ) {
//        Objects.requireNonNull(pickleStep, "pickleStep");
//        Objects.requireNonNull(location, "location");
//        Objects.requireNonNull(keyword, "keyword");
//
//        if (dialect == null) {
//            dialect = new GherkinDialectProvider().getDefaultDialect();
//        }
//        if (previousGwtKeyword == null) {
//            previousGwtKeyword = "";
//        }
//
//        try {
//            // Constructor signature:
//            // (io.cucumber.messages.types.PickleStep,
//            //  io.cucumber.gherkin.GherkinDialect,
//            //  java.lang.String previousGwt,
//            //  io.cucumber.plugin.event.Location,
//            //  java.lang.String keyword)
//            Class<?> impl = Class.forName("io.cucumber.core.gherkin.messages.GherkinMessagesStep");
//            Constructor<?> ctor = impl.getDeclaredConstructor(
//                    io.cucumber.messages.types.PickleStep.class,
//                    io.cucumber.gherkin.GherkinDialect.class,
//                    String.class,
//                    io.cucumber.plugin.event.Location.class,
//                    String.class
//            );
//            ctor.setAccessible(true);
//            Object instance = ctor.newInstance(pickleStep, dialect, previousGwtKeyword, location, keyword);
//            return (Step) instance;
//        } catch (ClassNotFoundException |
//                 NoSuchMethodException |
//                 InstantiationException |
//                 IllegalAccessException |
//                 InvocationTargetException e) {
//            throw new IllegalStateException("Could not construct GherkinMessagesStep reflectively", e);
//        }
//    }
//
//    public static Step buildGherkinMessagesStepAsStep(
//            PickleStep pickleStep,
//            Location location,
//            String keyword
//    ) {
//        return buildGherkinMessagesStepAsStep(pickleStep, location, keyword, "", null);
//    }
//
//    /* ============================================================
//     * PickleStepTestStep builders
//     * ============================================================ */
//
//    /**
//     * High-level builder that:
//     *  1) builds PickleStep
//     *  2) builds Step (backed by GherkinMessagesStep)
//     *  3) resolves PickleStepDefinitionMatch via matchTextWithGlue(stepText, gluePaths)
//     *  4) constructs PickleStepTestStep
//     */
//    public static PickleStepTestStep buildPickleStepTestStep(
//            String stepText,
//            URI uri,
//            Location location,
//            PickleStepArgument argument,
//            String keyword,
//            String previousGwtKeyword,
//            List<String> astNodeIds,
//            String id,
//            PickleStepType type,
//            String... gluePaths
//    ) {
//        Objects.requireNonNull(stepText, "stepText");
//        Objects.requireNonNull(uri, "uri");
//        Objects.requireNonNull(location, "location");
//        Objects.requireNonNull(keyword, "keyword");
//
//        PickleStep pickleStep = buildPickleStep(stepText, argument, astNodeIds, id, type);
//        Step step = buildGherkinMessagesStepAsStep(pickleStep, location, keyword, previousGwtKeyword, null);
//
//        PickleStepDefinitionMatch match = findPickleStepDefinitionMatch(step, Arrays.stream(gluePaths)
//                .flatMap(p -> Stream.of("--glue", p))
//                .toArray(String[]::new));

//        return constructPickleStepTestStep(uri, match, step);
//    }
//
//    public static PickleStepTestStep buildPickleStepTestStep(
//            String stepText,
//            URI uri,
//            Location location,
//            PickleStepArgument argument,
//            String keyword,
//            String... gluePaths
//    ) {
//        return buildPickleStepTestStep(
//                stepText, uri, location, argument, keyword,
//                "", emptyList(), null, null, gluePaths
//        );
//    }
//
//    /* ============================================================
//     * Internal helpers
//     * ============================================================ */
//
//    private static PickleStepTestStep constructPickleStepTestStep(
//            URI uri,
//            PickleStepDefinitionMatch match,
//            Step step
//    ) {
//        return  new PickleStepTestStep(UUID.randomUUID(), uri, step, match);
//    }
//
//
//    /* ======================================================================
//     * Convenience: ultra-simple creation with minimal input
//     * ====================================================================== */
//
//    /**
//     * Builds a basic PickleStepTestStep using only step text.
//     * Delegates to the second overload with a null PickleStepArgument.
//     */
//    public static PickleStepTestStep buildPickleStepTestStep(String stepText, String... gluePaths) {
//        return buildPickleStepTestStep(stepText, null, gluePaths);
//    }
//
//    /**
//     * Builds a basic PickleStepTestStep using step text and an optional PickleStepArgument.
//     * Other required fields (URI, keyword, location, etc.) are filled with defaults.
//     */
//    public static PickleStepTestStep buildPickleStepTestStep(
//            String stepText,
//            PickleStepArgument argument,
//            String... gluePaths
//    ) {
//        URI uri = URI.create("file:///default.feature");
//        Location location = new Location(1, 1);
//
//        // Use the first Given keyword from the current dialect, defaulting to "Given"
//        String keyword;

//        try {
//            keyword = getGivenKeyword();
//        } catch (Throwable t) {
//            keyword = "Given ";
//        }

//        return buildPickleStepTestStep(
//                stepText,
//                uri,
//                location,
//                argument,
//                keyword,
//                keyword,                       // previous GWT keyword
//                List.of(),                // astNodeIds
//                UUID.randomUUID().toString(),
//                null,                     // PickleStepType
//                gluePaths == null ? new String[0] : gluePaths
//        );
//    }
//
//
//}
