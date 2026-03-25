package io.cucumber.core.stepexpression;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.core.backend.ParameterInfo;
import io.cucumber.core.backend.TypeResolver;
import io.cucumber.datatable.DataTable;
import io.cucumber.datatable.DataTableTypeRegistryTableConverter;
import io.cucumber.docstring.DocString;
import io.cucumber.docstring.DocStringTypeRegistryDocStringConverter;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Locale;

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
            return docString.convert(Object.class.equals(targetType) ? DocString.class : targetType);
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

    private static DocStringTypeRegistryDocStringConverter runtimeDocStringConverter() {
        try {
            DocStringTypeRegistryDocStringConverter converter = getDocStringTypeRegistryDocStringConverter();
            if (converter != null) {
                return converter;
            }
        } catch (Exception ignored) {
        }
        return new DocStringTypeRegistryDocStringConverter(
                new StepTypeRegistry(Locale.ENGLISH).docStringTypeRegistry()
        );
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