package io.pickleball.cacheandstate;

import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.messages.GherkinMessagesFeature;
import io.cucumber.core.plugin.TeamCityPlugin;
import  io.cucumber.core.runner.Runner;
import io.cucumber.core.runner.TestCase;
import io.cucumber.core.runtime.FeaturePathFeatureSupplier;
import io.cucumber.core.runtime.Runtime;
import io.pickleball.cucumberutilities.FeatureFileUtilities;

import java.io.PrintStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GlobalCache {
    public static final PrintStream out = System.out;
    public static TeamCityPlugin teamCityPlugin;

//    public static ConcurrentHashMap<String, Object> gherkinMap = new ConcurrentHashMap<>();


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

//    private static Runner globalRunner;


//    public static Runner getGlobalRunner() {
//        return globalRunner;
//    }




    private static Runtime globalRuntime;

    public static FeaturePathFeatureSupplier getFeaturePathFeatureSupplier() {
        return  (FeaturePathFeatureSupplier) getGlobalRuntime().featureSupplier;
    }

    public static Runtime getGlobalRuntime() {
        return globalRuntime;
    }

    public static void setGlobalRuntime(Runtime globalRuntime) {
        if(GlobalCache.globalRuntime != null)
            throw new RuntimeException("globalRuntime value already set");
        GlobalCache.globalRuntime = globalRuntime;
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