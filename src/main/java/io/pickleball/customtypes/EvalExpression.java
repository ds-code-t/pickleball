package io.pickleball.customtypes;

import java.util.List;

public class EvalExpression {

    public final List<String> initialExpressions;

    public EvalExpression(String... expressions)
    {
        initialExpressions = List.of(expressions);
    }
}
