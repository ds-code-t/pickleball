package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.OperationMeta;

import java.util.EnumSet;

public enum AssertionOperations {

    EQUAL(
            new OperationMeta(
                    EnumSet.of(ElementType.PRECEDING_OPERATION),
                    EnumSet.of(ElementType.RETURNS_VALUE)
            ),
            new OperationMeta(
                    EnumSet.of(ElementType.FOLLOWING_OPERATION),
                    EnumSet.of(ElementType.RETURNS_VALUE)
            )
    ),

    START_WITH(
            new OperationMeta(
                    EnumSet.of(ElementType.PRECEDING_OPERATION),
                    EnumSet.of(ElementType.RETURNS_VALUE)
            ),
            new OperationMeta(
                    EnumSet.of(ElementType.FOLLOWING_OPERATION),
                    EnumSet.of(ElementType.RETURNS_VALUE)
            )
    ),

    END_WITH(
            new OperationMeta(
                    EnumSet.of(ElementType.PRECEDING_OPERATION),
                    EnumSet.of(ElementType.RETURNS_VALUE)
            ),
            new OperationMeta(
                    EnumSet.of(ElementType.FOLLOWING_OPERATION),
                    EnumSet.of(ElementType.RETURNS_VALUE)
            )
    ),

    MATCH(
            new OperationMeta(
                    EnumSet.of(ElementType.PRECEDING_OPERATION),
                    EnumSet.of(ElementType.RETURNS_VALUE)
            ),
            new OperationMeta(
                    EnumSet.of(ElementType.FOLLOWING_OPERATION),
                    EnumSet.of(ElementType.VALUE_TYPE)
            )
    ),

    HAS_VALUE(
            new OperationMeta(
                    EnumSet.noneOf(ElementType.class),
                    EnumSet.of(ElementType.RETURNS_VALUE)
            ),
            null
    ),

    IS_BLANK(
            null,
            null
    ),

    DISPLAYED(
            new OperationMeta(
                    EnumSet.noneOf(ElementType.class),
                    EnumSet.of(ElementType.HTML_ELEMENT)
            ),
            null
    ),

    ENABLED(
            new OperationMeta(
                    EnumSet.noneOf(ElementType.class),
                    EnumSet.of(ElementType.HTML_ELEMENT)
            ),
            null
    ),

    SELECTED(
            new OperationMeta(
                    EnumSet.noneOf(ElementType.class),
                    EnumSet.of(ElementType.HTML_ELEMENT)
            ),
            null
    ),

    IS_TRUE(
            null,
            null
    ),

    IS_FALSE(
            null,
            null
    );

    private final OperationMeta primaryMeta;
    private final OperationMeta secondaryMeta;

    AssertionOperations(OperationMeta primaryMeta, OperationMeta secondaryMeta) {
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
