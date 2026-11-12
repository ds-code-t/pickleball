package tools.dscode.engine.runtime;

import io.cucumber.core.runner.StepExtension;

import java.util.function.Consumer;

/** Collects steps as they run so native results match Cucumber's finish events. */
public final class NativeStepTrace {
    private static final ThreadLocal<Consumer<StepExtension>> SINK = new ThreadLocal<>();

    private NativeStepTrace() {
    }

    public static void listen(Consumer<StepExtension> sink) {
        if (sink == null) {
            SINK.remove();
        } else {
            SINK.set(sink);
        }
    }

    public static Consumer<StepExtension> current() {
        return SINK.get();
    }

    public static void record(StepExtension step) {
        Consumer<StepExtension> sink = SINK.get();
        if (sink != null && step != null) {
            sink.accept(step);
        }
    }
}
