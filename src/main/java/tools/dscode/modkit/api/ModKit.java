// tools/ds/modkit/api/ModKit.java
package tools.dscode.modkit.api;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.*;

public final class ModKit {
    private ModKit() {}

    public static Instrumentation attach() {
        // attach (or reuse) the agent
        Instrumentation inst = ByteBuddyAgent.install();

        AgentBuilder base = new AgentBuilder.Default()
                // Make sure already-loaded matching classes are woven:
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .disableClassFormatChanges()
                // Helpful logging in case something’s odd on a user’s box:
                .with(AgentBuilder.Listener.StreamWriting.toSystemError())
                .with(AgentBuilder.InstallationListener.StreamWriting.toSystemError())
                // Ignore noise (keeps perf good and avoids self-instrumentation):
                .ignore(nameStartsWith("net.bytebuddy.")
                        .or(nameStartsWith("org.slf4j."))
                        .or(nameStartsWith("ch.qos.logback."))
                        .or(nameStartsWith("java."))
                        .or(nameStartsWith("javax."))
                        .or(nameStartsWith("jdk."))
                        .or(nameStartsWith("sun.")));

        // apply your registered plans
        tools.dscode.modkit.blackbox.Weaver.apply(base).installOn(inst);
        return inst;
    }
}
