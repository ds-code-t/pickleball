package tools.dscode.common.treeparsing.parsedComponents;

public class Attribute {
    String attrName;
    String predicateType;
    String predicateVal;

    public Attribute(String attrName, String predicateType, String predicateVal) {
        this.attrName =  attrName;
        this.predicateType = predicateType;
        this.predicateVal = predicateVal;

    }
}