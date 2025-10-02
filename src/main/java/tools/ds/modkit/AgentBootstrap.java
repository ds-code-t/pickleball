package tools.ds.modkit;

import java.lang.instrument.Instrumentation;

public final class AgentBootstrap {
    public static void premain(String args, Instrumentation inst)  { boot(inst); }
    public static void agentmain(String args, Instrumentation inst){ boot(inst); }

    private static void boot(Instrumentation inst) {
        System.setProperty("modkit.agent.active","true");
        InstrumentationHolder.set(inst);
        ModKitCore.install(inst); // your AgentBuilder + retransformation + eager retransform
    }
    private AgentBootstrap() {}
}
