package tools.dscode.common.treeparsing.parsedComponents;

import tools.dscode.common.dataelements.DataElementRegistry;
import tools.dscode.common.treeparsing.MatchNode;

public final class ElementMatchFactory {
    private ElementMatchFactory() {
    }

    public static ElementMatch create(
            PhraseData phraseData,
            MatchNode elementNode
    ) {
        String category = elementNode.getStringFromLocalState("type");
        return DataElementRegistry.contains(category)
                ? new DataElementMatch(phraseData, elementNode)
                : new ElementMatch(phraseData, elementNode);
    }

    public static ElementMatch copy(
            PhraseData phraseData,
            ElementMatch elementMatch
    ) {
        if (elementMatch instanceof DataElementMatch) {
            return new DataElementMatch(phraseData, elementMatch);
        }
        if (DataElementRegistry.contains(elementMatch.category)) {
            return new DataElementMatch(phraseData, elementMatch);
        }
        return new ElementMatch(phraseData, elementMatch);
    }
}
