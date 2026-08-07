package tools.dscode.common.dataelements;

import org.junit.jupiter.api.Test;
import tools.dscode.common.dataoperations.TextOp;
import tools.dscode.common.dataoperations.TextPredicateMatcher;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;
import static tools.dscode.common.dataelements.DataCardinality.OPTIONAL_MANY;
import static tools.dscode.common.dataelements.DataCardinality.REQUIRED_MANY;
import static tools.dscode.common.dataelements.DataCardinality.REQUIRED_ONE;
import static tools.dscode.common.dataelements.DataElementForm.PLURAL;
import static tools.dscode.common.dataelements.DataElementForm.SINGULAR;
import static tools.dscode.common.dataelements.DataElementKind.DATA_ENTRY;
import static tools.dscode.common.dataelements.DataElementKind.DATA_LIST;
import static tools.dscode.common.dataelements.DataElementKind.DATA_ROW;
import static tools.dscode.common.dataelements.DataElementKind.LIST;
import static tools.dscode.common.dataelements.DataElementKind.MULTIMAP;

public class DataElementPhaseOneChecks {

    @Test
    void registryUsesExplicitSingularPluralAndAliasForms() {
        DataElementRegistration entries =
                DataElementRegistry.require("Data Entries");
        DataElementRegistration multimaps =
                DataElementRegistry.require("Multimaps");
        DataElementRegistration docString =
                DataElementRegistry.require("Doc String");

        assertEquals(DATA_ENTRY, entries.kind());
        assertEquals(PLURAL, entries.form());
        assertEquals(MULTIMAP, multimaps.kind());
        assertEquals(PLURAL, multimaps.form());
        assertTrue(docString.alias());
        assertEquals("Data Doc String", docString.canonicalName());
        assertFalse(DataElementRegistry.contains("Statuses"));
    }

    @Test
    void registryKeepsDataListAndJavaListDistinct() {
        assertEquals(
                DATA_LIST,
                DataElementRegistry.require("Data Lists").kind()
        );
        assertEquals(
                LIST,
                DataElementRegistry.require("Lists").kind()
        );
        assertTrue(DataElementRegistry.CUCUMBER_DATA_ELEMENTS
                .contains("Data Column Lists"));
        assertTrue(DataElementRegistry.JAVA_DATA_ELEMENTS
                .contains("Multimaps"));
        assertTrue(DataElementRegistry.FORMAT_DATA_ELEMENTS
                .contains("Data"));
    }

    @Test
    void elementTypeUsesTheExplicitDataElementRegistry() {
        assertTrue(ElementType.fromString("Data Entries")
                .contains(ElementType.DATA_TYPE));
        assertTrue(ElementType.fromString("Multimaps")
                .contains(ElementType.DATA_TYPE));
        assertTrue(ElementType.fromString("Doc Strings")
                .contains(ElementType.DATA_TYPE));
    }

    @Test
    void textMatcherPreservesQuoteModes() {
        assertFalse(TextPredicateMatcher.matches(
                "Alpha",
                new TextOp(
                        createValueWrapper("\"alpha\""),
                        ExecutionDictionary.Op.EQUALS
                )
        ));
        assertTrue(TextPredicateMatcher.matches(
                "Alpha",
                new TextOp(
                        createValueWrapper("'alpha'"),
                        ExecutionDictionary.Op.EQUALS
                )
        ));
        assertTrue(TextPredicateMatcher.matches(
                " Alpha ",
                new TextOp(
                        createValueWrapper("` Alpha `"),
                        ExecutionDictionary.Op.EQUALS
                )
        ));
        assertFalse(TextPredicateMatcher.matches(
                " Alpha ",
                new TextOp(
                        createValueWrapper("`Alpha`"),
                        ExecutionDictionary.Op.EQUALS
                )
        ));
        assertTrue(TextPredicateMatcher.matches(
                "A-A",
                new TextOp(
                        createValueWrapper("~a-a~"),
                        ExecutionDictionary.Op.EQUALS
                )
        ));
    }

    @Test
    void textMatcherSupportsTextRegexAndNumericOperations() {
        assertTrue(TextPredicateMatcher.matches(
                "Alpha Beta",
                TextOp.of("Alpha", ExecutionDictionary.Op.STARTS_WITH)
        ));
        assertTrue(TextPredicateMatcher.matches(
                "Alpha Beta",
                TextOp.of("Beta", ExecutionDictionary.Op.ENDS_WITH)
        ));
        assertTrue(TextPredicateMatcher.matches(
                "Alpha Beta",
                TextOp.of("ha Be", ExecutionDictionary.Op.CONTAINS)
        ));
        assertTrue(TextPredicateMatcher.matches(
                "ABC-123",
                TextOp.of("[A-Z]+-\\d+", ExecutionDictionary.Op.MATCHES)
        ));
        assertTrue(TextPredicateMatcher.matches(
                "value 12",
                TextOp.of(10, ExecutionDictionary.Op.GT)
        ));
    }

    @Test
    void queryDefaultsFollowSingularAndPluralContracts() {
        DataQuery singular = DataQuery.builder(DATA_ROW, SINGULAR).build();
        DataQuery plural = DataQuery.builder(DATA_ROW, PLURAL).build();

        assertEquals(REQUIRED_ONE, singular.cardinality());
        assertEquals(OPTIONAL_MANY, plural.cardinality());
        assertEquals(DataResultUse.TERMINAL, singular.resultUse());
    }

    @Test
    void queryRepresentsEveryAnyAndStrideSeparately() {
        DataQuery everyThird = DataQuery.builder(DATA_ROW, SINGULAR)
                .every()
                .stride(3)
                .resultUse(DataResultUse.ITERATION)
                .build();
        DataQuery anyThird = DataQuery.builder(DATA_ROW, SINGULAR)
                .any()
                .stride(3)
                .build();

        assertEquals(REQUIRED_MANY, everyThird.cardinality());
        assertEquals(3, everyThird.stride());
        assertEquals(DataResultUse.ITERATION, everyThird.resultUse());
        assertEquals(OPTIONAL_MANY, anyThird.cardinality());
    }

    @Test
    void queryRejectsContradictorySelectionCombinations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DataQuery.builder(DATA_ROW, PLURAL)
                        .ordinal(2)
                        .build()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> DataQuery.builder(DATA_ROW, SINGULAR)
                        .every()
                        .first()
                        .every()
                        .build()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> DataQuery.builder(DATA_ROW, SINGULAR)
                        .stride(3)
                        .build()
        );
    }
}
