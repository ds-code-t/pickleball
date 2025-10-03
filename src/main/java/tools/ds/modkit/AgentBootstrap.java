package tools.ds.modkit;

import java.lang.instrument.Instrumentation;

public final class AgentBootstrap {
    public static void premain(String args, Instrumentation inst)  { boot(inst); }
    public static void agentmain(String args, Instrumentation inst){ boot(inst); }

    private static void boot(Instrumentation inst) {
        System.setProperty("modkit.agent.active","true");
        InstrumentationHolder.set(inst);

        // Non-JNA injection is handled *inside* ModKitCore.install(inst)
        ModKitCore.install(inst);
    }

    private AgentBootstrap() {}
}
