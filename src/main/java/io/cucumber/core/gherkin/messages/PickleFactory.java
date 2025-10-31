//package io.cucumber.core.gherkin.messages;
//
//import io.cucumber.gherkin.GherkinDialect;
//import io.cucumber.gherkin.GherkinDialects;
//import io.cucumber.messages.types.PickleStep;
//import io.cucumber.messages.types.PickleStepArgument;
//import io.cucumber.messages.types.PickleTag;
//
//import java.net.URI;
//import java.util.List;
//import java.util.Objects;
//import java.util.UUID;
//
//import static io.cucumber.core.runner.GlobalState.getGherkinDialect;
//import static io.cucumber.core.runner.GlobalState.getLanguage;
//
///**
// * Factory utilities for creating Cucumber "messages" Pickles and the
// * wrapper {@link GherkinMessagesPickle}.
// */
//public final class PickleFactory {
//
//    private PickleFactory() {}
//
//    /** Build a wrapper by first creating a messages Pickle, then wrapping it. */
//    public static GherkinMessagesPickle createGherkinMessagesPickle(
//            final String id,
//            final URI uri,
//            final String name,
//            final String language,
//            final List<PickleStep> steps,
//            final List<PickleTag> tags,
//            final List<String> astNodeIds,
//            final GherkinDialect dialect,
//            final CucumberQuery cucumberQuery
//    ) {
//        Objects.requireNonNull(uri, "uri");
//        final io.cucumber.messages.types.Pickle messagesPickle =
//                createMessagesPickle(id, uri.toString(), name, language, steps, tags, astNodeIds);
//        // Adjust parameter order here if your local constructor differs.
//        return new GherkinMessagesPickle(messagesPickle, uri, dialect, cucumberQuery);
//    }
//
//    /** Build a plain messages Pickle. */
//    public static io.cucumber.messages.types.Pickle createMessagesPickle(
//            final String id,
//            final String uri,
//            final String name,
//            final String language,
//            final List<PickleStep> steps,
//            final List<PickleTag> tags,
//            final List<String> astNodeIds
//    ) {
//        return new io.cucumber.messages.types.Pickle(
//                Objects.requireNonNull(id, "id"),
//                Objects.requireNonNull(uri, "uri"),
//                Objects.requireNonNull(name, "name"),
//                Objects.requireNonNull(language, "language"),
//                Objects.requireNonNull(steps, "steps"),
//                Objects.requireNonNull(tags, "tags"),
//                Objects.requireNonNull(astNodeIds, "astNodeIds")
//        );
//    }
//
//    /**
//     * Convenience: build a {@link GherkinMessagesPickle} for a pickle that has exactly one step.
//     * You provide the step text and (optionally) a {@link PickleStepArgument} (DocString/DataTable).
//     * Everything else is filled with safe defaults.
//     *
//     * Defaults:
//     *  - id: random UUID
//     *  - uri: "file://generated.feature"
//     *  - name: "Generated Scenario"
//     *  - language: "en"
//     *  - tags: empty
//     *  - astNodeIds: empty
//     *  - dialect: GherkinDialects.getDialect(language) or "en"
//     *  - cucumberQuery: new CucumberQuery()
//     */
//    public static GherkinMessagesPickle createOneStepGherkinMessagesPickle(
//            String stepText, PickleStepArgument arg
//    ) {
//        String stepAstId = UUID.randomUUID().toString();
//        PickleStep step = new PickleStep(
//                arg,
//                List.of(stepAstId),                 // <-- not empty
//                UUID.randomUUID().toString(),       // step id
//                null,
//                stepText
//        );
//        Objects.requireNonNull(stepText, "stepText");
//
//        // Build minimal PickleStep using the correct constructor:
//        // (argument, astNodeIds, id, type, text)
//        final PickleStep oneStep = new PickleStep(
//                stepArgumentOrNull,          // PickleStepArgument (nullable)
//                List.of(),                   // astNodeIds
//                UUID.randomUUID().toString(),// step id
//                /* type */ null,             // or PickleStepType.UNKNOWN if available in your build
//                stepText                     // text
//        );
//
//        // Minimal messages Pickle (one step)
//        final String pickleId   = UUID.randomUUID().toString();
//        final URI    uri        = URI.create("file://generated.feature");
//        final String name       = "Generated Scenario";
//        final String language   = getLanguage();
//        final List<PickleStep> steps = List.of(oneStep);
//        final List<PickleTag>  tags  = List.of();
//        final List<String>     astNodeIds = List.of();
//
//        final GherkinDialect dialect = getGherkinDialect();
//
//        CucumberQuery query = new CucumberQuery();
//
//        // PSEUDO: register so getStepBy(stepAstId) returns 1 element.
//        // Adjust to your actual API, e.g. query.addStep(stepAstId, keyword, line, …)
//        query.registerStepKeyword(stepAstId, /*keyword*/ dialect.getGivenKeywords().getFirst(),
//                /*line*/ 1, /*uri*/ "file://generated.feature");
//
//        return createGherkinMessagesPickle(
//                pickleId, uri, name, language, steps, tags, astNodeIds, dialect, query
//        );
//    }
//}
