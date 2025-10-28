package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Utilities to create fully functional Cucumber objects (Feature, Pickle, TestCase, PickleStepTestStep)
 * from in-memory strings, backed by a real Runner/Runtime via {@link RunnerRuntimeRegistry}.
 *
 * No mocks, no dummies: we write a temporary .feature file, initialize a Cucumber context with the
 * given glue paths, then use the real Cucumber pipeline to produce objects.
 *
 * NOTE:
 *  - For {@link #createStepFromText(String, String...)}, you must have woven the ITD:
 *      - Runner implements RunnerExtras
 *      - Runner has createStepForText(Pickle,String)
 *    so we can call ((RunnerExtras)(Object) runner).createStepForText(...)
 */
public final class CucumberObjects {

    private CucumberObjects() {}

    /* ───────────────────────────────────────────── Public API ───────────────────────────────────────────── */

    /**
     * Create a fully matched {@link PickleStepTestStep} from a single Gherkin step text and glue paths.
     *
     * We synthesize a minimal .feature that contains exactly one scenario with the provided step,
     * initialize a Runner/Runtime with the given glue, and ask the woven Runner method to match it.
     *
     * @param stepText   e.g. "Given I log in" or "@___ROOTSTEP_" or "I log in"
     *                   (if no keyword is included, "*" is used so any step definition can match)
     * @param gluePaths  one or more glue packages (e.g., "tools.dscode.coredefinitions")
     */
    public static PickleStepTestStep createStepFromText(String stepText, String... gluePaths) {
        Objects.requireNonNull(stepText, "stepText");
        String featureSrc = minimalFeatureWithSingleStep(stepText);

        var ctx = contextForFeatureSource(featureSrc, gluePaths);
        Pickle pickle = ctx.firstPickle()
                .orElseThrow(() -> new IllegalStateException("No pickle produced from synthesized feature"));

        // Use the ITD to construct a fully matched PickleStepTestStep
        return ((RunnerExtras) (Object) ctx.runner).createStepForText(pickle, rawStepText(stepText));
    }

    /** Parse a Feature from an in-memory string + glue paths (for proper type resolution). */
    public static List<Feature> featuresFromSource(String featureSource, String... gluePaths) {
        return contextForFeatureSource(featureSource, gluePaths).loadFeatures();
    }

    /** Collect Pickles from an in-memory feature string, honoring any filters/order from default options. */
    public static List<Pickle> picklesFromSource(String featureSource, String... gluePaths) {
        return contextForFeatureSource(featureSource, gluePaths).collectPickles();
    }

    /** Create a {@link TestCase} for the first Pickle in the in-memory feature. */
    public static TestCase testCaseFromSource(String featureSource, String... gluePaths) {
        var ctx = contextForFeatureSource(featureSource, gluePaths);
        Pickle p = ctx.firstPickle()
                .orElseThrow(() -> new IllegalStateException("No pickle produced from feature source"));
        return ctx.createTestCase(p);
    }

    /** Create {@link TestCase}s for all Pickles produced by the in-memory feature. */
    public static List<TestCase> testCasesFromSource(String featureSource, String... gluePaths) {
        var ctx = contextForFeatureSource(featureSource, gluePaths);
        return ctx.createTestCases();
    }

    /** Convenience: first Pickle by exact name. */
    public static Optional<Pickle> firstPickleByName(String featureSource, String name, String... gluePaths) {
        return contextForFeatureSource(featureSource, gluePaths).picklesByName(name).stream().findFirst();
    }

    /* ───────────────────────────────────────── Internal plumbing ───────────────────────────────────────── */

    /**
     * Build or reuse a Runner/Runtime context keyed by normalized args:
     *   [--glue <each glue>] <tempFeaturePath>
     */
    private static RunnerRuntimeContext contextForFeatureSource(String featureSource, String... gluePaths) {
        Path featurePath = writeTempFeature(featureSource);
        List<String> args = new ArrayList<>();
        if (gluePaths != null) {
            for (String g : gluePaths) {
                if (g != null && !g.isBlank()) {
                    args.add("--glue");
                    args.add(g.trim());
                }
            }
        }
        args.add(featurePath.toAbsolutePath().toString());
        return RunnerRuntimeRegistry.getOrInit(args.toArray(new String[0]));
    }

    /** Write an ephemeral .feature file (deleted on JVM exit). */
    private static Path writeTempFeature(String featureSource) {
        try {
            Path dir = Files.createTempDirectory("cuke-src-");
            Path file = dir.resolve("temp.feature");
            Files.writeString(file, featureSource, StandardCharsets.UTF_8);
            // best-effort cleanup
            file.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Failed writing temporary .feature file", e);
        }
    }

    /** Build a minimal, valid feature with one scenario and exactly one step. */
    private static String minimalFeatureWithSingleStep(String stepText) {
        String keyworded = ensureKeyword(stepText);
        return """
               Feature: Generated Feature
                 Scenario: Generated Scenario
                   %s
               """.formatted(keyworded);
    }

    /** If the user didn't include a keyword, prefix with '*' (valid Gherkin wildcard). */
    private static String ensureKeyword(String stepText) {
        String t = stepText.strip();
        if (startsWithKeyword(t)) return t;
        return "* " + t;
    }

    /** The woven Runner helper expects raw step text (without the keyword). */
    private static String rawStepText(String stepText) {
        String t = stepText.strip();
        if (startsWithKeyword(t)) {
            int idx = t.indexOf(' ');
            return (idx > 0 && idx + 1 < t.length()) ? t.substring(idx + 1) : "";
        }
        // if "*" prefixed or no keyword, treat the whole thing as raw text
        return t.startsWith("* ") ? t.substring(2) : t;
    }

    private static boolean startsWithKeyword(String t) {
        // Keep it simple (case-sensitive standard English keywords and '*')
        return t.startsWith("Given ") || t.startsWith("When ") || t.startsWith("Then ")
                || t.startsWith("And ")   || t.startsWith("But ")  || t.startsWith("* ");
    }
}
