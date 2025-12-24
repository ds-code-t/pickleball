package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.OperationMeta;

import java.util.EnumSet;

public enum ActionOperations {

    SAVE(
            new OperationMeta(
                    EnumSet.of(ElementType.FIRST_ELEMENT, ElementType.FOLLOWING_OPERATION),
                    EnumSet.of(ElementType.RETURNS_VALUE)
            ),
            new OperationMeta(
                    EnumSet.of(ElementType.SECOND_ELEMENT, ElementType.FOLLOWING_OPERATION),
                    EnumSet.of(ElementType.KEY_VALUE)
            )
    ),

    WAIT(
            new OperationMeta(
                    EnumSet.of(ElementType.FIRST_ELEMENT, ElementType.FOLLOWING_OPERATION),
                    EnumSet.of(ElementType.TIME_VALUE)
            ),
            null
    ),


    SELECT(
            new OperationMeta(
                    EnumSet.of(ElementType.FIRST_ELEMENT, ElementType.FOLLOWING_OPERATION),
                    EnumSet.of(ElementType.HTML_ELEMENT)
            ),
            new OperationMeta(
                    EnumSet.of(ElementType.SECOND_ELEMENT, ElementType.FOLLOWING_OPERATION),
                    EnumSet.of(ElementType.VALUE_TYPE)
            )
    ),
    CLICK(
            new OperationMeta(
                    EnumSet.of(ElementType.FIRST_ELEMENT, ElementType.FOLLOWING_OPERATION),
                    EnumSet.of(ElementType.HTML_ELEMENT)
            ),
            null
    ),
    DOUBLE_CLICK(
            new OperationMeta(
                    EnumSet.of(ElementType.FIRST_ELEMENT, ElementType.FOLLOWING_OPERATION),
                    EnumSet.of(ElementType.HTML_ELEMENT)
            ),
            null
    ),
    RIGHT_CLICK(
            new OperationMeta(
                    EnumSet.of(ElementType.FIRST_ELEMENT, ElementType.FOLLOWING_OPERATION),
                    EnumSet.of(ElementType.HTML_ELEMENT)
            ),
            null
    ),
    ENTER(
            new OperationMeta(
                    EnumSet.of(ElementType.FIRST_ELEMENT, ElementType.FOLLOWING_OPERATION),
                    EnumSet.of(ElementType.VALUE_TYPE)
            ),
            new OperationMeta(
                    EnumSet.of(ElementType.SECOND_ELEMENT, ElementType.FOLLOWING_OPERATION),
                    EnumSet.of(ElementType.HTML_ELEMENT)
            )
    ),
    OVERWRITE(
            new OperationMeta(
                    EnumSet.of(ElementType.FIRST_ELEMENT, ElementType.FOLLOWING_OPERATION),
                    EnumSet.of(ElementType.HTML_ELEMENT)
            ),
            new OperationMeta(
                    EnumSet.of(ElementType.SECOND_ELEMENT, ElementType.FOLLOWING_OPERATION),
                    EnumSet.of(ElementType.VALUE_TYPE)
            )
    ),
    SCROLL(
            new OperationMeta(
                    EnumSet.of(ElementType.FIRST_ELEMENT, ElementType.FOLLOWING_OPERATION),
                    EnumSet.of(ElementType.HTML_ELEMENT)
            ),
            null
    );


    private final OperationMeta primaryMeta;
    private final OperationMeta secondaryMeta;

    ActionOperations(OperationMeta primaryMeta, OperationMeta secondaryMeta) {
        this.primaryMeta = primaryMeta;
        this.secondaryMeta = secondaryMeta;
    }

    public OperationMeta primaryMeta() {
        return primaryMeta;
    }

    public OperationMeta secondaryMeta() {
        return secondaryMeta;
    }
}
