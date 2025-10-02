//// tools/ds/modkit/override/TestCaseRunOverrideInstaller.java
//package tools.ds.modkit.override;
//
//import net.bytebuddy.agent.builder.AgentBuilder;
//import net.bytebuddy.implementation.MethodDelegation;
//
//import java.lang.instrument.Instrumentation;
//
//import static net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy.RETRANSFORMATION;
//import static net.bytebuddy.matcher.ElementMatchers.*;
//
//public final class TestCaseRunOverrideInstaller {
//    private TestCaseRunOverrideInstaller() {}
//
//    public static void install(Instrumentation inst) {
//        new AgentBuilder.Default()
//                .with(RETRANSFORMATION)
//                .disableClassFormatChanges()
//                .type(named("io.cucumber.core.runner.TestCase"))
//                .transform((builder, td, cl, module, pd) ->
//                        builder.method(named("run").and(takesArguments(1))) // run(EventBus)
//                                .intercept(MethodDelegation.to(StepLoopOverride.class))
//                )
//                .installOn(inst);
//    }
//}
