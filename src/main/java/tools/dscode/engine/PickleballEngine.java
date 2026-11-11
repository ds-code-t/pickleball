package tools.dscode.engine;

import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.convert.CucumberModelConverter;
import tools.dscode.engine.match.AmbiguousMatchException;
import tools.dscode.engine.match.StepKind;
import tools.dscode.engine.match.StepMatch;
import tools.dscode.engine.match.StepRegistry;
import tools.dscode.engine.model.DocText;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.ScenarioResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.model.StepArgument;
import tools.dscode.engine.model.StepResult;
import tools.dscode.engine.model.Table;
import tools.dscode.engine.parse.GherkinEngineParser;
import tools.dscode.engine.source.MemoryScenarioSource;
import tools.dscode.engine.source.ScenarioSource;
import tools.dscode.engine.source.StepListScenarioSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Runs scenarios and steps without a Cucumber scenario. */
public final class PickleballEngine {
    private PickleballEngine() {
    }

    public static RunResult run(String text, EngineOptions options) {
        return run(new MemoryScenarioSource(text), options);
    }

    public static RunResult run(List<String> steps, EngineOptions options) {
        String name = options == null || options.scenarioName().isBlank() ? "runtime" : options.scenarioName();
        return run(new StepListScenarioSource(name, steps), options);
    }

    public static RunResult run(ScenarioSource source, EngineOptions options) {
        EngineOptions settings = options == null ? EngineOptions.of() : options;
        StepRegistry registry = new StepRegistry().logTo(settings.project());
        if (settings.glue() != null) registry.scan(settings.glue().toArray(Class<?>[]::new));
        List<ScenarioResult> scenarios = new ArrayList<>();
        for (EngineFeature feature : source.load()) {
            EngineFeature expanded = GherkinEngineParser.expand(feature);
            for (EngineScenario scenario : expanded.scenarios()) {
                if (!settings.scenarioName().isBlank() && !scenario.name().contains(settings.scenarioName())) continue;
                scenarios.add(runScenario(scenario, registry, settings));
            }
        }
        RunResult result = RunResult.of(scenarios);
        EngineLog.log(settings.project(), "run", source.id(), result.status() + " scenarios=" + scenarios.size());
        return result;
    }

    public static StepResult runStep(String text, String argument, ExecutionContext context) {
        ExecutionContext active = context == null ? ExecutionContext.current() : context;
        boolean opened = false;
        if (active == null) {
            active = ExecutionContext.open(EngineScenario.of("step", List.of()));
            opened = true;
        }
        try {
            StepRegistry registry = registry(active);
            EngineStep step = EngineStep.of("* ", text == null ? "" : text);
            if (argument != null && !argument.isBlank()) {
                step = new EngineStep(step.keyword(), step.text(), StepArgument.doc(DocText.of(argument)), step.location(), step.metadata(), false);
            }
            return execute(step, registry, active, new IdentityHashMap<>());
        } finally {
            if (opened) active.close();
        }
    }

