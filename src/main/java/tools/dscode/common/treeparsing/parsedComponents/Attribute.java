package tools.dscode.common.treeparsing.parsedComponents;

public class Attribute {
    String attrName;
    String predicateType;
    String predicateVal;

    public Attribute(String attrName, String predicateType, String predicateVal) {
        this.attrName = attrName == null || attrName.isBlank() ? "Text" : attrName;
        this.predicateType = predicateType;
        this.predicateVal = predicateVal;
        System.out.println("@@attrName: " + attrName + ", predicateType: " + predicateType + ", predicateVal: " + predicateVal + "");
    }
}