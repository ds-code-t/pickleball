package tools.dscode.engine.adapter.cucumber;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.AfterStep;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeStep;
import tools.dscode.engine.match.StepRegistry;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Cucumber expression matching and hook scanning for the legacy adapter.
 * The native engine calls {@link tools.dscode.engine.match.PatternMatcher}.
 */
public final class CucumberStepRegistry {
    private static final Map<StepRegistry, GlueConverters> CONVERTERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private CucumberStepRegistry() {
    }

    public static void attach(StepRegistry registry, Locale locale) {
        if (registry == null) return;
        CONVERTERS.put(registry, new GlueConverters(locale == null ? Locale.ENGLISH : locale));
    }

    /** Registers data-table converters and Cucumber hooks found on one glue method. */
    public static void observe(StepRegistry registry, Method method) {
        if (registry == null || method == null) return;
        GlueConverters converters = CONVERTERS.get(registry);
        if (converters != null) converters.register(method);
        if (!GlueHooks.callable(method)) return;
        for (Before before : method.getAnnotationsByType(Before.class)) {
            registry.addHook("before", before.order(), before.value(), method);
        }
        for (After after : method.getAnnotationsByType(After.class)) {
            registry.addHook("after", after.order(), after.value(), method);
        }
        for (BeforeStep beforeStep : method.getAnnotationsByType(BeforeStep.class)) {
            registry.addHook("beforestep", beforeStep.order(), beforeStep.value(), method);
        }
        for (AfterStep afterStep : method.getAnnotationsByType(AfterStep.class)) {
            registry.addHook("afterstep", afterStep.order(), afterStep.value(), method);
        }
    }

    public static DataTable.TableConverter tableConverter(StepRegistry registry) {
        if (registry == null) return null;
        GlueConverters converters = CONVERTERS.get(registry);
        return converters == null ? null : converters.converter();
    }
}
