package tools.dscode.common.treeparsing.parsedComponents;


import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.reporting.logging.Level;
import tools.dscode.common.seleniumextensions.ContextWrapper;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.preparsing.LineData;

import java.util.ArrayList;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.annotations.DefinitionFlag.BLOCK_CONDITIONAL;
import static tools.dscode.common.assertions.AssertionChain.copyAssertionChainToNewPhrase;
import static tools.dscode.common.domoperations.ExecutionDictionary.STARTING_CONTEXT;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.reporting.logging.LogForwarder.getDefaultLoggingLevel;
import static tools.dscode.common.reporting.logging.LogForwarder.logDebug;
import static tools.dscode.common.reporting.logging.LogForwarder.logInfo;
import static tools.dscode.common.reporting.logging.LogForwarder.logSkip;
import static tools.dscode.common.reporting.logging.LogForwarder.logToDefaultLevel;
import static tools.dscode.common.reporting.logging.LogForwarder.setDefaultEntry;
import static tools.dscode.common.reporting.logging.LogForwarder.setDefaultLoggingLevel;


public final class Phrase extends PhraseData {


    public Phrase(LineData parsedLine) {
        super(STARTING_CONTEXT, ',', parsedLine);
        isTopContext = true;
    }

    public Phrase(String inputText, Character delimiter, LineData parsedLine) {
        super(inputText, delimiter, parsedLine, null);
    }

    public Phrase(String inputText, Character delimiter, LineData parsedLine, PhraseData previousPhrase) {
        super(inputText, delimiter, parsedLine, previousPhrase);
        if (!isOperationPhrase) {
            elementMatches = new ArrayList<>(elementMatches.stream().filter(e -> !e.isPlaceHolder()).toList());
        }
    }


    boolean shouldRun() {

        if (assertionChainMembership != null)
            return true;
        if (getConditional().startsWith("else")) {
            if (this.position == 0) {
                if (parsedLine.previousSiblingConditionalState > -1) {
                    phraseConditionalMode = 0;
                    previouslyResolvedBoolean = false;
                }
            } else if (getPreviousPhrase() == null) {
                if (parsedLine.previousSiblingConditionalState > -1) {
                    phraseConditionalMode = 0;
                    previouslyResolvedBoolean = false;
                }
            } else if (getPreviousPhrase().phraseConditionalMode > -1) {
                phraseConditionalMode = 0;
            } else {
                phraseConditionalMode = 1;
            }


            if (getConditional().trim().equals("else")) {

                if (!getRunningStep().emittedStepStartManually && metaTextPrefix.contains("BLOCK_CONDITIONAL")) {
                    if (phraseConditionalMode > 0) {
                        PhraseData phraseData = this;
                        String emitText = " , " + phraseData.text.replaceFirst("else", "") + phraseData.termination;
                        while ((phraseData = phraseData.getNextPhrase()) != null) {
                            if (phraseData.metaTextPrefix.contains("BLOCK_CONDITIONAL")) {
                                phraseData.setNextPhrase(null);
                                break;
                            }
                            emitText += phraseData.text + phraseData.termination;
                            phraseData = phraseData.getPreviousPhrase();
                        }
                        getRunningStep().emitStepStart(emitText);
                    }
                }

            }


        } else {
            phraseConditionalMode = getPreviousPhrase() == null ? 1 : getPreviousPhrase().phraseConditionalMode;
        }

        return phraseConditionalMode > 0;
    }


    @Override
    public PhraseData runPhrase() {
        executePhrase();
        resolveResults();
        setDefaultEntry(getRunningStep().stepEntry);
        PhraseData nextResolvedPhrase = getNextResolvedPhrase();


        if (isContextTermination()) {
            if (termination.equals(':') || termination.equals('?')) {
                parsedLine.lineConditionalMode = phraseConditionalMode;
                parsedLine.inheritancePhrases.add(this);
            }
        }

        return nextResolvedPhrase;
    }


