package tools.dscode.engine.match;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;

/** One annotated step definition. The annotation value is stored unchanged. */
public record GlueStep(
        String keyword,
        String pattern,
        Method method,
        List<Type> expressionTypes,
        boolean dataTable,
        boolean docString,
        StepKind kind,
        String parameterTypes,
        String definitionId,
        String host,
        String contentHash
) {
    public GlueStep(
            String keyword,
            String pattern,
            Method method,
            List<Type> expressionTypes,
            boolean dataTable,
            boolean docString,
            StepKind kind,
            String parameterTypes
    ) {
        this(keyword, pattern, method, expressionTypes, dataTable, docString, kind, parameterTypes, "", "java", "");
    }

    public GlueStep(
            String keyword,
            String pattern,
            Method method,
            List<Type> expressionTypes,
            boolean dataTable,
            boolean docString,
            StepKind kind
    ) {
        this(keyword, pattern, method, expressionTypes, dataTable, docString, kind, "");
    }

    public GlueStep {
        keyword = keyword == null ? "" : keyword;
        pattern = pattern == null ? "" : pattern;
        expressionTypes = expressionTypes == null ? List.of() : List.copyOf(expressionTypes);
        kind = kind == null ? StepKind.GLUE : kind;
        parameterTypes = parameterTypes == null ? "" : parameterTypes;
        definitionId = definitionId == null ? "" : definitionId;
        host = host == null || host.isBlank() ? "java" : host;
        contentHash = contentHash == null ? "" : contentHash;
    }

    public String location() {
        if (method == null) return "";
        return method.getDeclaringClass().getName() + "." + method.getName();
    }
}
