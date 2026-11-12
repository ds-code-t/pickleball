package tools.dscode.engine.runtime;

import io.cucumber.core.runner.StepExtension;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.control.override.StepOverrideContext;
import tools.dscode.control.override.StepOverrideRegistry;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.match.AmbiguousMatchException;
import tools.dscode.engine.match.StepKind;
import tools.dscode.engine.match.StepMatch;
import tools.dscode.engine.match.StepRegistry;
import tools.dscode.engine.model.DocText;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.model.StepArgument;
import tools.dscode.engine.model.Table;
import tools.dscode.engine.convert.CucumberModelConverter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static io.cucumber.core.runner.GlobalState.lifecycle;
import static io.cucumber.core.runner.util.TableUtils.DOCSTRING_KEY;
import static io.cucumber.core.runner.util.TableUtils.TABLE_KEY;
import static io.cucumber.core.runner.util.TableUtils.toRowsStringMultimap;
import static tools.dscode.common.annotations.DefinitionFlag.NO_STEP_TEXT_RESOLUTION;
import static tools.dscode.common.domoperations.LeanWaits.safeWaitForPageReady;
import static tools.dscode.common.reporting.logging.LogForwarder.closestEntryToScenario;
import static tools.dscode.common.reporting.logging.LogForwarder.setDefaultEntry;
import static tools.dscode.common.treeparsing.preparsing.ParsedLine.createParsedLine;

/**
 * One step body for the native engine and for dynamic glue.
 * Cucumber's pickle runner still enters through the glue methods, which call
 * {@link #executeDynamic}. Native frames call {@link #run}.
 */
public final class SharedStepRun {
    private static final ThreadLocal<Map<StepExtension, EngineStep>> BOUND =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private SharedStepRun() {
    }

    public static void remember(StepExtension step, EngineStep engineStep) {
        if (step != null && engineStep != null) BOUND.get().put(step, engineStep);
    }

    public static void forget() {
        BOUND.remove();
    }

    /** Comma steps, block conditionals, and synthetic dynamic steps. */
    public static void executeDynamic(StepExtension currentStep) {
        if (currentStep == null) {
            throw new IllegalStateException("Dynamic step requires an active step.");
        }
        if (currentStep.lineData == null) {
            currentStep.lineData = createParsedLine(currentStep);
            currentStep.lineData.setInheritance(currentStep);
        }
        currentStep.insertChildStepsByString(currentStep.lineData.lineComponents);
        currentStep.lineData.runPhrases();
    }

    public static Result run(StepExtension step) {
        Instant start = Instant.now();
        try {
            step.stepEntry = closestEntryToScenario()
                    .logWithType("STEP", keyword(step) + step.getStepText(), step.stepLogLevel)
                    .tags("Step")
                    .start();
            setDefaultEntry(step.stepEntry);
            lifecycle.fire(Phase.BEFORE_SCENARIO_STEP);
            fireHooks("beforestep", contextOf());
            if (step.runNativeFinalizer()) {
                Result result = new Result(Status.PASSED, Duration.between(start, Instant.now()), null);
                step.result = result;
                return result;
            }
            Object value = invoke(step);
            step.nativeReturned = value;
            Result result = new Result(Status.PASSED, Duration.between(start, Instant.now()), null);
            step.result = result;
            return result;
        } catch (UndefinedStep undefined) {
            return finish(step, Status.UNDEFINED, start, undefined);
        } catch (AmbiguousMatchException ambiguous) {
            return finish(step, Status.AMBIGUOUS, start, ambiguous);
        } catch (Throwable thrown) {
            Throwable cause = unwrap(thrown);
            return finish(step, Status.FAILED, start, cause);
        } finally {
            fireHooks("afterstep", contextOf());
            if (step.waitForPageReady && step.webDriverUsed != null) {
                safeWaitForPageReady(step.webDriverUsed, Duration.ofSeconds(5), 200);
            }
            lifecycle.fire(Phase.AFTER_SCENARIO_STEP);
            if (step.stepEntry != null) step.stepEntry.stop();
        }
    }