    private static StepRegistry registry(ExecutionContext context) {
        if (context != null && context.resources().get("registry") instanceof StepRegistry existing) return existing;
        StepRegistry created = new StepRegistry();
        Object glue = context == null ? null : context.resources().get("glue");
        if (glue instanceof List<?> list) {
            List<Class<?>> types = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Class<?> type) types.add(type);
            }
            created.scan(types.toArray(Class<?>[]::new));
        }
        return created;
    }

    private static ScenarioResult runScenario(EngineScenario scenario, StepRegistry registry, EngineOptions options) {
        List<EngineStep> queue = new ArrayList<>(scenario.steps());
        List<StepResult> results = new ArrayList<>();
        Map<Class<?>, Object> instances = new IdentityHashMap<>();
        try (ExecutionContext context = ExecutionContext.open(scenario)) {
            context.phase(ExecutionContext.Phase.RUNNING);
            context.resource("registry", registry);
            for (int index = 0; index < queue.size(); index++) {
                EngineStep step = queue.get(index);
                context.pushStep(step);
                StepResult result = execute(step, registry, context, instances);
                results.add(result);
                context.popStep();
                List<EngineStep> written = context.drainWritten();
                if (!written.isEmpty()) queue.addAll(index + 1, written);
                context.scope().put("returned", result.returned());
                if (result.status() == Status.FAILED && !options.continueOnFailure()) break;
            }
            context.phase(ExecutionContext.Phase.CLEANUP);
        }
        return ScenarioResult.fromSteps(scenario.name(), results);
    }

    private static StepResult execute(
            EngineStep step,
            StepRegistry registry,
            ExecutionContext context,
            Map<Class<?>, Object> instances
    ) {
        long started = System.nanoTime();
        try {
            StepMatch match = registry.match(step.text());
            if (!match.matched()) {
                return finish(Status.UNDEFINED, step, "undefined", "", started);
            }
            if (match.step() == null || match.step().method() == null) {
                return finish(Status.PASSED, step, match.kind().name(), "", started);
            }
            Object value = invoke(match, step, instances);
            if (value != null) context.scope().put("returned", String.valueOf(value));
            EngineLog.log(null, "run", match.step().location(), step.text());
            return finish(Status.PASSED, step, "", value == null ? "" : String.valueOf(value), started);
        } catch (AmbiguousMatchException ambiguous) {
            return finish(Status.AMBIGUOUS, step, ambiguous.getMessage(), "", started);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            context.fail(cause);
            return finish(Status.FAILED, step, cause.getMessage() == null ? cause.toString() : cause.getMessage(), "", started);
        } catch (ReflectiveOperationException failure) {
            context.fail(failure);
            return finish(Status.FAILED, step, failure.getMessage(), "", started);
        }
    }

    private static Object invoke(StepMatch match, EngineStep step, Map<Class<?>, Object> instances) throws ReflectiveOperationException {
        Method method = match.step().method();
        Object target = instances.computeIfAbsent(method.getDeclaringClass(), type -> {
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        });
        method.setAccessible(true);
        Object[] args = arguments(method, match, step);
        return method.invoke(target, args);
    }

    private static Object[] arguments(Method method, StepMatch match, EngineStep step) {
        Type[] types = method.getGenericParameterTypes();
        List<String> captures = match.captures() == null ? List.of() : match.captures();
        Object[] args = new Object[types.length];
        int capture = 0;
        for (int index = 0; index < types.length; index++) {
            Class<?> raw = raw(types[index]);
            if (index == types.length - 1 && isTrailing(raw)) {
                args[index] = trailing(raw, step);
                continue;
            }
            String value = capture < captures.size() ? captures.get(capture++) : null;
            args[index] = convert(raw, value);
        }
        return args;
    }

    private static boolean isTrailing(Class<?> raw) {
        return raw == DataTable.class || raw == Table.class || raw == DocString.class || raw == DocText.class;
    }

    private static Object trailing(Class<?> raw, EngineStep step) {
        if (raw == Table.class && step.argument() instanceof StepArgument.TableArg table) return table.table();
        if (raw == DataTable.class && step.argument() instanceof StepArgument.TableArg table) {
            return CucumberModelConverter.toDataTable(table.table());
        }
        if (raw == DocText.class && step.argument() instanceof StepArgument.DocArg doc) return doc.doc();
        if (raw == DocString.class && step.argument() instanceof StepArgument.DocArg doc) {
            return CucumberModelConverter.toDocString(doc.doc());
        }
        if (raw == DataTable.class) return DataTable.emptyDataTable();
        if (raw == Table.class) return Table.empty();
        if (raw == DocText.class) return DocText.of("");
        return DocString.create("");
    }

    private static Object convert(Class<?> raw, String value) {
        if (raw == String.class) return value;
        if (value == null) return null;
        if (raw == int.class || raw == Integer.class) return Integer.parseInt(value);
        if (raw == long.class || raw == Long.class) return Long.parseLong(value);
        if (raw == boolean.class || raw == Boolean.class) return Boolean.parseBoolean(value);
        return value;
    }

    private static Class<?> raw(Type type) {
        return type instanceof Class<?> clazz ? clazz : Object.class;
    }

    private static StepResult finish(Status status, EngineStep step, String message, String returned, long started) {
        long millis = Math.max(0, (System.nanoTime() - started) / 1_000_000L);
        return new StepResult(status, step.keyword(), step.text(), message == null ? "" : message, millis, returned);
    }

    static String describe(StepKind kind) {
        return kind == null ? "" : kind.name();
    }
}
