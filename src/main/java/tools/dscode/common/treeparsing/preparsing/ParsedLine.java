package tools.dscode.common.treeparsing.preparsing;

import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.assertions.AssertionChain;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.annotations.DefinitionFlag.IGNORE_CHILDREN_IF_FALSE;
import static tools.dscode.common.annotations.DefinitionFlag.NO_LOGGING;
import static tools.dscode.common.annotations.DefinitionFlag._NO_LOGGING;
import static tools.dscode.common.assertions.AssertionChain.isAssertionChainBorder;
import static tools.dscode.common.reporting.logging.LogForwarder.stepInfo;
import static tools.dscode.common.treeparsing.parsedComponents.PassedData.isNewBoundary;


public final class ParsedLine extends LineData {

    public static ParsedLine createParsedLine(StepExtension stepExtension) {
        return new ParsedLine(normalizeConditionalText(stepExtension));
    }


    private ParsedLine(String input) {
        super(input);
    }

    @Override
    public void runPhrases() {
        if (stepExtension.overridePhrase != null) {
            runPhraseFromLine(stepExtension.overridePhrase);
            return;
        }
        PhraseData phrase = phrases.get(startPhraseIndex);
        runPhraseFromLine(phrase.resolvePhrase());
    }

    public static String normalizeConditionalText(StepExtension stepExtension) {
        String input = stepExtension.getUnmodifiedText();
        if (input == null || !input.matches("^(?:IF:|ELSE:|ELSE-IF:).*$")) {
            return input;
        }

        stepExtension.addDefinitionFlag(NO_LOGGING, _NO_LOGGING, IGNORE_CHILDREN_IF_FALSE);

        return input
                .replace("ELSE-IF:", " , else if ")
                .replace("ELSE:", " , else ")
                .replace("IF:", " , if ")
                .replace("THEN:", " , ");
    }


    @Override
    public PhraseData runPhraseFromLine(PhraseData phrase) {
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


        StepExtension currentStep = getRunningStep();
        Entry stepEntry = currentStep == null ? null : currentStep.stepEntry;
        getCurrentScenarioState().currentPhrase = (tools.dscode.common.treeparsing.parsedComponents.Phrase) phrase;

        if (stepEntry != null) {
            phrase.phraseEntry = getRunningStep().stepEntry.logWithType("PHRASE", phrase.toString()).start()
                    .defaultDescendantFields(
                            "html.fontSize:12px",
                            "html.headerFontSize:13px",
                            "html.borderColor:#e5e7eb",
                            "html.borderWidth:1px"
                    )
                    .field(
                            "html.fontSize:12px",
                            "html.headerFontSize:14px",
                            "html.borderColor:#10b981",
                            "html.borderWidth:1px",
                            "html.headerBackgroundColor:#ecfdf5",
                            "html.headerColor:#064e3b"
                    );
        }
        PhraseData nextResolvedPhrase = phrase.runPhrase();

        if (phrase.phraseEntry != null)
            phrase.phraseEntry.stop();

        getCurrentScenarioState().currentPhrase = null;

        if (phrase.assertionChainMembership != null) {
            phrase.assertionChainMembership.setPhraseIndex(phrase);
            if (phrase.isChainedAssertion)
                return phrase;
        }

        if (nextResolvedPhrase == null) {
            stepInfo("Step completed: " + executedPhrases);
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