    private static Result finish(StepExtension step, Status status, Instant start, Throwable error) {
        Result result = new Result(status, Duration.between(start, Instant.now()), error);
        step.result = result;
        if (step.stepEntry != null && error != null) step.stepEntry.error(String.valueOf(error.getMessage()));
        return result;
    }

    private static Object invoke(StepExtension step) throws ReflectiveOperationException {
        ExecutionContext context = ExecutionContext.current();
        StepRegistry registry = registry(context);
        String text = step.getUnmodifiedText();
        StepMatch match = registry == null ? StepMatch.undefined(text) : registry.match(text);
        if (step.method == null) {
            if (match.kind() == StepKind.OVERRIDE) return override(step, match, context);
            if (!match.matched() || match.step() == null || match.step().method() == null) {
                throw new UndefinedStep(text);
            }
            step.attachNativeMethod(match.step().method());
        }
        if (match.kind() == StepKind.GLUE && step.definitionFlags != null
                && !step.definitionFlags.contains(NO_STEP_TEXT_RESOLUTION)
                && step.getStepParsingMap() != null) {
            String resolved = step.getStepParsingMap().resolveWholeText(text);
            if (resolved != null && !resolved.equals(text)) {
                step.nativeText = resolved;
                if (registry != null) match = registry.match(resolved);
            }
        }
        if (match.kind() == StepKind.OVERRIDE) return override(step, match, context);
        prepareData(step, match);
        if (step.method == null) throw new UndefinedStep(text);
        return call(step, match, context);
    }

    private static Object override(StepExtension step, StepMatch match, ExecutionContext context) throws ReflectiveOperationException {
        StepOverrideRegistry.Match found = StepOverrideRegistry.match(step.getStepText())
                .orElseThrow(() -> new IllegalStateException("Step Override no longer exists for step '" + step.getStepText() + "'."));
        StepOverrideContext overrideContext = new StepOverrideContext(
                match.text(),
                step.getStepText(),
                keyword(step).trim(),
                found.captures(),
                argumentValue(step),
                context == null ? null : context.legacy(),
                step,
                step.getStepParsingMap()
        );
        try {
            return found.rule().handler().execute(overrideContext);
        } catch (RuntimeException | Error thrown) {
            throw thrown;
        } catch (Exception thrown) {
            throw new InvocationTargetException(thrown);
        }
    }

    public static void prepareData(StepExtension step, StepMatch match) {
        if (step == null || step.method == null || match == null) return;
        String name = step.method.getName();
        EngineStep engineStep = BOUND.get().get(step);
        if ("docString".equals(name) && engineStep != null && engineStep.argument() instanceof StepArgument.DocArg docArg) {
            DocString doc = CucumberModelConverter.toDocString(docArg.doc());
            step.docString = doc;
            step.dataArgumentStep = true;
            String docName = match.captures().isEmpty() ? null : match.captures().getFirst();
            if (docName != null && !docName.isBlank() && MappingProcessor.getRunMap() != null) {
                MappingProcessor.getRunMap().put(docName.trim(), doc);
            }
            step.dataContextStepNodeMap = new NodeMap(MapConfigurations.MapType.PHRASE_MAP);
            step.dataContextStepNodeMap.setDataSource(MapConfigurations.DataSource.DOC_STRING);
            step.dataContextStepNodeMap.put(DOCSTRING_KEY, doc);
        } else if ("dataTable".equals(name) && engineStep != null && engineStep.argument() instanceof StepArgument.TableArg tableArg) {
            DataTable table = CucumberModelConverter.toDataTable(tableArg.table());
            step.dataTable = table;
            step.dataArgumentStep = true;
            String tableName = match.captures().isEmpty() ? null : match.captures().getFirst();
            if (tableName != null && !tableName.isBlank() && MappingProcessor.getRunMap() != null) {
                MappingProcessor.getRunMap().put(tableName.trim(), table);
            }
            step.dataContextStepNodeMap = new NodeMap(MapConfigurations.MapType.PHRASE_MAP);
            step.dataContextStepNodeMap.setDataSource(MapConfigurations.DataSource.DATA_TABLE);
            step.dataContextStepNodeMap.put(TABLE_KEY, table);
            step.dataContextStepNodeMap.merge(toRowsStringMultimap(table));
        }
    }

