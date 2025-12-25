package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import org.apache.poi.xssf.usermodel.XSSFPivotTable;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.List;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.treeparsing.parsedComponents.phraseoperations.ElementMatching.processElementMatches;
import static tools.dscode.common.util.DebugUtils.printDebug;

public enum ActionOperations implements OperationsInterface {

    SAVE {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing SAVE");
            List<ElementMatch> list = processElementMatches(phraseData, phraseData.elementMatchesFollowingOperation,
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE, ElementType.FOLLOWING_OPERATION, ElementType.FIRST_ELEMENT),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.KEY_VALUE, ElementType.FOLLOWING_OPERATION)
                    );
            System.out.println("@@1");


            ElementMatch valueElement = list.getFirst();
            System.out.println("@@valueElement: " + valueElement);
            ElementMatch keyElement = list.get(1);
            System.out.println("@@keyElement: " + keyElement);
            String keyName = keyElement == null ? "saved" : keyElement.getValue().toString();
            phraseData.result = Attempt.run(() -> {
                for (ValueWrapper valueWrapper : valueElement.getValues()) {
                    System.out.println("Action: saving '" + valueWrapper + "' to key: " + keyName);
                    getRunningStep().getStepParsingMap().put(keyName, valueWrapper.getValue());
                }
            });
        }
    },

    WAIT {
        @Override
        public void execute(PhraseData phraseData) {
        };
    },

    SELECT{
        @Override
        public void execute(PhraseData phraseData) {
        }
    },

    CLICK{
        @Override
        public void execute(PhraseData phraseData) {
        }
    },

    DOUBLE_CLICK{
        @Override
        public void execute(PhraseData phraseData) {
        }
    },

    RIGHT_CLICK{
        @Override
        public void execute(PhraseData phraseData) {
        }
    },

    ENTER{
        @Override
        public void execute(PhraseData phraseData) {
        }
    },
    OVERWRITE{
        @Override
        public void execute(PhraseData phraseData) {
        }
    },
    SCROLL{
        @Override
        public void execute(PhraseData phraseData) {
        }
    };


    public static ActionOperations fromString(String input) {
        return OperationsInterface.requireOperationEnum(ActionOperations.class, input);
    }



}
