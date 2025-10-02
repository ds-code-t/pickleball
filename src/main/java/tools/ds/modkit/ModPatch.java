package tools.ds.modkit;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;

/** SPI for Byte Buddy patches, discovered via ServiceLoader. */
public interface ModPatch {
    /** Stable identifier for logs/toggles. */
    String id();

    /** Contribute transformations to the given AgentBuilder and return it. */
    AgentBuilder apply(AgentBuilder base, Instrumentation inst);

    /** Optional: force retransform of already-loaded targets right after install. */
    default void eagerRetransform(Instrumentation inst) { /* no-op */ }
}
