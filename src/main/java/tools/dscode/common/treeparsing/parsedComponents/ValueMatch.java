package tools.dscode.common.treeparsing.parsedComponents;

import tools.dscode.common.treeparsing.MatchNode;

public class ValueMatch extends Component {
    public String value;
    public String unit;

    public ValueMatch(MatchNode valueNode) {
        super(valueNode);
        this.value = valueNode.getStringFromLocalState("value");
        this.unit = valueNode.getStringFromLocalState("unit");
    }
}