package io.pickleball.executions;

import io.cucumber.core.options.CommandlineOptionsParser;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.runtime.Runtime;
import io.cucumber.core.feature.FeatureWithLines;


import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class RuntimeUtils {

    /**
     * Creates and returns a configured Runtime instance without running it.
     *
     * @param runner      The Runner instance to derive configuration from.
     * @param classLoader The class loader to use.
     * @return A configured Runtime instance.
     */
    public static Runtime createRuntimeFromRunner(Object runner, ClassLoader classLoader) {
        // Extract necessary values from the Runner instance
        String[] argv = extractCommandLineArgs(runner);
        List<URI> gluePaths = extractGluePaths(runner);
        List<FeatureWithLines> featurePaths = extractFeaturePaths(runner);

        // Build RuntimeOptions
        RuntimeOptions runtimeOptions = new CommandlineOptionsParser(System.out)
                .parse(argv)
                .addDefaultGlueIfAbsent()
                .addDefaultFeaturePathIfAbsent()
                .build(RuntimeOptions.defaultOptions());

        // Set custom glue and feature paths
        runtimeOptions.setGlue(gluePaths);
        runtimeOptions.setFeaturePaths(featurePaths);

        // Create and return the Runtime instance
        return Runtime.builder()
                .withRuntimeOptions(runtimeOptions)
                .withClassLoader(() -> classLoader)
                .build();
    }

    /**
     * Extracts command-line arguments from the Runner instance.
     *
     * @param runner The Runner instance.
     * @return An array of command-line arguments.
     */
    private static String[] extractCommandLineArgs(Object runner) {
        // Example: Customize based on your Runner instance
        return new String[]{
                "--plugin", "pretty",
                "--plugin", "html:target/cucumber-reports/cucumber.html",
                "--plugin", "json:target/cucumber-reports/cucumber.json"
        };
    }

    /**
     * Extracts glue paths from the Runner instance.
     *
     * @param runner The Runner instance.
     * @return A list of URI representing glue paths.
     */
    private static List<URI> extractGluePaths(Object runner) {
        // Example: Derive glue paths based on your Runner
        List<URI> gluePaths = new ArrayList<>();
        gluePaths.add(URI.create("classpath:io/pickleball/stepdefs"));
        return gluePaths;
    }

    /**
     * Extracts feature paths from the Runner instance.
     *
     * @param runner The Runner instance.
     * @return A list of FeatureWithLines representing feature paths.
     */
    private static List<FeatureWithLines> extractFeaturePaths(Object runner) {
        // Example: Derive feature paths based on your Runner
        List<FeatureWithLines> featurePaths = new ArrayList<>();
        featurePaths.add(FeatureWithLines.create(URI.create("file:src/test/resources/features"), new ArrayList<>()));
        return featurePaths;
    }
}
