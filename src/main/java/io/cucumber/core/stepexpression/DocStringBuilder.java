package io.cucumber.core.stepexpression;

import io.cucumber.core.backend.ParameterInfo;
import io.cucumber.datatable.DataTable;
import io.cucumber.datatable.DataTableTypeRegistryTableConverter;
import io.cucumber.docstring.DocString;
import io.cucumber.docstring.DocStringTypeRegistryDocStringConverter;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Locale;

public class DocStringBuilder {
    public static DocStringArgument createDocString(ParameterInfo parameterInfo, String content, String contentType) {
        return new DocStringArgument(docStringType(parameterInfo), content, contentType);
    }

    public static DocStringTransformer<?> docStringType(ParameterInfo parameterInfo) {
        return  (text, contentType) -> {
            DocString docString = DocString.create(text, contentType, new DocStringTypeRegistryDocStringConverter( new StepTypeRegistry(Locale.ENGLISH).docStringTypeRegistry()));
            Type targetType = parameterInfo.getTypeResolver().resolve();
            return docString.convert(Object.class.equals(targetType) ? DocString.class : targetType);
        };
    }

    public static DataTableArgument createDataTable(ParameterInfo parameterInfo, List<List<String>> rows, boolean transpose) {
        return new DataTableArgument(tableTransform(parameterInfo, transpose), rows);
    }

    public static RawTableTransformer<?> tableTransform(ParameterInfo parameterInfo, boolean transpose) {
        return (List<List<String>> raw) -> {
            DataTable dataTable = DataTable.create(raw, new DataTableTypeRegistryTableConverter(new StepTypeRegistry(Locale.ENGLISH).dataTableTypeRegistry()));
            Type targetType = parameterInfo.getTypeResolver().resolve();
            return dataTable.convert(Object.class.equals(targetType) ? DataTable.class : targetType, transpose);
        };
    }
}
