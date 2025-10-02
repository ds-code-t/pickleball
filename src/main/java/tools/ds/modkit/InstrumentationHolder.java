package tools.ds.modkit;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicReference;

public final class InstrumentationHolder {
    private static final AtomicReference<Instrumentation> REF = new AtomicReference<>();

    public static boolean isPresent() { return REF.get() != null; }
    public static Instrumentation get() {
        Instrumentation i = REF.get();
        if (i == null) throw new IllegalStateException("Instrumentation not available");
        return i;
    }
    public static void set(Instrumentation i) { REF.compareAndSet(null, i); }

    private InstrumentationHolder() {}
}
