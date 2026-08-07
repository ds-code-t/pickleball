package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import tools.dscode.common.seleniumextensions.ContextWrapper;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatchFactory;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static tools.dscode.common.treeparsing.parsedComponents.ElementType.TEXT_VALUE;

public class ElementMatching {

    public static List<ElementMatch> processElementMatches(
            PhraseData phraseData,
            List<ElementMatch> inputElementMatches,
            ElementMatcher... elementMatchers
    ) {
        List<ElementMatch> elementMatches =
                new ArrayList<>(inputElementMatches);
        List<ElementMatch> returnElementMatches =
                removeExcessPlaceHolders(
                        elementMatches,
                        elementMatchers.length
                );

        for (int i = 0; i < elementMatchers.length; i++) {
            ElementMatcher elementMatcher = elementMatchers[i];
            ElementMatch elementMatch = i < returnElementMatches.size()
                    ? returnElementMatches.get(i)
                    : null;
            if (elementMatch == null) {
                returnElementMatches.add(
                        createPlaceHolderElementMatch(
                                phraseData,
                                elementMatcher
                        )
                );
            } else if (elementMatch.isPlaceHolder()) {
                elementMatch.elementMatcher = elementMatcher;
            } else if (!elementMatcher.matches(
                    elementMatch.elementTypes
            )) {
                returnElementMatches.add(
                        i,
                        createPlaceHolderElementMatch(
                                phraseData,
                                elementMatcher
                        )
                );
            }
        }

        returnElementMatches.removeIf(
                element -> element.isPlaceHolder()
                        && element.elementMatcher == null
        );

        if (returnElementMatches.size() != elementMatchers.length) {
            if (elementMatchers.length == 1
                    && elementMatches.size() == 1) {
                ElementMatch firstElement =
                        elementMatches.getFirst();
                ElementMatch originalElement = firstElement;
                int index = phraseData.getElementMatches()
                        .indexOf(originalElement);

                if (firstElement.elementTypes.contains(TEXT_VALUE)
                        && !elementMatchers[0].matches(TEXT_VALUE)) {
                    PhraseData inheritance =
                            phraseData.operationInheritancePhrase;
                    if (inheritance != null
                            && inheritance.getElementMatches().size() == 1) {
                        ElementMatch inherited =
                                inheritance.getElementMatches().getFirst();
                        if (!inherited.elementTypes.contains(TEXT_VALUE)) {
                            firstElement.category = inherited.category;
                            firstElement.elementTypes.clear();
                            firstElement.elementTypes.addAll(
                                    inherited.elementTypes
                            );
                            firstElement = ElementMatchFactory.copy(
                                    phraseData,
                                    firstElement
                            );

                            if (firstElement.elementTypes.contains(
                                    ElementType.HTML_TYPE
                            )) {
                                firstElement.contextWrapper =
                                        new ContextWrapper(firstElement);
                            }
                            if (index != -1) {
                                phraseData.getElementMatches().set(
                                        index,
                                        firstElement
                                );
                            }
                            return new ArrayList<>(
                                    Collections.singleton(firstElement)
                            );
                        }
                    }
                }
            }

            throw new RuntimeException(
                    "Operation expected "
                            + elementMatchers.length
                            + " elements, but found "
                            + returnElementMatches.size()
                            + ". "
                            + returnElementMatches
            );
        }

        return returnElementMatches;
    }

    public static List<ElementMatch> removeExcessPlaceHolders(
            List<ElementMatch> elementMatches,
            int correctSize
    ) {
        List<ElementMatch> returnElementMatches =
                new ArrayList<>(elementMatches);
        while (returnElementMatches.size() > correctSize) {
            ElementMatch placeholder = returnElementMatches.stream()
                    .filter(element ->
                            element.isPlaceHolder()
                                    && element.elementMatcher == null
                    )
                    .findAny()
                    .orElse(null);
            if (placeholder == null) {
                break;
            }
            returnElementMatches.remove(placeholder);
        }
        return returnElementMatches;
    }

    public static ElementMatch createPlaceHolderElementMatch(
            PhraseData phraseData,
            ElementMatcher elementMatcher
    ) {
        PlaceHolderMatch placeHolder =
                new PlaceHolderMatch(phraseData);
        placeHolder.elementMatcher = elementMatcher;
        return placeHolder;
    }
}
