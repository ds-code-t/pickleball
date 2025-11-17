//package io.cucumber.core.runner;
//
//import io.cucumber.core.gherkin.Feature;
//import io.cucumber.core.gherkin.Pickle;
//import io.cucumber.core.gherkin.Step;
//import io.cucumber.plugin.event.Location;
//import io.cucumber.plugin.event.TestCase;
//
//import java.io.IOException;
//import java.net.URI;
//import java.nio.charset.StandardCharsets;
//import java.nio.file.Files;
//import java.nio.file.Path;
//
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Objects;
//import java.util.Optional;
//
///**
// * Utilities for constructing full Cucumber objects (Feature, Pickle, TestCase, PickleStepTestStep)
// * from strings or existing Pickles, backed by a real Cucumber Runtime environment.
// *
// * Delegates everything to {@link RunnerRuntimeRegistry} + {@link RunnerRuntimeContext}.
// */
//public final class CucumberObjects {
//
//    private CucumberObjects() {}
//
//    // ────────────────────────────── PUBLIC HIGH-LEVEL API ──────────────────────────────
//
//    /** Convenience: Pull pickles from a live Runtime created from CLI args */
//    public static List<Pickle> picklesFromCli(String... cliArgs) {
//        return RunnerRuntimeRegistry.getOrInit(cliArgs).collectPickles();
//    }
//
//    /**
//     * Create a fully matched PickleStepTestStep from a raw step text & glue.
//     */
//    public static PickleStepTestStep createStepFromText(String stepText, String... gluePaths) {
//        Objects.requireNonNull(stepText, "stepText");
//        String feature = minimalFeatureWithSingleStep(stepText);
//        printDebug("@@feature : " + feature);
//        RunnerRuntimeContext ctx = contextForFeatureSource(feature, gluePaths);
//        Pickle pickle = ctx.firstPickle();
//        printDebug("@@pickle : " + pickle.getName());
//        printDebug("@@pickle.getSteps().size() : " + pickle.getSteps().size());
//        return ctx.createStepFromText(pickle, rawStepText(stepText));
//    }
//
//    public static PickleStepTestStep createStepFromTextAndLocation(String stepText, Location location, URI uri, String... gluePaths) {
//        Objects.requireNonNull(stepText, "stepText");
//        String feature = minimalFeatureWithSingleStep(stepText);
//
//        RunnerRuntimeContext ctx = contextForFeatureSource(feature, gluePaths);
//        Pickle pickle = ctx.firstPickle();
//        PickleStepTestStep returnStep = ctx.createStepFromText(pickle, rawStepText(stepText));
//
//        // No HasOverrides cast; just use the helper:
//        OverrideSupport.set(returnStep, "uri", uri);
//        OverrideSupport.set(returnStep.getStep(), "location", location);
//        OverrideSupport.set(returnStep.getStep(), "line", Integer.valueOf(location.getLine()));
//        return returnStep;
//    }
//
//
//    /** Parse features from in-memory DSL */
//    public static List<Feature> featuresFromSource(String featureSource, String... gluePaths) {
//        return contextForFeatureSource(featureSource, gluePaths).loadFeatures();
//    }
//
//    /** Fully filtered, ordered pickles from in-memory DSL */
//    public static List<Pickle> picklesFromSource(String featureSource, String... gluePaths) {
//        return contextForFeatureSource(featureSource, gluePaths).collectPickles();
//    }
//
//    /** Create one TestCase for the first pickle parsed from an in-memory feature */
//    public static TestCase testCaseFromSource(String featureSource, String... gluePaths) {
//        var ctx = contextForFeatureSource(featureSource, gluePaths);
//        Pickle first = ctx.firstPickle();
//        return ctx.createTestCase(first);
//    }
//
//    /** All TestCases for a given in-memory feature */
//    public static List<TestCase> testCasesFromSource(String featureSource, String... gluePaths) {
//        var ctx = contextForFeatureSource(featureSource, gluePaths);
//        List<TestCase> result = new ArrayList<>();
//        for (Pickle p : ctx.collectPickles()) {
//            result.add(ctx.createTestCase(p));
//        }
//        return result;
//    }
//
//    /** Find first pickle by name */
//    public static Optional<Pickle> firstPickleByName(String featureSource, String name, String... gluePaths) {
//        return picklesFromSource(featureSource, gluePaths)
//                .stream()
//                .filter(p -> Objects.equals(p.getName(), name))
//                .findFirst();
//    }
//
//    /** Utility: given an existing pickle, just rebuild scenario state around glue */
//    public static TestCase testCaseFromPickle(Pickle pickle, String... gluePaths) {
//        Objects.requireNonNull(pickle, "pickle");
//        var ctx = contextForGlueOnly(gluePaths);
//        return ctx.createTestCase(pickle);
//    }
//
//    // ────────────────────────────── INTERNAL CONTEXT CREATION ───────────────────────────
//
//    private static RunnerRuntimeContext contextForFeatureSource(String featureSource, String... gluePaths) {
//        Path featureFile = writeTempFeature(featureSource);
//        List<String> args = glueArgs(gluePaths);
//        args.add(featureFile.toAbsolutePath().toString());
//        return RunnerRuntimeRegistry.getOrInit(args.toArray(new String[0]));
//    }
//
//    private static RunnerRuntimeContext contextForGlueOnly(String... gluePaths) {
//        return RunnerRuntimeRegistry.getOrInit(glueArgs(gluePaths).toArray(new String[0]));
//    }
//
//    private static List<String> glueArgs(String... gluePaths) {
//        List<String> args = new ArrayList<>();
//        if (gluePaths != null) {
//            for (String g : gluePaths) {
//                if (g != null && !g.isBlank()) {
//                    args.add("--glue");
//                    args.add(g.trim());
//                }
//            }
//        }
//        return args;
//    }
//
//    private static Path writeTempFeature(String source) {
//        try {
//            Path dir = Files.createTempDirectory("cuke-src-");
//            Path file = dir.resolve("temp.feature");
//            Files.writeString(file, source, StandardCharsets.UTF_8);
//            file.toFile().deleteOnExit();
//            dir.toFile().deleteOnExit();
//            return file;
//        } catch (IOException e) {
//            throw new IllegalStateException("Unable to write temporary .feature file", e);
//        }
//    }
//
//    // ────────────────────────────── GHERKIN HELPERS ──────────────────────────────
//
//    private static String minimalFeatureWithSingleStep(String stepText) {
//        String keyworded = ensureKeyword(stepText);
//        return """
//               Feature: Virtual Feature
//                 Scenario: Virtual Scenario
//                   %s
//               """.formatted(keyworded);
//    }
//
//    private static String ensureKeyword(String stepText) {
//        String t = stepText.strip();
//        if (startsWithKeyword(t)) return t;
//        return "* " + t;
//    }
//
//    private static boolean startsWithKeyword(String t) {
//        return t.startsWith("Given ") || t.startsWith("When ")
//                || t.startsWith("Then ")  || t.startsWith("And ")
//                || t.startsWith("But ")   || t.startsWith("* ");
//    }
//
//    /** Extract raw text for matching (drop keyword or leading '*') */
//    private static String rawStepText(String stepText) {
//        String t = stepText.strip();
//        if (startsWithKeyword(t)) {
//            int idx = t.indexOf(" ");
//            return (idx > 0 && idx + 1 < t.length()) ? t.substring(idx + 1) : "";
//        }
//        return t.startsWith("* ") ? t.substring(2) : t;
//    }
//}
