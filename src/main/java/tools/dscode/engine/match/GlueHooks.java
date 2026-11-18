package tools.dscode.engine.match;

import io.cucumber.core.backend.Status;
import io.cucumber.core.backend.TestCaseState;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.SourceLocation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** Builds the {@code Scenario} argument consumer {@code @Before} hooks expect. */
final class GlueHooks {
    private static final Constructor<?> SCENARIO;

    static {
        try {
            Constructor<?> ctor = io.cucumber.java.Scenario.class.getDeclaredConstructor(TestCaseState.class);
            ctor.setAccessible(true);
            SCENARIO = ctor;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private GlueHooks() {
    }

    static boolean callable(Method method) {
        if (method == null) return false;
        for (Class<?> parameter : method.getParameterTypes()) {
            if (parameter != io.cucumber.java.Scenario.class) return false;
        }
        return true;
    }

    static Object[] arguments(Method method) {
        Class<?>[] parameters = method.getParameterTypes();
        Object[] args = new Object[parameters.length];
        if (parameters.length == 0) return args;
        Object scenario = scenario();
        for (int index = 0; index < parameters.length; index++) args[index] = scenario;
        return args;
    }

    private static Object scenario() {
        ExecutionContext context = ExecutionContext.current();
        if (context != null) {
            Object cached = context.resources().get("cucumber.scenario");
            if (cached != null) return cached;
            Object created = create(context);
            context.resource("cucumber.scenario", created);
            return created;
        }
        return create(null);
    }

    private static Object create(ExecutionContext context) {
        EngineScenario scenario = context == null ? null : context.scenario();
        String name = scenario == null ? "" : scenario.name();
        List<String> tags = scenario == null ? List.of() : scenario.tags();
        SourceLocation location = scenario == null || scenario.location() == null
                ? SourceLocation.unknown()
                : scenario.location();
        String id = context == null ? name : context.id();
        boolean failed = context != null && !context.failures().isEmpty();
        TestCaseState state = new TestCaseState() {
            @Override
            public Collection<String> getSourceTagNames() {
                return tags;
            }

            @Override
            public Status getStatus() {
                return failed ? Status.FAILED : Status.PASSED;
            }

            @Override
            public boolean isFailed() {
                return failed;
            }

            @Override
            public void attach(byte[] data, String mediaType, String name) {
            }

            @Override
            public void attach(String data, String mediaType, String name) {
            }

            @Override
            public void log(String text) {
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getId() {
                return id;
            }

            @Override
            public URI getUri() {
                return uri(location.uri());
            }

            @Override
            public Integer getLine() {
                return location.line();
            }
        };
        try {
            return SCENARIO.newInstance(state);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not build a Scenario for a glue hook.", failure);
        }
    }

    private static URI uri(String raw) {
        if (raw == null || raw.isBlank()) return URI.create("unknown:scenario");
        try {
            return URI.create(raw);
        } catch (IllegalArgumentException ignored) {
            // A filesystem path is not a URI.
        }
        try {
            return Path.of(raw).toUri();
        } catch (RuntimeException ignored) {
            return URI.create("unknown:scenario");
        }
    }
}
