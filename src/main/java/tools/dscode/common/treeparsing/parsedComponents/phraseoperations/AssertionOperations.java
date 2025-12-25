package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.EnumSet;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.treeparsing.parsedComponents.phraseoperations.ElementMatching.processElementMatches;
import static tools.dscode.common.util.DebugUtils.printDebug;

public enum AssertionOperations  implements OperationsInterface  {

    EQUAL{    @Override
    public void execute(PhraseData phraseData) {
        List<ElementMatch> list = processElementMatches(phraseData, phraseData.elementMatchesFollowingOperation,
                new ElementMatcher()
                        .mustMatchAll(ElementType.RETURNS_VALUE, ElementType.FOLLOWING_OPERATION, ElementType.FIRST_ELEMENT),
                new ElementMatcher()
                        .mustMatchAll(ElementType.KEY_VALUE, ElementType.FOLLOWING_OPERATION)
        );

        ElementMatch valueElement = list.getFirst();
        ElementMatch keyElement = list.get(1);
        String keyName = keyElement == null ? "saved" : keyElement.getValue().toString();
        phraseData.result = Attempt.run(() -> {
            for (ValueWrapper valueWrapper : valueElement.getValues()) {
                printDebug("Action: saving '" + valueWrapper + "' to key: " + keyName);
                getRunningStep().getStepParsingMap().put(keyName, valueWrapper.getValue());
            }
        });
    }};

//    START_WITH,
//
//    END_WITH,
//
//    MATCH,
//
//    HAS_VALUE,
//
//    IS_BLANK,
//
//    DISPLAYED,
//
//    ENABLED,
//
//    SELECTED,
//
//    IS_TRUE,
//
//    IS_FALSE;


    public static AssertionOperations fromString(String input) {
        return OperationsInterface.requireOperationEnum(AssertionOperations.class, input);
    }



}
