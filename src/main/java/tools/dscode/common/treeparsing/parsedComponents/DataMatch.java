package tools.dscode.common.treeparsing.parsedComponents;

import tools.dscode.common.treeparsing.MatchNode;

public class DataMatch extends ElementMatch {
    public DataMatch(MatchNode matchNode) {
        super(matchNode);
        name = "dataMatch";

        if(category.equals("Data Table"))
        {

        } else if(category.equals("Data Row"))
        {

        }
    }

}
