package io.cucumber.core.stepexpression;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.core.backend.ParameterInfo;
import io.cucumber.core.backend.TypeResolver;
import io.cucumber.datatable.DataTable;
import io.cucumber.datatable.DataTableTypeRegistryTableConverter;
import io.cucumber.docstring.DocString;
import io.cucumber.docstring.DocStringTypeRegistry;
import io.cucumber.docstring.DocStringTypeRegistryDocStringConverter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.cucumber.core.runner.CurrentScenarioState.getDataTableTypeRegistryTableConverter;
import static io.cucumber.core.runner.CurrentScenarioState.getDocStringTypeRegistryDocStringConverter;

// add static imports for your existing helper methods here, or qualify them below:
// import static your.package.YourUtils.getDocStringTypeRegistryDocStringConverter;
// import static your.package.YourUtils.getDataTableTypeRegistryTableConverter;

public class DocStringBuilder {

    public static DocStringArgument createObjectNodeDocString(ObjectNode node) {
        return createDocString(ObjectNode.class, node.toPrettyString(), "json");
    }

    public static DocStringArgument createDocString(Class<?> targetClass, String content, String contentType) {
        ParameterInfo parameterInfo = parameterInfo(targetClass, false);
        return createDocString(parameterInfo, content, contentType);
    }

    public static DocStringArgument createDocString(ParameterInfo parameterInfo, String content, String contentType) {
        return new DocStringArgument(docStringType(parameterInfo), content, contentType);
    }

    public static DocStringTransformer<?> docStringType(ParameterInfo parameterInfo) {
        return (text, contentType) -> {
            DocString docString = DocString.create(text, contentType, runtimeDocStringConverter());
            Type targetType = parameterInfo.getTypeResolver().resolve();

            if (Object.class.equals(targetType) && !"registry".equalsIgnoreCase(String.valueOf(contentType))) {
                targetType = DocString.class;
            }

            return docString.convert(targetType);
        };
    }


    public static DataTableArgument createDataTable(Class<?> targetClass, List<List<String>> rows, boolean transpose) {
        ParameterInfo parameterInfo = parameterInfo(targetClass, transpose);
        return createDataTable(parameterInfo, rows, transpose);
    }

    public static DataTableArgument createDataTable(ParameterInfo parameterInfo, List<List<String>> rows, boolean transpose) {
        return new DataTableArgument(tableTransform(parameterInfo, transpose), rows);
    }

    public static RawTableTransformer<?> tableTransform(ParameterInfo parameterInfo, boolean transpose) {
        return raw -> {
            DataTable dataTable = DataTable.create(raw, runtimeTableConverter());
            Type targetType = parameterInfo.getTypeResolver().resolve();
            return dataTable.convert(Object.class.equals(targetType) ? DataTable.class : targetType, transpose);
        };
    }

    private static ParameterInfo parameterInfo(Type targetType, boolean transpose) {
        return new ParameterInfo() {
            @Override
            public Type getType() {
                return targetType;
            }

            @Override
            public boolean isTransposed() {
                return transpose;
            }

            @Override
            public TypeResolver getTypeResolver() {
                return () -> targetType;
            }
        };
    }

    private static final Map<Class<?>, Object> DOC_STRING_RECEIVERS = new IdentityHashMap<>();
    private static volatile DocStringTypeRegistryDocStringConverter fallbackConverter;

    private static DocStringTypeRegistryDocStringConverter runtimeDocStringConverter() {
        try {
            DocStringTypeRegistryDocStringConverter converter = getDocStringTypeRegistryDocStringConverter();
            if (converter != null) {
                return converter;
            }
        } catch (Exception ignored) {
        }
        return nativeDocStringConverter();
    }

    /**
     * Native runs have no Cucumber glue registry. The same {@code @DocStringType}
     * methods still convert registry, json, yaml, and xml arguments.
     */
    private static DocStringTypeRegistryDocStringConverter nativeDocStringConverter() {
        DocStringTypeRegistryDocStringConverter existing = fallbackConverter;
        if (existing != null) return existing;
        synchronized (DocStringBuilder.class) {
            if (fallbackConverter != null) return fallbackConverter;
            DocStringTypeRegistry types = new StepTypeRegistry(Locale.ENGLISH).docStringTypeRegistry();
            registerDocStringTypes(types, tools.dscode.coredefinitions.DocStringDefinitions.class);
            fallbackConverter = new DocStringTypeRegistryDocStringConverter(types);
            return fallbackConverter;
        }
    }

    private static void registerDocStringTypes(DocStringTypeRegistry types, Class<?> owner) {
        for (Method method : owner.getDeclaredMethods()) {
            io.cucumber.java.DocStringType annotation = method.getAnnotation(io.cucumber.java.DocStringType.class);
            if (annotation == null || method.getParameterCount() != 1) continue;
            method.setAccessible(true);
            types.defineDocStringType(new io.cucumber.docstring.DocStringType(
                    method.getGenericReturnType(),
                    annotation.contentType(),
                    content -> invokeDocStringType(method, content)
            ));
        }
    }

    private static Object invokeDocStringType(Method method, String content) throws Throwable {
        Object receiver = null;
        if (!Modifier.isStatic(method.getModifiers())) {
            receiver = DOC_STRING_RECEIVERS.computeIfAbsent(method.getDeclaringClass(), type -> {
                try {
                    return type.getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                }
            });
        }
        try {
            return method.invoke(receiver, content);
        } catch (InvocationTargetException failure) {
            throw failure.getCause() == null ? failure : failure.getCause();
        }
    }

    private static DataTableTypeRegistryTableConverter runtimeTableConverter() {
        try {
            DataTableTypeRegistryTableConverter converter = getDataTableTypeRegistryTableConverter();
            if (converter != null) {
                return converter;
            }
        } catch (Exception ignored) {
        }
        return new DataTableTypeRegistryTableConverter(
                new StepTypeRegistry(Locale.ENGLISH).dataTableTypeRegistry()
        );
    }
}