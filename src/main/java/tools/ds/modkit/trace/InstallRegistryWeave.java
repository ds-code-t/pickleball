//// src/main/java/tools/ds/modkit/trace/InstallRegistryWeave.java
//package tools.ds.modkit.trace;
//
//import net.bytebuddy.agent.builder.AgentBuilder;
//import net.bytebuddy.asm.Advice;
//import net.bytebuddy.description.type.TypeDescription;
//import net.bytebuddy.utility.JavaModule;
//
//import java.lang.instrument.Instrumentation;
//import java.security.ProtectionDomain;
//import java.util.ArrayList;
//import java.util.List;
//
//import static net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy.RETRANSFORMATION;
//import static net.bytebuddy.matcher.ElementMatchers.*;
//
//public final class InstallRegistryWeave {
//
//    public static void apply(Instrumentation inst) {
//        new AgentBuilder.Default()
//                .with(RETRANSFORMATION)
//                .disableClassFormatChanges()
//                .type(
//                        named("io.cucumber.core.runner.TestCase")
//                                .or(named("io.cucumber.core.gherkin.messages.GherkinMessagesPickle"))
//                                .or(named("io.cucumber.messages.types.Pickle"))
//                                .or(named("io.cucumber.messages.types.Scenario"))
//                                // optional: also catch Java API scenario wrappers
//                                .or(named("io.cucumber.java.Scenario"))
//                                .or(named("io.cucumber.java8.Scenario"))
//                )
//                .transform(new AgentBuilder.Transformer() {
//                    @Override
//                    public net.bytebuddy.dynamic.DynamicType.Builder<?> transform(
//                            net.bytebuddy.dynamic.DynamicType.Builder<?> builder,
//                            TypeDescription typeDescription,
//                            ClassLoader classLoader,
//                            JavaModule module,
//                            ProtectionDomain protectionDomain) {
//                        return builder.visit(
//                                Advice.to(RegisterAdvice.class).on(isConstructor())
//                        );
//                    }
//                })
//                .installOn(inst);
//    }
//
//    /** Runs after target constructor completes. */
//    public static class RegisterAdvice {
//        @Advice.OnMethodExit(suppress = Throwable.class)
//        static void after(@Advice.This Object self) {
//            Class<?> c = self.getClass();
//            String fqn = c.getName();
//
//            // Build alias keys we’ll store under.
//            List<Object> keys = new ArrayList<>();
//            keys.add(fqn);   // lookup by FQCN string
//            keys.add(c);     // lookup by Class object
//
//            // Canonical aliases used by ScenarioState/GlobalState
//            if (fqn.equals("io.cucumber.core.gherkin.messages.GherkinMessagesPickle")) {
//                keys.add("io.cucumber.messages.types.Pickle");
//            } else if (fqn.equals("io.cucumber.messages.types.Pickle")) {
//                // ok as-is
//            } else if (fqn.equals("io.cucumber.java.Scenario") ||
//                    fqn.equals("io.cucumber.java8.Scenario")) {
//                keys.add("io.cucumber.messages.types.Scenario");
//            } else if (fqn.equals("io.cucumber.core.runner.TestCase")) {
//                // ok as-is; matches ScenarioState key
//            }
//
//            // Store once, under all keys.
//            InstanceRegistry.register(self, keys.toArray(new Object[0]));
//        }
//    }
//
//    private InstallRegistryWeave() {}
//}
