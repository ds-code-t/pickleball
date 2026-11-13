package tools.dscode.engine.adapter;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.options.Constants;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.EventListener;
import io.cucumber.plugin.event.EventHandler;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.Step;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestStep;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.tagexpressions.TagExpressionParser;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import tools.dscode.coredefinitions.GeneralSteps;
import tools.dscode.dalton.DaltonFeatureSource;
import tools.dscode.engine.EngineLog;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.match.StepRegistry;
import tools.dscode.engine.model.DocText;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.model.ScenarioResult;
import tools.dscode.engine.model.SourceLocation;
import tools.dscode.engine.model.StepArgument;
import tools.dscode.engine.model.StepResult;
import tools.dscode.engine.model.Table;
import tools.dscode.engine.parse.GherkinEngineParser;
import tools.dscode.engine.runtime.NativeScenarioRun;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * JUnit entry when the native engine is selected.
 * {@code pkb_engine=cucumber} keeps the Cucumber delegate.
 * Discovered pickles run through {@link NativeScenarioRun}. Plugin listeners
 * still receive scenario and step results, and a tag or name that matches
 * nothing is not reported as a passing test.
 */
public final class NativeSuiteExecution {
    private NativeSuiteExecution() {
    }

    public static void execute(ExecutionRequest request) {
        // Discovery constructs Cucumber TestCases on this thread and leaves the
        // last one in the scenario thread local. Native registration, soft
        // failures, and teardown must see the native state instead.
        io.cucumber.core.runner.CurrentScenarioState parked =
                io.cucumber.core.runner.CurrentScenarioState.currentScenarioState.get();
        io.cucumber.core.runner.CurrentScenarioState.currentScenarioState.remove();
        EngineExecutionListener listener = request.getEngineExecutionListener();
        TestDescriptor root = request.getRootTestDescriptor();
        listener.executionStarted(root);
        Throwable failure = null;
        try {
            GeneralSteps.startSuite();
            String tags = setting(request, Constants.FILTER_TAGS_PROPERTY_NAME, "pkb_tags");
            String name = setting(request, Constants.FILTER_NAME_PROPERTY_NAME, "pkb_name");
            String glue = setting(request, Constants.GLUE_PROPERTY_NAME, "pkb_glue");
            Bus bus = plugins(setting(request, Constants.PLUGIN_PROPERTY_NAME, "cucumber.plugin"));
            StepRegistry registry = registry(glue);
            Catalog catalog = new Catalog();
            EngineOptions options = EngineOptions.of().project(tools.dscode.engine.EngineConfig.project());
            walk(listener, root, tags, name, registry, catalog, options, bus);
        } catch (Throwable error) {
            failure = error;
        } finally {
            try {
                GeneralSteps.finishSuite();
            } catch (Throwable cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
            if (parked == null) {
                io.cucumber.core.runner.CurrentScenarioState.currentScenarioState.remove();
            } else {
                io.cucumber.core.runner.CurrentScenarioState.currentScenarioState.set(parked);
            }
        }
        TestExecutionResult outcome = failure == null
                ? TestExecutionResult.successful()
                : TestExecutionResult.failed(failure);
        listener.executionFinished(root, outcome);
    }

    private static boolean walk(
            EngineExecutionListener listener,
            TestDescriptor node,
            String tags,
            String name,
            StepRegistry registry,
            Catalog catalog,
            EngineOptions options,
            Bus bus
    ) {
        if (node.getType() == TestDescriptor.Type.TEST) {
            Pickle pickle = pickleOf(node);
            if (pickle == null || !selected(pickle, tags, name)) return false;
            listener.executionStarted(node);
            TestExecutionResult result = runPickle(pickle, registry, catalog, options, bus);
            listener.executionFinished(node, result);
            return result.getStatus() == TestExecutionResult.Status.FAILED;
        }
        boolean started = node.getParent().isPresent();
        if (started) listener.executionStarted(node);
        boolean failed = false;
        for (TestDescriptor child : node.getChildren()) {
            if (walk(listener, child, tags, name, registry, catalog, options, bus)) failed = true;
        }
        if (started) {
            listener.executionFinished(node, failed
                    ? TestExecutionResult.failed(new IllegalStateException("a scenario failed"))
                    : TestExecutionResult.successful());
        }
        return failed;
    }

    private static TestExecutionResult runPickle(
            Pickle pickle,
            StepRegistry registry,
            Catalog catalog,
            EngineOptions options,
            Bus bus
    ) {
        try {
            EngineScenario scenario = catalog.take(pickle);
            ScenarioResult result = NativeScenarioRun.run(scenario, registry, options, pickle);
            Status pluginStatus = pluginStatus(result);
            Throwable recorded = NativeScenarioRun.takeFailure();
            Throwable error = pluginStatus == Status.FAILED || pluginStatus == Status.UNDEFINED || pluginStatus == Status.AMBIGUOUS
                    ? (recorded != null ? recorded : new IllegalStateException(result.message() == null || result.message().isBlank()
                    ? pluginStatus.name()
                    : result.message()))
                    : null;
            publish(bus, pickle, result, pluginStatus, error);
            EngineLog.log(options.project(), "suite", pickle.getName(), pluginStatus.name());
            if (pluginStatus == Status.FAILED || pluginStatus == Status.UNDEFINED || pluginStatus == Status.AMBIGUOUS) {
                return TestExecutionResult.failed(error == null ? new IllegalStateException(pluginStatus.name()) : error);
            }
            return TestExecutionResult.successful();
        } catch (Throwable error) {
            EngineLog.log(options.project(), "suite", pickle.getName(), error.toString());
            return TestExecutionResult.failed(error);
        }
    }

    private static Status pluginStatus(ScenarioResult result) {
        if (result == null) return Status.FAILED;
        boolean failed = result.status() == tools.dscode.engine.model.Status.FAILED
                || result.status() == tools.dscode.engine.model.Status.UNDEFINED
                || result.status() == tools.dscode.engine.model.Status.AMBIGUOUS;
        if (!failed && result.steps() != null) {
            for (StepResult step : result.steps()) {
                if (step.status() == tools.dscode.engine.model.Status.FAILED
                        || step.status() == tools.dscode.engine.model.Status.UNDEFINED
                        || step.status() == tools.dscode.engine.model.Status.AMBIGUOUS) {
                    failed = true;
                    break;
                }
            }
        }
        if (failed) {
            if (result.status() == tools.dscode.engine.model.Status.UNDEFINED) return Status.UNDEFINED;
            if (result.status() == tools.dscode.engine.model.Status.AMBIGUOUS) return Status.AMBIGUOUS;
            return Status.FAILED;
        }
        return Status.PASSED;
    }

    private static boolean selected(Pickle pickle, String tags, String name) {
        if (tags != null && !tags.isBlank()) {
            if (!TagExpressionParser.parse(tags.trim()).evaluate(pickle.getTags())) return false;
        }
        if (name != null && !name.isBlank() && !Pattern.compile(name.trim()).matcher(pickle.getName()).find()) {
            return false;
        }
        return true;
    }

    private static Pickle pickleOf(TestDescriptor node) {
        if (node == null) return null;
        Class<?> type = node.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod("getPickle");
                method.setAccessible(true);
                Object value = method.invoke(node);
                return value instanceof Pickle pickle ? pickle : null;
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            } catch (Throwable failure) {
                return null;
            }
        }
        return null;
    }

    private static StepRegistry registry(String glue) {
        StepRegistry registry = new StepRegistry();
        // JavaBackend_LoadGlue_Mutator always adds coredefinitions beside the
        // configured glue path. A package-only cucumber.glue still sees FAIL:.
        registry.scanPackage("tools.dscode.coredefinitions");
        String packages = glue == null ? "" : glue;
        for (String part : packages.split(",")) {
            String token = part.trim();
            if (token.isEmpty() || "tools.dscode.coredefinitions".equals(token)) continue;
            try {
                registry.scan(Class.forName(token));
            } catch (ClassNotFoundException notAClass) {
                registry.scanPackage(token);
            }
        }
        return registry;
    }

    private static String setting(ExecutionRequest request, String... keys) {
        if (keys == null) return "";
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            if (request != null) {
                String configured = request.getConfigurationParameters().get(key).orElse("");
                if (!configured.isBlank()) return configured.trim();
            }
            String property = System.getProperty(key);
            if (property != null && !property.isBlank()) return property.trim();
        }
        return "";
    }

    private static final class Catalog {
        private final Map<String, Deque<EngineScenario>> scenarios = new LinkedHashMap<>();
        private final Map<String, Boolean> loaded = new LinkedHashMap<>();

        EngineScenario take(Pickle pickle) {
            String uri = pickle.getUri() == null ? "" : pickle.getUri().toString();
            if (loaded.putIfAbsent(uri, Boolean.TRUE) == null) load(pickle.getUri(), uri);
            Deque<EngineScenario> queue = scenarios.get(key(uri, pickle.getName()));
            if (queue != null && !queue.isEmpty()) return queue.removeFirst();
            return fromPickle(pickle);
        }

        private void load(URI uri, String key) {
            String text;
            try {
                text = read(uri);
            } catch (IOException failure) {
                throw new IllegalStateException("Could not read " + uri + ": " + failure.getMessage(), failure);
            }
            if (text == null || text.isBlank()) return;
            EngineFeature feature = GherkinEngineParser.expand(GherkinEngineParser.parse(key, text));
            for (EngineScenario scenario : feature.scenarios()) {
                scenarios.computeIfAbsent(key(key, scenario.name()), ignored -> new ArrayDeque<>()).add(scenario);
            }
        }

        private static String key(String uri, String name) {
            return uri + "\n" + (name == null ? "" : name);
        }
    }

    private static String read(URI uri) throws IOException {
        if (uri == null) return "";
        byte[] fromDb = DaltonFeatureSource.bytesFor(uri);
        if (fromDb != null) return new String(fromDb, StandardCharsets.UTF_8);
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return Files.readString(Path.of(uri));
        }
        if ("classpath".equalsIgnoreCase(uri.getScheme())) {
            String name = io.cucumber.core.resource.ClasspathSupport.resourceName(uri);
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) loader = NativeSuiteExecution.class.getClassLoader();
            try (var input = loader.getResourceAsStream(name)) {
                if (input == null) return "";
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        try (var input = uri.toURL().openStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            return "";
        }
    }

    private static EngineScenario fromPickle(Pickle pickle) {
        List<EngineStep> steps = new ArrayList<>();
        String uri = pickle.getUri() == null ? "" : pickle.getUri().toString();
        if (pickle.getSteps() != null) {
            for (io.cucumber.core.gherkin.Step step : pickle.getSteps()) {
                StepArgument argument = StepArgument.none();
                if (step.getArgument() instanceof io.cucumber.core.gherkin.DataTableArgument table) {
                    argument = StepArgument.table(new Table(table.cells()));
                } else if (step.getArgument() instanceof io.cucumber.core.gherkin.DocStringArgument doc) {
                    argument = StepArgument.doc(new DocText(doc.getContent(), doc.getMediaType()));
                }
                int line = lineOf(step);
                steps.add(new EngineStep(
                        step.getKeyword(),
                        textOf(step),
                        argument,
                        new SourceLocation(uri, line, 0),
                        pickleMeta(step),
                        false
                ));
            }
        }
        return new EngineScenario(
                pickle.getKeyword(),
                pickle.getName(),
                "",
                pickle.getTags(),
                steps,
                List.of(),
                new SourceLocation(uri, lineOf(pickle), 0),
                Map.of()
        );
    }

    private static Map<String, String> pickleMeta(Object step) {
        Object pickle = field(step, "pickleStep");
        if (pickle == null) return Map.of();
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        putMeta(metadata, "metaText", call(pickle, "getMetaText"));
        putMeta(metadata, "inlineArgumentType", call(pickle, "getInlineArgumentType"));
        putMeta(metadata, "inlineArgumentText", call(pickle, "getInlineArgumentText"));
        return metadata.isEmpty() ? Map.of() : metadata;
    }

    private static void putMeta(Map<String, String> metadata, String key, Object value) {
        if (value == null) return;
        String text = value.toString();
        if (!text.isBlank()) metadata.put(key, text);
    }

    private static Object field(Object target, String name) {
        if (target == null || name == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field found = type.getDeclaredField(name);
                found.setAccessible(true);
                return found.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException failure) {
                return null;
            }
        }
        return null;
    }

    private static String textOf(Object target) {
        Object value = call(target, "getText");
        return value == null ? "" : value.toString();
    }

    private static int lineOf(Object target) {
        Object value = located(target);
        if (value instanceof io.cucumber.plugin.event.Location location) return location.getLine();
        return 0;
    }

    private static Object located(Object target) {
        return call(target, "getLocation");
    }

    private static Object call(Object target, String name) {
        if (target == null || name == null) return null;
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }

    private static Bus plugins(String raw) {
        Bus bus = new Bus();
        if (raw == null || raw.isBlank()) return bus;
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = NativeSuiteExecution.class.getClassLoader();
        for (String token : raw.split(",")) {
            String name = token.trim();
            if (name.isEmpty()) continue;
            int colon = name.indexOf(':');
            if (colon > 0 && name.substring(0, colon).contains(".")) name = name.substring(0, colon);
            if (!name.contains(".")) continue;
            try {
                Object plugin = Class.forName(name, true, loader).getDeclaredConstructor().newInstance();
                if (plugin instanceof ConcurrentEventListener listener) listener.setEventPublisher(bus);
                else if (plugin instanceof EventListener listener) listener.setEventPublisher(bus);
            } catch (Throwable ignored) {
                EngineLog.log(null, "suite", name, "plugin not loaded");
            }
        }
        return bus;
    }

    private static void publish(Bus bus, Pickle pickle, ScenarioResult result, Status scenarioStatus, Throwable error) {
        List<TestStep> steps = new ArrayList<>();
        List<StepResult> outcomes = result.steps() == null ? List.of() : result.steps();
        List<Object[]> published = new ArrayList<>();
        for (StepResult step : outcomes) {
            Step body = stepProxy(step.text());
            TestStep testStep = (TestStep) Proxy.newProxyInstance(
                    PickleStepTestStep.class.getClassLoader(),
                    new Class<?>[] {PickleStepTestStep.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getStep" -> body;
                        case "getStepText" -> step.text();
                        case "getStepLine" -> 0;
                        case "getUri" -> pickle.getUri();
                        case "getId" -> UUID.randomUUID();
                        case "getCodeLocation" -> "";
                        case "getPattern" -> step.text();
                        case "getDefinitionArgument", "getStepArgument" -> List.of();
                        case "toString" -> step.text();
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    }
            );
            steps.add(testStep);
            published.add(new Object[] {testStep, step});
        }
        TestCase testCase = testCase(pickle, steps);
        Instant now = Instant.now();
        for (Object[] pair : published) {
            StepResult step = (StepResult) pair[1];
            Status status = stepStatus(step);
            Throwable stepError = status == Status.FAILED || status == Status.UNDEFINED || status == Status.AMBIGUOUS
                    ? new IllegalStateException(step.message() == null || step.message().isBlank() ? status.name() : step.message())
                    : null;
            bus.publish(TestStepFinished.class, new TestStepFinished(
                    now,
                    testCase,
                    (TestStep) pair[0],
                    new Result(status, Duration.ofMillis(Math.max(0, step.durationMillis())), stepError)
            ));
        }
        bus.publish(TestCaseFinished.class, new TestCaseFinished(
                now,
                testCase,
                new Result(scenarioStatus, Duration.ZERO, error)
        ));
    }

    private static Status stepStatus(StepResult step) {
        if (step == null || step.status() == null) return Status.PASSED;
        return switch (step.status()) {
            case PASSED -> Status.PASSED;
            case SKIPPED -> Status.SKIPPED;
            case PENDING -> Status.PENDING;
            case UNDEFINED -> Status.UNDEFINED;
            case AMBIGUOUS -> Status.AMBIGUOUS;
            case FAILED -> Status.FAILED;
        };
    }

    private static Step stepProxy(String text) {
        String body = text == null ? "" : text;
        return (Step) Proxy.newProxyInstance(
                Step.class.getClassLoader(),
                new Class<?>[] {Step.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getText", "getKeyword", "toString" -> "getKeyword".equals(method.getName()) ? "* " : body;
                    case "getLine" -> 0;
                    case "getArgument" -> null;
                    case "getLocation" -> null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                }
        );
    }

    private static TestCase testCase(Pickle pickle, List<TestStep> steps) {
        UUID id = pickle.getId() == null
                ? UUID.randomUUID()
                : UUID.nameUUIDFromBytes(pickle.getId().getBytes(StandardCharsets.UTF_8));
        return (TestCase) Proxy.newProxyInstance(
                TestCase.class.getClassLoader(),
                new Class<?>[] {TestCase.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return switch (method.getName()) {
                            case "getName", "getScenarioDesignation", "toString" -> pickle.getName();
                            case "getKeyword" -> pickle.getKeyword();
                            case "getTags" -> pickle.getTags();
                            case "getTestSteps" -> steps;
                            case "getUri" -> pickle.getUri();
                            case "getId" -> id;
                            case "getLine" -> lineOf(pickle);
                            case "getLocation" -> located(pickle);
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == (args == null ? null : args[0]);
                            default -> null;
                        };
                    }
                }
        );
    }

    private static final class Bus implements EventPublisher {
        private final Map<Class<?>, List<EventHandler<?>>> handlers = new LinkedHashMap<>();

        @Override
        public <T> void registerHandlerFor(Class<T> eventType, EventHandler<T> handler) {
            handlers.computeIfAbsent(eventType, ignored -> new ArrayList<>()).add(handler);
        }

        @Override
        public <T> void removeHandlerFor(Class<T> eventType, EventHandler<T> handler) {
            List<EventHandler<?>> list = handlers.get(eventType);
            if (list != null) list.remove(handler);
        }

        @SuppressWarnings("unchecked")
        <T> void publish(Class<T> type, T event) {
            List<EventHandler<?>> list = handlers.get(type);
            if (list == null) return;
            for (EventHandler<?> handler : List.copyOf(list)) {
                ((EventHandler<T>) handler).receive(event);
            }
        }
    }
}
