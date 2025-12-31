//package io.cucumber.core.plugin;
//
//
//import static io.cucumber.core.runner.modularexecutions.FilePathResolver.fixFeatureLocationForIntellij;
//
///**
// * Intercepts TeamCityPlugin.extractLocation(TestStepStarted)
// * and normalizes feature-file locations for IntelliJ terminal links.
// */
//public aspect TeamCityPluginExtractLocationFixAspect {
//
//String around(io.cucumber.plugin.event.TestStepStarted event) :
//execution(String io.cucumber.core.plugin.TeamCityPlugin.extractLocation(
//        io.cucumber.plugin.event.TestStepStarted))
//        && args(event) {
//
//    String original = proceed(event);
//    System.out.println("@@extractLocation: original = " + original);
//
//    String fixed = fixFeatureLocationForIntellij(original);
//
//    if (!String.valueOf(original).equals(String.valueOf(fixed))) {
//        System.out.println("@@extractLocation: fixed    = " + fixed);
//    }
//
//    return fixed;
//}
//}
