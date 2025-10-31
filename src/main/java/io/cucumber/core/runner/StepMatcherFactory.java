//package io.cucumber.core.runner;
//
//import io.cucumber.core.gherkin.Pickle;
//import io.cucumber.messages.types.PickleStepArgument;
//
//import static io.cucumber.core.gherkin.messages.PickleFactory.createOneStepGherkinMessagesPickle;
//
//public final class StepMatcherFactory {
//
//    private StepMatcherFactory() {}
//
//    public static PickleStepDefinitionMatch matchFromText(String stepText, PickleStepArgument pickleStepArgument, String... gluePaths) {
//        // Create a minimal synthetic pickle with one step
//        Pickle pickle = createOneStepGherkinMessagesPickle(stepText, pickleStepArgument);
//
//        // Build a minimal runner with glue
//        java.util.List<String> args = new java.util.ArrayList<>();
//        for (String g : gluePaths) {
//            args.add("--glue");
//            args.add(g);
//        }
//
//        RunnerRuntimeContext ctx =
//                RunnerRuntimeRegistry.getOrInit(args.toArray(new String[0]));
//
//        // Use RunnerExtras to resolve the match directly
//        RunnerExtras extras = (RunnerExtras) (Object) ctx.runner;
//        PickleStepTestStep testStep = extras.createStepForText(pickle, stepText);
//        return testStep.getDefinitionMatch();
//    }
//}
