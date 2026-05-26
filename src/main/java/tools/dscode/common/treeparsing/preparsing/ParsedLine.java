package tools.dscode.common.treeparsing.preparsing;

import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.assertions.AssertionChain;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.GlobalConstants.META_TEXT_SEPARATOR;
import static tools.dscode.common.annotations.DefinitionFlag.BLOCK_CONDITIONAL;
import static tools.dscode.common.annotations.DefinitionFlag.IGNORE_CHILDREN_IF_FALSE;
import static tools.dscode.common.annotations.DefinitionFlag.NO_LOGGING;
import static tools.dscode.common.annotations.DefinitionFlag._NO_LOGGING;
import static tools.dscode.common.assertions.AssertionChain.isAssertionChainBorder;
import static tools.dscode.common.reporting.logging.LogForwarder.logDebug;
import static tools.dscode.common.reporting.logging.LogForwarder.setDefaultEntry;


public final class ParsedLine extends LineData {

    public static ParsedLine createParsedLine(StepExtension stepExtension) {
        return new ParsedLine(normalizeConditionalText(stepExtension));
    }


    private ParsedLine(String input) {
        super(input);
    }


    @Override
    public void runPhrases() {
        isBlockConditionalStep = getRunningStep().definitionFlags.contains(BLOCK_CONDITIONAL);
        if (stepExtension.overridePhrase != null) {
            stepExtension.overridePhrase.assertionChain.executeAssertionChain();
            return;
        }
        PhraseData phrase = phrases.get(startPhraseIndex);
        runPhraseFromLine(phrase.resolvePhrase());
    }

    public static String normalizeConditionalText(StepExtension stepExtension) {
        String input = stepExtension.getUnmodifiedText().trim();
        if (!input.matches("^(?:IF:|ELSE:|ELSE-IF:).*$")) {
            return input;
        }

        stepExtension.addDefinitionFlag(BLOCK_CONDITIONAL, NO_LOGGING, _NO_LOGGING, IGNORE_CHILDREN_IF_FALSE);

        String returnText = "";

        String metaText = input.endsWith(":") ? "" : META_TEXT_SEPARATOR + " BLOCK_CONDITIONAL " + META_TEXT_SEPARATOR;

        for (StringPair stringPair : splitKeepingTokens(input)) {
            String remainder = stringPair.value();
            if (!remainder.isBlank() && (stringPair.token.equals("ELSE:") || stringPair.token.equals("THEN:"))) {
                if (remainder.startsWith(",")) {
                    remainder = remainder.substring(1).trim();
                } else {
                    remainder = " run step `" + remainder + "`";
                }
            }

            returnText += " , " + metaText + " " + stringPair.normalizedToken() + " " + remainder;
        }
        if (input.endsWith(":") && !returnText.endsWith(":"))
            return returnText + " :";
        return returnText;
    }

    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("\\b(?:ELSE-IF:|ELSE:|IF:|THEN:)");


    public record StringPair(String token, String value) {

        public StringPair {
            token = token == null ? "" : token;
            value = value == null ? "" : value;
        }

        public String normalizedToken() {
            return token
                    .toLowerCase()
                    .replace(':', ' ')
                    .replace('-', ' ')
                    .trim();
        }
    }

    public static List<StringPair> splitKeepingTokens(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }

        List<StringPair> result = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(input);

        while (matcher.find()) {
            String token = matcher.group();

            int valueStart = matcher.end();

            int valueEnd;
            if (matcher.find()) {
                valueEnd = matcher.start();
                matcher.region(matcher.start(), input.length());
            } else {
                valueEnd = input.length();
            }

            String value = input.substring(valueStart, valueEnd).trim();

            result.add(new StringPair(token, value));
        }

        return result;
    }


    @Override
    public PhraseData runPhraseFromLine(PhraseData phrase) {
//        setDefaultEntry(getRunningStep().stepEntry);
        phrase.setOperationInheritanceIfNeeded();

        if (!phrase.getAssertion().isBlank() && phrase.assertionChainMembership == null && phrase.assertionChain == null) {
            phrase.assertionChain = new AssertionChain(phrase);
            PhraseData currentPhrase = phrase;
            while (true) {
                phrase.assertionChain.addAssertionPhrase(currentPhrase);
                currentPhrase = currentPhrase.getNextPhrase();
                if (isAssertionChainBorder(currentPhrase)) {
                    phrase.setNextPhrase(currentPhrase);
                    if (currentPhrase != null) {
                        currentPhrase.setPreviousPhrase(phrase);
                    }
                    break;
                }
            }
            phrase.termination = phrase.assertionChain.phraseChain.getLast().termination;

            if (phrase.untilPhrase && phrase.isContextTermination()) {
                if (phrase.termination.equals(':') || phrase.termination.equals('?')) {
                    phrase.parsedLine.inheritancePhrases.add(phrase);
                    return phrase;
                }
            }
        }


        getCurrentScenarioState().currentPhrase = (tools.dscode.common.treeparsing.parsedComponents.Phrase) phrase;

//        if (stepEntry != null) {
//            phrase.phraseEntry = getRunningStep().stepEntry.logWithType("PHRASE", phrase.toString()).tags("phrase").start();
//        }
        PhraseData nextResolvedPhrase = phrase.runPhrase();

        if(phrase.phraseEntry != null)
            phrase.phraseEntry.stop();

        getCurrentScenarioState().currentPhrase = null;

        if (phrase.assertionChainMembership != null) {
            phrase.assertionChainMembership.setPhraseIndex(phrase);
            if (phrase.isChainedAssertion)
                return phrase;
        }

        if (nextResolvedPhrase == null) {
            logDebug("Step completed: " + executedPhrases);
            return phrase;
        }


        if (phrase.branchedPhrases.isEmpty()) {
            runPhraseFromLine(nextResolvedPhrase);
        } else {
            for (PhraseData clone : phrase.branchedPhrases) {
                runPhraseFromLine(clone);
            }
        }
        return phrase;
    }


}
