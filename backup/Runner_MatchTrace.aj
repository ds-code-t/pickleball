//package io.cucumber.core.runner;
//
//import io.cucumber.core.gherkin.Pickle;
//import io.cucumber.core.gherkin.Step;
//
///** Traces match requests so you see the precise text and pickle used. */
//public aspect Runner_MatchTrace {
//
//    Object around(Runner self, Pickle pickle, Step step) :
//            execution(* io.cucumber.core.runner.Runner.matchStepToStepDefinition(..))
//                    && this(self) && args(pickle, step) {
//
//        System.out.println("~~ matchStepToStepDefinition: text='" + step.getText()
//                + "' in pickle='" + pickle.getName() + "'");
//        return proceed(self, pickle, step);
//    }
//}
