//package io.cucumber.core.runner;
//
//import io.cucumber.core.gherkin.Pickle;
//import tools.dscode.support.ProbeUtil;
//
//public aspect CucumberProbe {
//    /**
//     * Intercepts Cucumber's execution of a single scenario ("pickle").
//     * Demonstrates calling your own Java helper + before/after logs.
//     */
//    pointcut runPickle(Pickle p):
//        execution(void io.cucumber.core.runner.Runner.runPickle(io.cucumber.core.gherkin.Pickle))
//        && args(p);
//
//    void around(Pickle p): runPickle(p) {
//        ProbeUtil.log("Before pickle: " + p.getName());
//        try {
//            proceed(p); // let cucumber run the scenario
//        } finally {
//            ProbeUtil.log("After  pickle: " + p.getName());
//        }
//    }
//}
