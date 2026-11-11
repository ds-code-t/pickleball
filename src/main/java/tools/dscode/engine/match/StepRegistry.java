package tools.dscode.engine.match;

import io.cucumber.cucumberexpressions.Argument;
import io.cucumber.cucumberexpressions.Expression;
import io.cucumber.cucumberexpressions.ExpressionFactory;
import io.cucumber.cucumberexpressions.ParameterTypeRegistry;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.java.StepDefinitionAnnotation;
import tools.dscode.control.override.StepOverrideRegistry;
import tools.dscode.control.override.StepOverrideRule;
import tools.dscode.engine.EngineLog;
import tools.dscode.engine.model.DocText;
import tools.dscode.engine.model.Table;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the same step annotations Cucumber reads and matches with
 * {@link ExpressionFactory}, which is the regex-versus-Cucumber-expression rule
 * Cucumber uses. Annotation values are not rewritten.
 */
public final class StepRegistry {
    private final List<GlueStep> steps = new ArrayList<>();
    private final ExpressionFactory expressions;
    private final Map<String, Expression> compiled = new LinkedHashMap<>();
    private Path logProject;

    public StepRegistry() {
        this(Locale.ENGLISH);
    }

    public StepRegistry(Locale locale) {
        expressions = new ExpressionFactory(new ParameterTypeRegistry(locale == null ? Locale.ENGLISH : locale));
    }

    public StepRegistry logTo(Path project) {
        this.logProject = project;
        return this;
    }

    public List<GlueStep> steps() {
        return List.copyOf(steps);
    }

    public StepRegistry scan(Class<?>... types) {
        if (types == null) return this;
        for (Class<?> type : types) {
            if (type == null) continue;
            for (Method method : type.getMethods()) {
                scanMethod(method);
            }
        }
        return this;
    }

    public GlueStep register(String keyword, String pattern, Method method) {
        GlueStep step = define(keyword, pattern, method);
        add(step);
        return step;
    }

    public StepMatch match(String text) {
        Optional<StepMatch> override = liveOverride(text);
        if (override.isPresent()) return override.get();
        return matchGlue(text);
    }

    /** Override rules registered for {@code scenarioId}, then glue. No live Cucumber scenario required. */
    public StepMatch match(String text, String scenarioId) {
        if (scenarioId != null && !scenarioId.isBlank()) {
            Optional<StepMatch> override = overrides(text, StepOverrideRegistry.rules(scenarioId));
            if (override.isPresent()) return override.get();
        }
        return matchGlue(text);
    }

    public StepMatch match(String text, List<StepOverrideRule> rules) {
        Optional<StepMatch> override = overrides(text, rules);
        if (override.isPresent()) return override.get();
        return matchGlue(text);
    }

    /** Names the executor a match will use. It does not run the step. */
    public static String dispatchStub(StepMatch match) {
        if (match == null || !match.matched()) return "stub:undefined";
        return "stub:" + match.kind().name().toLowerCase(Locale.ROOT);
    }

    private Optional<StepMatch> liveOverride(String text) {
        try {
            Optional<StepOverrideRegistry.Match> found = StepOverrideRegistry.match(text);
            if (found.isEmpty()) return Optional.empty();
            StepOverrideRegistry.Match match = found.get();
            return Optional.of(new StepMatch(
                    StepKind.OVERRIDE,
                    text,
                    null,
                    match.captures(),
                    match.rule().id()
            ));
        } catch (IllegalStateException failure) {
            if (failure instanceof StepOverrideRegistry.AmbiguousStepOverrideException) throw failure;
            return Optional.empty();
        }
    }

    private Optional<StepMatch> overrides(String text, List<StepOverrideRule> rules) {
        if (rules == null || rules.isEmpty()) return Optional.empty();
        List<OverrideHit> hits = new ArrayList<>();
        String body = text == null ? "" : text;
        for (StepOverrideRule rule : rules) {
            if (rule == null) continue;
            Matcher matcher = Pattern.compile(rule.pattern()).matcher(body);
            if (!matcher.matches()) continue;
            List<String> groups = new ArrayList<>();
            for (int index = 1; index <= matcher.groupCount(); index++) {
                groups.add(matcher.group(index));
            }
            hits.add(new OverrideHit(rule, List.copyOf(groups)));
        }
        if (hits.isEmpty()) return Optional.empty();
        hits.sort(Comparator.comparing(hit -> hit.rule().id()));
        if (hits.size() > 1) {
            throw new AmbiguousMatchException(body, hits.stream().map(hit -> hit.rule().id()).toList());
        }
        OverrideHit hit = hits.getFirst();
        return Optional.of(new StepMatch(StepKind.OVERRIDE, body, null, hit.captures(), hit.rule().id()));
    }

