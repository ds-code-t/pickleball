package io.cucumber.core.runtime;

import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.plugin.TeamCityPlugin;
import  io.cucumber.core.runner.Runner;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GlobalCache {
    public static final PrintStream out = System.out;
    public static TeamCityPlugin teamCityPlugin;

    public static ConcurrentHashMap<String, Object> gherkinMap = new ConcurrentHashMap<>();

//    private static final ThreadLocal<PrimaryScenarioData> ThreadContext = ThreadLocal.withInitial(PrimaryScenarioData::new);
//    public static PrimaryScenarioData getState() {
//        return ThreadContext.get();
//    }

    private static final Map<String, List<Feature>> CACHE = new ConcurrentHashMap<>();

    private static Runner globalRunner;


    public static Runner getGlobalRunner() {
        return globalRunner;
    }

    public static void setGlobalRunner(Runner globalRunner) {
        if(GlobalCache.globalRunner != null)
            throw new RuntimeException("globalRunner value already set");
        GlobalCache.globalRunner = globalRunner;
    }


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