package io.cucumber.core.gherkin.messages;

import io.cucumber.core.gherkin.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Utility methods for working with {@link GherkinMessagesPickle} steps.
 */
final class GherkinMessagesPickleStepMatcher {

    private static final String ANY_TEXT = "[\\s\\S]*";

    private GherkinMessagesPickleStepMatcher() {
        // Utility class
    }

    /**
     * Returns the pickle steps whose text matches the supplied regular expression.
     *
     * <p>Steps are checked in their original pickle order, and matching steps are
     * returned in that same order.</p>
     *
     * @param pickle the pickle to search
     * @param regexExpression the regular expression to match against each step text
     * @return the matching steps, in pickle step order
     */
    static List<Step> findStepsMatchingText(
            GherkinMessagesPickle pickle,
            String regexExpression
    ) {
        Objects.requireNonNull(pickle, "pickle");
        Objects.requireNonNull(regexExpression, "regexExpression");

        Pattern pattern = Pattern.compile(regexExpression);
        List<Step> matchingSteps = new ArrayList<>();

        for (Step step : pickle.getSteps()) {
            if (pattern.matcher(step.getText()).matches()) {
                matchingSteps.add(step);
            }
        }

        return matchingSteps;
    }

    /**
     * Returns the pickle steps whose text exactly equals the supplied literal text.
     *
     * @param pickle the pickle to search
     * @param literalText the literal step text to match
     * @return the matching steps, in pickle step order
     */
    static List<Step> findStepsWithExactText(
            GherkinMessagesPickle pickle,
            String literalText
    ) {
        return findStepsMatchingText(
                pickle,
                Pattern.quote(Objects.requireNonNull(literalText, "literalText"))
        );
    }

    /**
     * Returns the pickle steps whose text starts with the supplied literal text.
     *
     * @param pickle the pickle to search
     * @param literalText the literal text the step text must start with
     * @return the matching steps, in pickle step order
     */
    static List<Step> findStepsStartingWithText(
            GherkinMessagesPickle pickle,
            String literalText
    ) {
        return findStepsMatchingText(
                pickle,
                Pattern.quote(Objects.requireNonNull(literalText, "literalText")) + ANY_TEXT
        );
    }

    /**
     * Returns the pickle steps whose text ends with the supplied literal text.
     *
     * @param pickle the pickle to search
     * @param literalText the literal text the step text must end with
     * @return the matching steps, in pickle step order
     */
    static List<Step> findStepsEndingWithText(
            GherkinMessagesPickle pickle,
            String literalText
    ) {
        return findStepsMatchingText(
                pickle,
                ANY_TEXT + Pattern.quote(Objects.requireNonNull(literalText, "literalText"))
        );
    }

    /**
     * Returns the pickle steps whose text contains the supplied literal text.
     *
     * @param pickle the pickle to search
     * @param literalText the literal text the step text must contain
     * @return the matching steps, in pickle step order
     */
    static List<Step> findStepsContainingText(
            GherkinMessagesPickle pickle,
            String literalText
    ) {
        return findStepsMatchingText(
                pickle,
                ANY_TEXT + Pattern.quote(Objects.requireNonNull(literalText, "literalText")) + ANY_TEXT
        );
    }

}