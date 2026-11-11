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
        StepKind kind
) {
    public GlueStep {
        keyword = keyword == null ? "" : keyword;
        pattern = pattern == null ? "" : pattern;
        expressionTypes = expressionTypes == null ? List.of() : List.copyOf(expressionTypes);
        kind = kind == null ? StepKind.GLUE : kind;
    }

    public String location() {
        if (method == null) return "";
        return method.getDeclaringClass().getName() + "." + method.getName();
    }
}