    @SuppressWarnings("unchecked")
    private static Object call(StepExtension step, StepMatch match, ExecutionContext context) throws ReflectiveOperationException {
        Method method = step.method;
        Map<Class<?>, Object> instances = instances(context);
        Object target = null;
        if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            target = instances.computeIfAbsent(method.getDeclaringClass(), type -> {
                try {
                    return type.getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                }
            });
        }
        method.setAccessible(true);
        EngineStep engineStep = BOUND.get().get(step);
        Object[] args = arguments(method, match, engineStep);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw failure;
        }
    }

    private static Object[] arguments(Method method, StepMatch match, EngineStep step) {
        Type[] types = method.getGenericParameterTypes();
        List<String> captures = match == null || match.captures() == null ? List.of() : match.captures();
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
        if (step == null) {
            if (raw == DataTable.class) return DataTable.emptyDataTable();
            if (raw == Table.class) return Table.empty();
            if (raw == DocText.class) return DocText.of("");
            return DocString.create("");
        }
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
        if (raw == double.class || raw == Double.class) return Double.parseDouble(value);
        return value;
    }

    private static Class<?> raw(Type type) {
        return type instanceof Class<?> clazz ? clazz : Object.class;
    }

    private static Object argumentValue(StepExtension step) {
        EngineStep engineStep = BOUND.get().get(step);
        if (engineStep == null) return null;
        if (engineStep.argument() instanceof StepArgument.TableArg table) return CucumberModelConverter.toDataTable(table.table());
        if (engineStep.argument() instanceof StepArgument.DocArg doc) return CucumberModelConverter.toDocString(doc.doc());
        return null;
    }

    private static String keyword(StepExtension step) {
        String keyword = step.nativeKeyword;
        if (keyword == null || keyword.isBlank()) return "";
        return keyword.endsWith(" ") ? keyword : keyword + " ";
    }

    private static void fireHooks(String phase, ExecutionContext context) {
        StepRegistry registry = registry(context);
        if (registry == null) return;
        try {
            registry.fire(phase, tags(context), instances(context));
        } catch (RuntimeException failure) {
            throw failure;
        }
    }

    private static ExecutionContext contextOf() {
        return ExecutionContext.current();
    }

    @SuppressWarnings("unchecked")
    private static List<String> tags(ExecutionContext context) {
        if (context == null) return List.of();
        Object tags = context.resources().get("tags");
        if (tags instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object item : list) values.add(item == null ? "" : String.valueOf(item));
            return values;
        }
        return List.of();
    }

    private static StepRegistry registry(ExecutionContext context) {
        if (context == null) return null;
        Object registry = context.resources().get("registry");
        return registry instanceof StepRegistry steps ? steps : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<Class<?>, Object> instances(ExecutionContext context) {
        if (context == null) return new IdentityHashMap<>();
        Object existing = context.resources().get("instances");
        if (existing instanceof Map<?, ?> map) return (Map<Class<?>, Object>) map;
        Map<Class<?>, Object> created = new IdentityHashMap<>();
        context.resource("instances", created);
        return created;
    }

    private static Throwable unwrap(Throwable thrown) {
        if (thrown instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            return invocation.getCause();
        }
        if (thrown instanceof IllegalStateException illegal && illegal.getCause() instanceof ReflectiveOperationException) {
            return illegal.getCause();
        }
        return thrown;
    }

    static final class UndefinedStep extends IllegalStateException {
        UndefinedStep(String text) {
            super("'" + text + "' step is undefined");
        }
    }
}
