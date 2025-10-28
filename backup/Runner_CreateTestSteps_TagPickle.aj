//// Runner_CreateTestSteps_TagPickle.aj
//// AspectJ 1.9+ / Java 21
////
//// - Adds a `Pickle` field to PickleStepTestStep
//// - Tags each created PickleStepTestStep with the Pickle passed to
////   Runner#createTestStepsForPickleSteps(Pickle)
//
//package io.cucumber.core.runner;
//
//public privileged aspect Runner_CreateTestSteps_TagPickle {
//
//    // ITD: add a field onto PickleStepTestStep to hold the originating Pickle
//    public io.cucumber.core.gherkin.Pickle
//            io.cucumber.core.runner.PickleStepTestStep.pickle;
//
//    // (Optional) getter if you want to read it elsewhere:
//    // public io.cucumber.core.gherkin.Pickle
//    //     io.cucumber.core.runner.PickleStepTestStep.getPickle() { return this.pickle; }
//
//    /**
//     * Wrap Runner#createTestStepsForPickleSteps(Pickle), then stamp each step.
//     * Note: return type is raw List at bytecode level (type erasure), so a
//     * harmless "uncheckedAdviceConversion" lint may appear unless you silence it.
//     */
//    java.util.List around(io.cucumber.core.gherkin.Pickle pickle) :
//            execution(private java.util.List io.cucumber.core.runner.Runner.createTestStepsForPickleSteps(io.cucumber.core.gherkin.Pickle))
//                    && args(pickle) {
//
//        java.util.List result = proceed(pickle);
//
//        if (result != null) {
//            for (Object o : result) {
//                if (o instanceof io.cucumber.core.runner.PickleStepTestStep) {
//                    ((io.cucumber.core.runner.PickleStepTestStep) o).pickle = pickle;
//                }
//            }
//        }
//        return result;
//    }
//
//
//}
