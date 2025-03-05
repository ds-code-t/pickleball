package io.pickleball.cacheandstate;

import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.messages.GherkinMessagesFeature;
import io.cucumber.core.plugin.TeamCityPlugin;
import io.cucumber.core.runner.Runner;
import io.cucumber.core.runner.TestCase;
import io.cucumber.core.runtime.FeaturePathFeatureSupplier;
import io.cucumber.core.runtime.Runtime;
import io.pickleball.cucumberutilities.FeatureFileUtilities;
//import io.pickleball.datafunctions.MultiFormatDataNode;
import io.pickleball.executions.RuntimeUtils;
import io.pickleball.mapandStateutilities.LinkedMultiMap;
import org.testng.TestRunner;

import java.io.File;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.pickleball.pathrools.ProjectRootUtils.getConfigPaths;


public class GlobalCache {
    public static final PrintStream out = System.out;
    public static TeamCityPlugin teamCityPlugin;

    public static LinkedMultiMap<String, Object> getGlobalConfigs() {
        if (globalConfigs == null)
            globalConfigs = new LinkedMultiMap<String, Object>(new File(getConfigPaths().toString()));
        return globalConfigs;
    }

    private static LinkedMultiMap<String, Object> globalConfigs;


    public static GherkinMessagesFeature getParsedFeature(URI uri) {
        return parsedFeature.computeIfAbsent(uri, FeatureFileUtilities::parseFeature
        );
    }

    private static final ConcurrentHashMap<URI, GherkinMessagesFeature> parsedFeature = new ConcurrentHashMap<>();


    private static final ThreadLocal<PrimaryScenarioData> ThreadContext = new ThreadLocal<>();


    public static void setScenarioThreadState(Runner runner, TestCase testCase) {
        ThreadContext.set(new PrimaryScenarioData(runner, testCase));
    }

    public static PrimaryScenarioData getState() {
        return ThreadContext.get();
    }

    private static final Map<String, List<Feature>> CACHE = new ConcurrentHashMap<>();

    private static Runtime globalRuntime;

    public static FeaturePathFeatureSupplier getFeaturePathFeatureSupplier() {
        return (FeaturePathFeatureSupplier) getGlobalRuntime().featureSupplier;
    }

    public static Runtime getGlobalRuntime() {
        return globalRuntime;
    }


    public static synchronized void setGlobalRuntime(Runner runner) {
        if (globalRuntime == null)
            globalRuntime = RuntimeUtils.createRuntimeFromRunner(runner, TestRunner.class.getClassLoader());
    }


    // Retrieve from cache
    public static List<Feature> getFeatures(String key) {
        return CACHE.get(key);
    }

    // Put into cache if not already cached
    public static void cacheFeatures(String key, List<Feature> features) {
        CACHE.putIfAbsent(key, features);
    }


}