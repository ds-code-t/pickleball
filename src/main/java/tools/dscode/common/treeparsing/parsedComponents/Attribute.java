package tools.dscode.common.treeparsing.parsedComponents;

import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.domoperations.ExecutionDictionary;

import static tools.dscode.common.domoperations.ExecutionDictionary.Op.getOpFromString;

public class Attribute {
    String attrName;
    ExecutionDictionary.Op predicateType;
    ValueWrapper predicateVal;

    @Override
    public String toString() {
        return "Attribute{" +
                "attrName='" + attrName + '\'' +
                ", predicateType=" + predicateType +
                ", predicateVal=" + predicateVal +
                '}';
    }

    public Attribute(String attrName, ExecutionDictionary.Op predicateType, ValueWrapper predicateVal) {
        this.attrName =  attrName;
        this.predicateType = predicateType;
        this.predicateVal = predicateVal;
    }

    public Attribute(String attrName, String predicateTypeString, ValueWrapper predicateVal) {
        this(attrName, getOpFromString(predicateTypeString), predicateVal);
    }
}