package tools.dscode.common.treeparsing.parsedComponents;

import tools.dscode.common.assertions.ValueWrapper;

public class Attribute {
    String attrName;
    String predicateType;
    ValueWrapper predicateVal;

    public Attribute(String attrName, String predicateType, ValueWrapper predicateVal) {
        this.attrName =  attrName;
        this.predicateType = predicateType;
        this.predicateVal = predicateVal;
    }
}