    public void executePhrase() {
        if ((phraseType == null || phraseType == PhraseType.ELEMENT_ONLY) && (templatePhrase != null && templatePhrase.phraseType != null)) {
            if (!templatePhrase.getAction().isBlank()) {
                setAction(templatePhrase.getAction());
            } else {
                if (!templatePhrase.getConditional().isBlank() && getConditional().isBlank()) {
                    setConditional(templatePhrase.getConditional());
                }

                if (!templatePhrase.getAssertionType().isBlank() && getAssertionType().isBlank()) {
                    setAssertionType(templatePhrase.getAssertionType());
                }

                if (!templatePhrase.getAssertion().isBlank() && getAssertion().isBlank()) {
                    setAssertion(templatePhrase.getAssertion());
                }
            }
        }

        if (phraseType == null && !getConditional().isBlank()) {
            phraseType = PhraseType.CONDITIONAL;
        }


        if (assertionChain == null)
            parsedLine.executedPhrases.add(this);

        if (!text.equals(resolvedText)) {
            logDebug("Resolving `" + text + "` to `" + resolvedText + "`");
        }

        StepExtension currentStep = getRunningStep();

        if (shouldRun()) {
            if (parsedLine.isBlockConditionalStep && !metaTextPrefix.contains("BLOCK_CONDITIONAL"))
                setDefaultLoggingLevel(Level.INFO);
            if (assertionChain == null) {
                phraseEntry = currentStep.stepEntry.logWithType("PHRASE", toString()).tags("phrase").start();
                logToDefaultLevel("Running Phrase: " + this.resolvedText);
            } else {
                logToDefaultLevel("Initiating Assertion Chain: " + assertionChain);
            }
            setDefaultEntry(phraseEntry);
            if (currentStep != null && nextSemicolon()) {
                currentStep.waitForPageReady = false;
            }
        } else {
            if (parsedLine.isBlockConditionalStep && metaTextPrefix.contains("BLOCK_CONDITIONAL"))
                setDefaultLoggingLevel(Level.DEBUG);
            logSkip("Skipping Phrase: " + this.resolvedText);
            wasPhraseSkipped = true;
            assertionChain = null;
            return;
        }



        if (noExecution) {
            logDebug("Context branch set");
            return;
        }


        getElementMatches().forEach(e -> {
            if (e.elementTypes.contains(ElementType.HTML_TYPE)) e.contextWrapper = new ContextWrapper(e);
        });

        if (isOperationPhrase) {
            runOperation();
        } else if (phraseType == PhraseType.CONTEXT) {
            processContextPhrase();
        }
    }


    void processContextPhrase() {
        ElementMatch firstElement = getFirstElement();
        if (firstElement.elementTypes.contains(ElementType.DATA_TYPE)) {
            categoryFlags.add(ExecutionDictionary.CategoryFlags.DATA_CONTEXT);
//            String categoryName = firstElement.category.replaceFirst("(?i:s)$", "");
            String key = null;
            List obj;
            try {
                obj = getPhraseParsingMap().get(firstElement);
            } catch (NullPointerException e) {
                obj = null;
            }
            if (obj == null)
                throw new RuntimeException("Failed to find Data element for: " + firstElement);

            if (firstElement.selectionType.isEmpty()) {
                if (obj == null || obj.isEmpty())
                    phraseConditionalMode = 0;
                else
                    saveToPhraseParsingMap(key, obj.getLast());
            } else {
                if (obj == null || obj.isEmpty()) {
                    phraseConditionalMode = 0;
                    if (!firstElement.selectionType.equals("any")) {
                        throw new RuntimeException("Failed to find Data elements for " + firstElement);
                    }
                    System.out.println("No Data elements match for " + firstElement + ", skipping subsequent phrases");
                } else {
                    for (Object item : obj) {
                        branchedPhrases.add(cloneWithDataElement(key, item));
                    }
                }
            }
        } else if (!firstElement.selectionType.isEmpty() || !firstElement.elementPosition.isEmpty()) {
            if (firstElement.getElementWrappers().isEmpty()) {
                phraseConditionalMode = 0;
                if (!firstElement.selectionType.equals("any")) {
                    throw new RuntimeException("Failed to find WebElements for " + firstElement);
                }
                System.out.println("No elements match for " + firstElement + ", skipping subsequent phrases");
            }
            for (ElementWrapper elementWrapper : getWrappedElements()) {
                branchedPhrases.add(cloneWithElementContext(elementWrapper));
            }
        }
    }


    public PhraseData cloneWithElementContext(ElementWrapper elementWrapper) {
        PhraseData clone = clonePhrase(getPreviousPhrase());
        clone.contextElement = elementWrapper;
        clone.categoryFlags.add(ExecutionDictionary.CategoryFlags.ELEMENT_CONTEXT);
        clone.noExecution = true;
        return clone;
    }

