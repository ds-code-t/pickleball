package tools.ds.modkit.force;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

public final class ForceWeaveTeamCity {

    public static void apply(Instrumentation inst) {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .disableClassFormatChanges() // important: never add fields/methods on retransformation
                .ignore(ElementMatchers.none())
                .type(named("io.cucumber.core.plugin.TeamCityPlugin"))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                            TypeDescription typeDescription,
                                                            ClassLoader classLoader,
                                                            JavaModule module,
                                                            ProtectionDomain protectionDomain) {
                        return builder
                                .visit(Advice.to(FormatAdvice.class)
                                        .on(named("formatCommand")
                                                .and(takesArguments(2))
                                                .and(takesArgument(0, String.class)))) // (String, Object[])
                                .visit(Advice.to(PrintAdvice.class)
                                        .on(named("print")
                                                .and(takesArguments(2))
                                                .and(takesArgument(0, String.class)))); // (String, Object[])
                    }
                })
                .installOn(inst);

        // Retransform if already loaded
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().equals("io.cucumber.core.plugin.TeamCityPlugin") && inst.isModifiableClass(c)) {
                try {
                    inst.retransformClasses(c);
                } catch (Throwable t) {
                    System.err.println("[modkit] retransform TeamCityPlugin failed: " + t);
                }
            }
        }
    }

    /** formatCommand(String, Object...): mutate last param and log. */
    public static class FormatAdvice {
        @Advice.OnMethodEnter
        static void enter(@Advice.Argument(value = 0, readOnly = false) String template,
                          @Advice.Argument(value = 1, readOnly = false) Object[] params) {
            System.out.println("\n@@TC#formatCommand ENTER template=" + template +
                    " params=" + (params == null ? "null" : Arrays.asList(params)));
            if (template != null && template.startsWith("##teamcity[") && params != null && params.length > 0) {
                int idx = params.length - 1;
                params[idx] = "FMT#" + params[idx] + "#";
                System.out.println("@@TC#formatCommand mutated last param -> " + params[idx]);
            }
        }

        @Advice.OnMethodExit
        static void exit(@Advice.Return(readOnly = false) String ret) {
            System.out.println("@@TC#formatCommand EXIT ret=" + ret);
            if (ret != null) {
                ret = ret + " |RET#fmt";
            }
        }
    }

    /** print(String, Object...): mutate last param and log. */
    public static class PrintAdvice {
        @Advice.OnMethodEnter
        static void enter(@Advice.Argument(value = 0, readOnly = false) String template,
                          @Advice.Argument(value = 1, readOnly = false) Object[] params) {
            System.out.println("\n@@TC#print ENTER template=" + template +
                    " params=" + (params == null ? "null" : Arrays.asList(params)));
            if (template != null && template.startsWith("##teamcity[") && params != null && params.length > 0) {
                int idx = params.length - 1;
                params[idx] = "PRINT#" + params[idx] + "#";
                System.out.println("@@TC#print mutated last param -> " + params[idx]);
            }
        }

        @Advice.OnMethodExit
        static void exit() {
            System.out.println("@@TC#print EXIT");
        }
    }

    private ForceWeaveTeamCity() {}
}
