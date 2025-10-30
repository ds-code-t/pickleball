//package io.cucumber.core.runner;
//
//import io.cucumber.plugin.event.StepArgument;
//
///**
// * Adds a public 'overrideStepArgument' field to PickleStepTestStep and
// * makes getStepArgument() return it when non-null; otherwise, the original
// * method executes normally.
// */
//public aspect PickleStepTestStep_OverrideStepArgumentAspect {
//
//    /** Public override slot; leave null to use the original argument. */
//    public StepArgument io.cucumber.core.runner.PickleStepTestStep.overrideStepArgument;
//
//    /** Intercept the no-arg getStepArgument() method on PickleStepTestStep. */
//    pointcut getStepArgumentExec(io.cucumber.core.runner.PickleStepTestStep self) :
//            execution(io.cucumber.plugin.event.StepArgument io.cucumber.core.runner.PickleStepTestStep.getStepArgument())
//                    && this(self);
//
//    /** If an override is present, return it; else proceed with original implementation. */
//    io.cucumber.plugin.event.StepArgument around(io.cucumber.core.runner.PickleStepTestStep self)
//            : getStepArgumentExec(self) {
//        StepArgument override = self.overrideStepArgument;
//        if (override != null) {
//            return override;
//        }
//        // Important: proceed must receive the same bound args as the advice signature
//        return proceed(self);
//    }
//}