    public PhraseData cloneWithDataElement(String key, Object object) {
        PhraseData clone = clonePhrase(getPreviousPhrase());
        clone.categoryFlags.add(ExecutionDictionary.CategoryFlags.DATA_CONTEXT);
        clone.noExecution = true;
        saveToPhraseParsingMap(clone, key, object);
        return clone;
    }


    public void saveToPhraseParsingMap(String key, Object object) {
        saveToPhraseParsingMap(this, key, object);
    }

    public void saveToPhraseParsingMap(PhraseData phraseData, String key, Object object) {
        if (key == null) {
            if (object instanceof ObjectNode objectNode) {
                phraseData.setPhraseParsingMap(objectNode);
            } else {
                phraseData.setPhraseParsingMap(MAPPER.valueToTree(object));
            }
        } else {
            phraseData.setPhraseParsingMap(MAPPER.createObjectNode().set(key, MAPPER.valueToTree(object)));
        }
    }


    @Override
    public PhraseData cloneInheritedPhrase() {
        PhraseData clonedPhrase = clonePhrase(getPreviousPhrase());
        clonedPhrase.branchedPhrases.addAll(branchedPhrases);
        clonedPhrase.setResolvedPhrase(clonedPhrase);
        return clonedPhrase;
    }

    @Override
    public PhraseData clonePhrase(PhraseData previous) {
        return clonePhrase(previous, null);
    }


    @Override
    public PhraseData clonePhrase(PhraseData previous, Character newTermination) {
        Phrase clone = copyPhraseWithModifications(this, newTermination, parsedLine, previous);
        clone.phraseParsingMap = null;
        return clone;
    }

    public PhraseData resolvePhrase() {
        PhraseData resolvedPhrase = new Phrase(originalText, termination, parsedLine, getPreviousPhrase());
        setResolvedPhrase(resolvedPhrase);
        getResolvedPhrase().position = position;
        if (assertionChain != null)
            copyAssertionChainToNewPhrase(this, getResolvedPhrase());
        getResolvedPhrase().untilPhrase = untilPhrase;
        getResolvedPhrase().setNextPhrase(getNextPhrase());
        return getResolvedPhrase();
    }


    public PhraseData getNextResolvedPhrase() {
        if (getNextPhrase() == null) return null;
        PhraseData nextResolvedPhrase = getNextPhrase().resolvePhrase();
        nextResolvedPhrase.setPreviousPhrase(this);
        this.setNextPhrase(nextResolvedPhrase);

        return nextResolvedPhrase;
    }


    public static Phrase copyPhraseWithModifications(Phrase phrase) {
        return copyPhraseWithModifications(phrase, null, null, null);
    }

    public static Phrase copyPhraseWithModifications(Phrase phrase, Character newTermination, LineData parsedLine, PhraseData previous) {
        Phrase clonePhrase = newTermination == null ? new Phrase(phrase.originalText, phrase.termination, phrase.parsedLine) : new Phrase(phrase.originalText, newTermination, parsedLine, previous);
        clonePhrase.operationInheritancePhrase = phrase.operationInheritancePhrase;
        clonePhrase.hasNo = phrase.hasNo;
        clonePhrase.assertionChainMembership = phrase.assertionChainMembership;
        clonePhrase.isChainedAssertion = phrase.isChainedAssertion;
        clonePhrase.operationIndex = phrase.operationIndex;
        clonePhrase.conjunction = phrase.conjunction;
        clonePhrase.conditional = phrase.conditional;
        clonePhrase.assertionType = phrase.assertionType;
        clonePhrase.action = phrase.action;
        clonePhrase.actionOperation = phrase.actionOperation;
        clonePhrase.assertionOperation = phrase.assertionOperation;
        clonePhrase.isOperationPhrase = phrase.isOperationPhrase;
        clonePhrase.phraseType = phrase.phraseType;
        clonePhrase.untilPhrase = phrase.untilPhrase;
        clonePhrase.phraseConditionalMode = phrase.phraseConditionalMode;
        clonePhrase.result = null;
        clonePhrase.isClone = true;
        clonePhrase.position = phrase.position;
        clonePhrase.phraseParsingMap = phrase.phraseParsingMap;
        if (previous == null) {
            clonePhrase.setPreviousPhrase(phrase.getPreviousPhrase());
        }
        if (phrase.getNextPhrase() != null) {
            clonePhrase.setNextPhrase(phrase.getNextPhrase().clonePhrase(clonePhrase));
            clonePhrase.getNextPhrase().setPreviousPhrase(clonePhrase);
        }

        return clonePhrase;

    }


}