    private record OverrideHit(StepOverrideRule rule, List<String> captures) {
    }

    private StepMatch matchGlue(String text) {
        String body = text == null ? "" : text;
        List<GlueStep> hits = new ArrayList<>();
        List<List<String>> captures = new ArrayList<>();
        for (GlueStep step : steps) {
            List<Argument<?>> arguments = expression(step).match(body, step.expressionTypes().toArray(Type[]::new));
            if (arguments == null) continue;
            hits.add(step);
            captures.add(stringify(arguments));
        }
        if (hits.isEmpty()) {
            EngineLog.log(logProject, "match", "undefined", body);
            return StepMatch.undefined(body);
        }
        if (hits.size() > 1) {
            throw new AmbiguousMatchException(body, hits.stream().map(GlueStep::location).toList());
        }
        GlueStep step = hits.getFirst();
        EngineLog.log(logProject, "match", step.location(), step.kind() + " " + body);
        return new StepMatch(step.kind(), body, step, captures.getFirst(), "");
    }

    private Expression expression(GlueStep step) {
        return compiled.computeIfAbsent(step.pattern(), expressions::createExpression);
    }

    private void scanMethod(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            for (Found found : valuesOf(annotation)) {
                add(define(found.keyword(), found.pattern(), method));
            }
        }
    }

    private void add(GlueStep step) {
        String key = step.location() + " " + step.pattern();
        for (GlueStep existing : steps) {
            if ((existing.location() + " " + existing.pattern()).equals(key)) return;
        }
        steps.add(step);
        EngineLog.log(logProject, "registry", step.location(), step.keyword() + " " + step.pattern());
    }

    private static GlueStep define(String keyword, String pattern, Method method) {
        Type[] types = method == null ? new Type[0] : method.getGenericParameterTypes();
        boolean table = false;
        boolean doc = false;
        List<Type> expressionTypes = new ArrayList<>();
        for (int index = 0; index < types.length; index++) {
            Class<?> raw = raw(types[index]);
            boolean argument = raw == DataTable.class || raw == DocString.class || raw == Table.class || raw == DocText.class;
            if (argument && index == types.length - 1) {
                table = raw == DataTable.class || raw == Table.class;
                doc = raw == DocString.class || raw == DocText.class;
                continue;
            }
            expressionTypes.add(types[index]);
        }
        return new GlueStep(keyword, pattern, method, expressionTypes, table, doc, classify(pattern, method));
    }

    static StepKind classify(String pattern, Method method) {
        String name = method == null ? "" : method.getName();
        String source = pattern == null ? "" : pattern;
        if (name.equals("executeDynamicStepBlockConditionals") || source.contains("IF:") || source.contains("ELSE-IF:") || source.contains("ELSE:")) {
            return StepKind.BLOCK_CONDITIONAL;
        }
        if (name.equals("executeDynamicStep") || source.startsWith("^,(?s)")) {
            return StepKind.DYNAMIC;
        }
        if (name.startsWith("runScenario") || name.startsWith("runComponent") || source.contains("SCENARIO:") || source.contains("COMPONENT") || source.startsWith("^RUN")) {
            return StepKind.RUN;
        }
        if (source.contains("SERVICE CALL") || source.startsWith("^CALL:") || name.toLowerCase(Locale.ROOT).contains("servicecall")) {
            return StepKind.SERVICE_CALL;
        }
        return StepKind.GLUE;
    }

    private static List<Found> valuesOf(Annotation annotation) {
        if (annotation == null) return List.of();
        Class<? extends Annotation> type = annotation.annotationType();
        try {
            if (type.isAnnotationPresent(StepDefinitionAnnotation.class)) {
                Object value = type.getMethod("value").invoke(annotation);
                if (value instanceof String pattern) {
                    return List.of(new Found(type.getSimpleName(), pattern));
                }
            }
            Method value = type.getMethod("value");
            Object nested = value.invoke(annotation);
            if (nested instanceof Annotation[] annotations) {
                List<Found> found = new ArrayList<>();
                for (Annotation child : annotations) {
                    found.addAll(valuesOf(child));
                }
                return found;
            }
        } catch (ReflectiveOperationException ignored) {
            return List.of();
        }
        return List.of();
    }

    private static List<String> stringify(List<Argument<?>> arguments) {
        List<String> values = new ArrayList<>();
        if (arguments == null) return values;
        for (Argument<?> argument : arguments) {
            Object value = argument == null ? null : argument.getValue();
            values.add(value == null ? null : String.valueOf(value));
        }
        return Collections.unmodifiableList(values);
    }

    private static Class<?> raw(Type type) {
        if (type instanceof Class<?> clazz) return clazz;
        return Object.class;
    }

    private record Found(String keyword, String pattern) {
    }
}
