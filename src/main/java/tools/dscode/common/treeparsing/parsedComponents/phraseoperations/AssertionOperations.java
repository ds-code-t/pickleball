package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.assertions.ValueWrapperCompareReducer;
import tools.dscode.common.assertions.ValueWrapperComparisons;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.exceptions.SoftRuntimeException;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;
import static tools.dscode.common.reporting.logging.LogForwarder.logInfo;
import static tools.dscode.common.reporting.logging.LogForwarder.logWarn;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.RETURNS_VALUE;
import static tools.dscode.common.treeparsing.parsedComponents.phraseoperations.ElementMatching.processElementMatches;

public enum AssertionOperations implements OperationsInterface {


    EQUAL {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            ElementMatch firstElement = phraseData.getElementMatchBeforeOperation(RETURNS_VALUE);
            ElementMatch secondElement = phraseData.getElementMatchAfterOperation(RETURNS_VALUE);
            if (rejectNonNumeric(phraseData, firstElement, secondElement)) {
                return;
            }
            boolean qualified = numericQualifier(firstElement) || numericQualifier(secondElement);
            int repetition = phraseData.getRepetition();
            ValueWrapper margin = phraseData.getMargin();

            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.eval(
                        (left, right) -> qualified
                                ? ValueWrapperComparisons.numericEquals(left, right, margin)
                                : ValueWrapperComparisons.equals(left, right, margin),
                        firstElement.getComparisonValues(),
                        secondElement.getComparisonValues(),
                        getModeSet(phraseData)
                );
            });
        }
    },

    GREATER_THAN {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            ElementMatch firstElement = phraseData.getElementMatchBeforeOperation(RETURNS_VALUE);
            ElementMatch secondElement = phraseData.getElementMatchAfterOperation(RETURNS_VALUE);
            if (rejectNonNumeric(phraseData, firstElement, secondElement)) {
                return;
            }
            int repetition = phraseData.getRepetition();
            ValueWrapper margin = phraseData.getMargin();

            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.eval(
                        (left, right) -> ValueWrapperComparisons.numericGreaterThan(left, right, margin),
                        firstElement.getComparisonValues(),
                        secondElement.getComparisonValues(),
                        getModeSet(phraseData)
                );
            });

        }
    },

    LESS_THAN {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            ElementMatch firstElement = phraseData.getElementMatchBeforeOperation(RETURNS_VALUE);
            ElementMatch secondElement = phraseData.getElementMatchAfterOperation(RETURNS_VALUE);
            if (rejectNonNumeric(phraseData, firstElement, secondElement)) {
                return;
            }
            int repetition = phraseData.getRepetition();
            ValueWrapper margin = phraseData.getMargin();

            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.eval(
                        (left, right) -> ValueWrapperComparisons.numericLessThan(left, right, margin),
                        firstElement.getComparisonValues(),
                        secondElement.getComparisonValues(),
                        getModeSet(phraseData)
                );
            });

        }
    },
    GREATER_THAN_OR_EQUAL {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            ElementMatch firstElement = phraseData.getElementMatchBeforeOperation(RETURNS_VALUE);
            ElementMatch secondElement = phraseData.getElementMatchAfterOperation(RETURNS_VALUE);
            if (rejectNonNumeric(phraseData, firstElement, secondElement)) {
                return;
            }
            int repetition = phraseData.getRepetition();
            ValueWrapper margin = phraseData.getMargin();

            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.eval(
                        (left, right) -> ValueWrapperComparisons.numericGreaterThanOrEqualTo(left, right, margin),
                        firstElement.getComparisonValues(),
                        secondElement.getComparisonValues(),
                        getModeSet(phraseData)
                );
            });

        }
    },

    LESS_THAN_OR_EQUAL {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            ElementMatch firstElement = phraseData.getElementMatchBeforeOperation(RETURNS_VALUE);
            ElementMatch secondElement = phraseData.getElementMatchAfterOperation(RETURNS_VALUE);
            if (rejectNonNumeric(phraseData, firstElement, secondElement)) {
                return;
            }
            int repetition = phraseData.getRepetition();
            ValueWrapper margin = phraseData.getMargin();

            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.eval(
                        (left, right) -> ValueWrapperComparisons.numericLessThanOrEqualTo(left, right, margin),
                        firstElement.getComparisonValues(),
                        secondElement.getComparisonValues(),
                        getModeSet(phraseData)
                );
            });
        }
    },

    START_WITH {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            int repetition = phraseData.getRepetition();

            ElementMatch firstElement = phraseData.getElementMatchBeforeOperation(RETURNS_VALUE);
            ElementMatch secondElement = phraseData.getElementMatchAfterOperation(RETURNS_VALUE);


            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.eval(
                        ValueWrapperComparisons::startsWith,
                        firstElement.getValues(),
                        secondElement.getValues(),
                        getModeSet(phraseData)
                );
            });
        }
    },

    END_WITH {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            int repetition = phraseData.getRepetition();

            ElementMatch firstElement = phraseData.getElementMatchBeforeOperation(RETURNS_VALUE);
            ElementMatch secondElement = phraseData.getElementMatchAfterOperation(RETURNS_VALUE);

            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.eval(
                        ValueWrapperComparisons::endsWith,
                        firstElement.getValues(),
                        secondElement.getValues(),
                        getModeSet(phraseData)
                );
            });
        }
    },
    MATCH {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            int repetition = phraseData.getRepetition();

            ElementMatch firstElement = phraseData.getElementMatchBeforeOperation(RETURNS_VALUE);
            ElementMatch secondElement = phraseData.getElementMatchAfterOperation(RETURNS_VALUE);

            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.eval(
                        ValueWrapperComparisons::matchesRegex,
                        firstElement.getValues(),
                        secondElement.getValues(),
                        getModeSet(phraseData)
                );
            });
        }
    },
    START {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            int repetition = phraseData.getRepetition();

            ElementMatch firstElement = phraseData.getElementMatchBeforeOperation(RETURNS_VALUE);
            ElementMatch secondElement = phraseData.getElementMatchAfterOperation(RETURNS_VALUE);

            List<ValueWrapper> secondVals = secondElement.getValues().stream().map(val ->
                    createLiteralRegexWrapper(val, name(), "", "(?s).*")
            ).toList();


            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.eval(
                        ValueWrapperComparisons::matchesRegex,
                        firstElement.getValues(),
                        secondVals,
                        getModeSet(phraseData)
                );
            });
        }
    },

    END {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            int repetition = phraseData.getRepetition();

            ElementMatch firstElement = phraseData.getElementMatchBeforeOperation(RETURNS_VALUE);
            ElementMatch secondElement = phraseData.getElementMatchAfterOperation(RETURNS_VALUE);

            List<ValueWrapper> secondVals = secondElement.getValues().stream().map(val ->
                    createLiteralRegexWrapper(val, name(), "(?s).*", "")
            ).toList();


            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.eval(
                        ValueWrapperComparisons::matchesRegex,
                        firstElement.getValues(),
                        secondVals,
                        getModeSet(phraseData)
                );
            });
        }
    },

    CONTAIN {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            int repetition = phraseData.getRepetition();

            ElementMatch firstElement = phraseData.getElementMatchBeforeOperation(RETURNS_VALUE);
            ElementMatch secondElement = phraseData.getElementMatchAfterOperation(RETURNS_VALUE);

            List<ValueWrapper> secondVals = secondElement.getValues().stream().map(val ->
                    createLiteralRegexWrapper(val, name(), "(?s).*", "(?s).*")
            ).toList();


            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.eval(
                        ValueWrapperComparisons::matchesRegex,
                        firstElement.getValues(),
                        secondVals,
                        getModeSet(phraseData)
                );
            });
        }
    },

    VALUE {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            int repetition = phraseData.getRepetition();

            List<ElementMatch> preceding = phraseData.getElementMatchesPrecedingOperation(RETURNS_VALUE);
            // A reference such as <A> is resolved to a bare word before parsing, so
            // "has value" has no element. The word itself is the subject.
            List<ValueWrapper> values = preceding.isEmpty()
                    ? List.of(createValueWrapper(literalValueSubject(phraseData)))
                    : preceding.getFirst().getValues();

            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.evalValues(
                        ValueWrapper::hasResolvedValue,
                        values,
                        getModeSet(phraseData)
                );
            });
        }
    },
    BLANK {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            int repetition = phraseData.getRepetition();

            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesPrecedingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(RETURNS_VALUE)
            );
            ElementMatch firstElement = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.evalValues(
                        ValueWrapper::isBlank,
                        firstElement.getValues(),
                        getModeSet(phraseData)
                );
            });
        }
    },
    DISPLAYED {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            int repetition = phraseData.getRepetition();

            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesPrecedingOperation(),
                    new ElementMatcher()
                            .mustMatchAtLeastOne(ElementType.HTML_ELEMENT, ElementType.ALERT, ElementType.BROWSER_WINDOW)
            );

            Set<ValueWrapperCompareReducer.Mode> modeSet = getModeSet(phraseData);
            ElementMatch firstElement = phraseData.resultElements.getFirst();
            if (firstElement.elementTypes.contains(ElementType.ALERT) || firstElement.elementTypes.contains(ElementType.BROWSER_WINDOW)) {
                phraseData.result = Attempt.run(repetition, 500, () ->
                        (modeSet.contains(ValueWrapperCompareReducer.Mode.NOT) ^ !firstElement.getValues().isEmpty())
                );
                return;
            }

            if (firstElement.getElementWrappers().isEmpty()) {
                phraseData.result = new Attempt.Result(getModeSet(phraseData).contains(ValueWrapperCompareReducer.Mode.NOT), null);
                return;
            }

            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.evalElements(
                        ElementWrapper::isDisplayed,
                        firstElement.getElementWrappers(),
                        modeSet
                );
            });


        }
    },

    ENABLED {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            int repetition = phraseData.getRepetition();

            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesPrecedingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch firstElement = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.evalElements(
                        ElementWrapper::isEnabled,
                        firstElement.getElementWrappers(),
                        getModeSet(phraseData)
                );
            });
        }
    },
    EXPANDED {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            int repetition = phraseData.getRepetition();

            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesPrecedingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch firstElement = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.evalElements(
                        ElementWrapper::isExpanded,
                        firstElement.getElementWrappers(),
                        getModeSet(phraseData)
                );
            });
        }
    },

    COLLAPSED {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            int repetition = phraseData.getRepetition();

            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesPrecedingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch firstElement = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.evalElements(
                        ElementWrapper::isCollapsed,
                        firstElement.getElementWrappers(),
                        getModeSet(phraseData)
                );
            });
        }
    },

    REQUIRED {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            int repetition = phraseData.getRepetition();

            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesPrecedingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch firstElement = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.evalElements(
                        ElementWrapper::isRequired,
                        firstElement.getElementWrappers(),
                        getModeSet(phraseData)
                );
            });
        }
    },


    ON {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            int repetition = phraseData.getRepetition();

            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatches(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch firstElement = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.evalElements(
                        ElementWrapper::isOn,
                        firstElement.getElementWrappers(),
                        getModeSet(phraseData)
                );
            });
        }
    },

    OFF {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            int repetition = phraseData.getRepetition();

            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatches(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch firstElement = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.evalElements(
                        ElementWrapper::isOff,
                        firstElement.getElementWrappers(),
                        getModeSet(phraseData)
                );
            });
        }
    },

    TRUE {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            int repetition = phraseData.getRepetition();

            List<ValueWrapper> values;
            if (phraseData.getElementMatches().isEmpty() && phraseData.booleanValues != null && !phraseData.booleanValues.isEmpty()) {
                values = phraseData.booleanValues;
            } else {
                phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatches(),
                        new ElementMatcher()
                                .mustMatchAll(RETURNS_VALUE)
                );
                ElementMatch firstElement = phraseData.resultElements.getFirst();
                values = firstElement.getValues();
            }
            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.evalValues(
                        ValueWrapper::isTruthy,
                        values,
                        getModeSet(phraseData)
                );
            });
        }
    },

    FALSE {
        @Override
        public void execute(PhraseData phraseData) {
            logInfo(phraseData + " : Executing Assertion " + this.name());
            int repetition = phraseData.getRepetition();

            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatches(),
                    new ElementMatcher()
                            .mustMatchAll(RETURNS_VALUE)
            );

            ElementMatch firstElement = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(repetition, 500, () -> {
                return ValueWrapperCompareReducer.evalValues(
                        ValueWrapper::isFalsy,
                        firstElement.getValues(),
                        getModeSet(phraseData)
                );
            });
        }
    };


    private static String literalValueSubject(PhraseData phraseData) {
        String body = phraseData.body == null ? "" : phraseData.body.trim();
        if (body.isEmpty()) {
            String resolved = phraseData.getResolvedText();
            body = resolved == null ? "" : resolved.trim();
        }
        return body
                .replaceFirst("(?i)\\b(?:has|is)\\s+(?:no\\s+)?values?\\s*$", "")
                .replaceFirst("(?i)^(?:if|else\\s+if|else|until|ensure|verify|the)\\s+", "")
                .trim();
    }

    private static ValueWrapper createLiteralRegexWrapper(ValueWrapper val, String assertion, String prefix, String suffix) {
        if (val == null) {
            throw new IllegalArgumentException(assertion + " assertion cannot use a null ValueWrapper");
        }
        if (val.getValue() == null) {
            throw new IllegalArgumentException(assertion + " assertion cannot use a null expected value");
        }

        return createValueWrapper(
                prefix + Pattern.quote(String.valueOf(val.getValue())) + suffix,
                val.type
        );
    }


    private static boolean rejectNonNumeric(PhraseData phraseData, ElementMatch first, ElementMatch second) {
        if (!numericQualifier(first) && !numericQualifier(second)) {
            return false;
        }
        String problem = qualifiedProblem(first, second);
        if (problem == null) {
            return false;
        }
        if (conditionalPhrase(phraseData)) {
            logWarn(problem);
            phraseData.result = new Attempt.Result(Boolean.FALSE, null);
            return true;
        }
        if (softPhrase(phraseData)) {
            throw new SoftRuntimeException(problem);
        }
        throw new IllegalArgumentException(problem);
    }

    private static boolean numericQualifier(ElementMatch element) {
        return element != null
                && element.elementTypes != null
                && element.elementTypes.contains(ElementType.NUMERIC_VALUE);
    }

    private static boolean integerQualifier(ElementMatch element) {
        if (!numericQualifier(element) || element.category == null) {
            return false;
        }
        return element.category.trim().equalsIgnoreCase("integer");
    }

    private static String qualifiedProblem(ElementMatch first, ElementMatch second) {
        boolean integerOnly = integerQualifier(first) || integerQualifier(second);
        String problem = problemOn(first, integerOnly);
        return problem != null ? problem : problemOn(second, integerOnly);
    }

    private static String problemOn(ElementMatch element, boolean integerOnly) {
        if (element == null) {
            return qualifierMessage(null, integerOnly);
        }
        List<ValueWrapper> values = element.getComparisonValues();
        if (values == null || values.isEmpty()) {
            return qualifierMessage(null, integerOnly);
        }
        for (ValueWrapper value : values) {
            if (value == null || !value.isQualifiedNumeric(integerOnly)) {
                return qualifierMessage(value, integerOnly);
            }
        }
        return null;
    }

    private static String qualifierMessage(ValueWrapper value, boolean integerOnly) {
        String shown = value == null || value.asNormalizedText() == null ? "" : value.asNormalizedText();
        if (integerOnly) {
            return "integer qualifier: '" + shown + "' is not an integer";
        }
        return "number qualifier: '" + shown + "' is not a number";
    }

    private static boolean conditionalPhrase(PhraseData phrase) {
        if (phrase.untilPhrase) {
            return true;
        }
        String type = phrase.getAssertionType();
        if (type != null && type.equalsIgnoreCase("conditional")) {
            return true;
        }
        String conditional = phrase.getConditional() == null ? "" : phrase.getConditional().trim().toLowerCase();
        if (conditional.equals("if") || conditional.equals("until") || conditional.startsWith("else")) {
            return true;
        }
        return phrase.assertionChainMembership != null && phrase.assertionChainMembership.ownerIsConditional();
    }

    private static boolean softPhrase(PhraseData phrase) {
        if (phrase.getAssertionType() != null && phrase.getAssertionType().equalsIgnoreCase("verify")) {
            return true;
        }
        return phrase.assertionChainMembership != null
                && "verify".equalsIgnoreCase(phrase.assertionChainMembership.ownerAssertionType());
    }

    public static AssertionOperations

    fromString(String input) {
        input = input.replaceFirst("un|non-?", "");
        return OperationsInterface.requireOperationEnum(AssertionOperations.class, input);
    }


    public Set<ValueWrapperCompareReducer.Mode> getModeSet(PhraseData phraseData) {
        Set<ValueWrapperCompareReducer.Mode> modeSet = new HashSet<>();
        if (phraseData.resultElements.stream().anyMatch(e -> e.selectionType.equals("any")))
            modeSet.add(ValueWrapperCompareReducer.Mode.ANY);
        if (phraseData.resultElements.stream().anyMatch(e -> e.selectionType.equals("none")))
            modeSet.add(ValueWrapperCompareReducer.Mode.NONE);
        if (phraseData.hasNo)
            modeSet.add(ValueWrapperCompareReducer.Mode.NOT);
        if (phraseData.getAssertion().startsWith("un") || phraseData.getAssertion().startsWith("disable") || phraseData.getAssertion().startsWith("non"))
            modeSet.add(ValueWrapperCompareReducer.Mode.UN);
        return modeSet;
    }


}
