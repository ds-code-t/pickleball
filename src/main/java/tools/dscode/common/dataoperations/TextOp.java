package tools.dscode.common.dataoperations;

import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.domoperations.ExecutionDictionary;

import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;
import static tools.dscode.common.domoperations.ExecutionDictionary.Op.getOpFromString;

public record TextOp(ValueWrapper text, ExecutionDictionary.Op op) {
    public TextOp {
        text = text == null ? createValueWrapper(null) : text;
        op = op == null ? ExecutionDictionary.Op.DEFAULT : op;
    }

    public TextOp(ValueWrapper text, String op) {
        this(text, resolveOp(op));
    }

    public static TextOp of(Object text, ExecutionDictionary.Op op) {
        return new TextOp(createValueWrapper(text), op);
    }

    private static ExecutionDictionary.Op resolveOp(String op) {
        ExecutionDictionary.Op resolved = getOpFromString(op == null ? "" : op);
        if (resolved == null) {
            throw new IllegalArgumentException("Unknown text operation: " + op);
        }
        return resolved;
    }
}
