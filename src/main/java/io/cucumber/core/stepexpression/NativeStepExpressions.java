package io.cucumber.core.stepexpression;

import io.cucumber.cucumberexpressions.Expression;
import io.cucumber.datatable.DataTable;

/** Builds a {@link StepExpression} for a native frame. Cucumber's constructor is package-private. */
public final class NativeStepExpressions {
    private NativeStepExpressions() {
    }

    public static StepExpression of(Expression expression) {
        DocStringTransformer<String> documents = (content, type) -> content;
        RawTableTransformer<DataTable> tables = DataTable::create;
        return new StepExpression(expression, documents, tables);
    }
}
