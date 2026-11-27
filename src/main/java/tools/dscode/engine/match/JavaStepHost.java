package tools.dscode.engine.match;

import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.runtime.WarmClassLoader;
import tools.dscode.engine.runtime.WarmFlags;
import tools.dscode.engine.runtime.WarmPackages;
import tools.dscode.engine.runtime.WarmSpine;
import tools.dscode.store.StoreDatabase;
import tools.dscode.store.StoreGlueCompiler;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Java step definitions. The method is the one named on the {@code step_definitions} row.
 * {@code pkb_warm=off} (the default) uses the application classloader.
 * {@code pkb_warm=on} uses {@link WarmClassLoader} for consumer glue packages.
 */
public final class JavaStepHost implements ForeignStepHost {
    public JavaStepHost() {
    }

    @Override
    public String language() {
        return "java";
    }

    @Override
    public boolean supports(String language) {
        return "java".equals(language);
    }

    @Override
    public Object invoke(String definitionId, String stepText, List<String> argsJson) {
        Path project = ForeignStepHosts.project();
        if (project == null) {
            throw new IllegalStateException("error: no project for the Java step host"
                    + "\nfix: run the step from PickleballEngine with a project");
        }
        Row row = lookup(project, definitionId);
        String className = className(row.sourcePath());
        if (className == null) {
            throw new IllegalStateException("error: " + row.sourcePath() + " is not a Java glue path"
                    + "\nfix: store glue show " + row.sourcePath());
        }
        ClassLoader loader = selectLoader(className, project);
        Class<?> type;
        try {
            type = Class.forName(className, true, loader);
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("error: Java glue class " + className + " was not found"
                    + "\nfix: compile the glue before the run", failure);
        }
        Method method = method(type, row.methodName(), argsJson);
        List<String> values = argsJson;
        if (values == null) {
            List<String> matched = PatternMatcher.match(
                    row.pattern(), stepText == null ? "" : stepText, row.parameterTypes(), method.getGenericParameterTypes());
            values = matched == null ? List.of() : matched;
        }
        Object[] args = convert(method.getParameterTypes(), values);
        Object target = null;
        try {
            method.setAccessible(true);
            if (!Modifier.isStatic(method.getModifiers())) {
                var constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                target = constructor.newInstance();
            }
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("error: " + className + "." + method.getName() + " failed"
                    + "\nfix: store glue show " + row.sourcePath(), cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("error: " + className + "." + method.getName() + " could not be called"
                    + "\nfix: store glue show " + row.sourcePath(), failure);
        }
    }

    /** Application loader when warm is off. {@link WarmClassLoader} when warm is on. */
    public static ClassLoader selectLoader(String className, Path project) {
        ClassLoader application = JavaStepHost.class.getClassLoader();
        if (application == null) application = ClassLoader.getSystemClassLoader();
        if (!WarmFlags.enabled()) return application;
        ExecutionContext context = ExecutionContext.current();
        if (context != null && context.subprocess()) return application;
        String snapshot = context == null ? "live" : context.id();
        WarmClassLoader warm = WarmSpine.shared().loader(snapshot, warmUrls(project), WarmPackages.resolve(project));
        if (context != null) context.holdWarm(warm);
        return warm;
    }

    private static URL[] warmUrls(Path project) {
        List<URL> urls = new ArrayList<>();
        addUrl(urls, StoreGlueCompiler.warmClasses(project));
        return urls.toArray(URL[]::new);
    }

    private static void addUrl(List<URL> urls, Path directory) {
        if (directory == null || !java.nio.file.Files.isDirectory(directory)) return;
        try {
            urls.add(directory.toUri().toURL());
        } catch (MalformedURLException ignored) {
            // A classes directory that is not a URL is skipped. The parent loader still sees the classpath.
        }
    }

    static String className(String sourcePath) {
        if (sourcePath == null) return null;
        String norm = sourcePath.replace('\\', '/');
        String marker = null;
        if (norm.contains("src/main/java/")) marker = "src/main/java/";
        else if (norm.contains("src/test/java/")) marker = "src/test/java/";
        if (marker == null) return null;
        String relative = norm.substring(norm.indexOf(marker) + marker.length());
        if (!relative.endsWith(".java")) return null;
        return relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
    }

    private static Row lookup(Path project, String definitionId) {
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement("""
                     SELECT pattern, parameter_types, method_name, source_path
                     FROM step_definitions
                     WHERE definition_id = ? AND removed = 0
                     """)) {
            statement.setString(1, definitionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("error: step definition " + definitionId + " was not found"
                            + "\nfix: store hydrate");
                }
                return new Row(
                        rows.getString(1),
                        rows.getString(2) == null ? "[]" : rows.getString(2),
                        rows.getString(3),
                        rows.getString(4));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("error: step definition " + definitionId + " was not read"
                    + "\nfix: store hydrate", failure);
        }
    }

    private static Method method(Class<?> type, String name, List<String> args) {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("error: the Java step row has no method name"
                    + "\nfix: store hydrate");
        }
        List<Method> named = new ArrayList<>();
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.getName().equals(name) && !method.isSynthetic() && !method.isBridge()) named.add(method);
            }
            cursor = cursor.getSuperclass();
        }
        if (named.isEmpty()) {
            throw new IllegalStateException("error: no Java method " + type.getName() + "." + name
                    + "\nfix: store glue show " + type.getName());
        }
        if (named.size() == 1) return named.getFirst();
        if (args != null) {
            for (Method method : named) {
                if (method.getParameterCount() == args.size()) return method;
            }
        }
        return named.getFirst();
    }

    private static Object[] convert(Class<?>[] types, List<String> values) {
        Object[] args = new Object[types.length];
        for (int index = 0; index < types.length; index++) {
            String raw = values != null && index < values.size() ? values.get(index) : null;
            args[index] = convertOne(types[index], raw);
        }
        return args;
    }

    private static Object convertOne(Class<?> type, String raw) {
        if (raw == null) return null;
        if (type == String.class || type == Object.class) return raw;
        if (type == int.class || type == Integer.class) return Integer.decode(raw);
        if (type == long.class || type == Long.class) return Long.decode(raw);
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(raw);
        if (type == double.class || type == Double.class) return Double.parseDouble(raw);
        return raw;
    }

    private record Row(String pattern, String parameterTypes, String methodName, String sourcePath) {
    }
}
