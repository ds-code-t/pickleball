package tools.dscode.common.treeparsing.preparsing;

import io.cucumber.core.runner.StepBase;
import tools.dscode.common.mappings.QuoteParser;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;


import static tools.dscode.common.GlobalConstants.BOOK_END;
import static tools.dscode.common.treeparsing.RegexUtil.normalizeWhitespace;
import static tools.dscode.common.treeparsing.RegexUtil.stripObscureNonText;

public abstract class LineData implements Cloneable {
    public int lineConditionalMode = 1;
    public int startPhraseIndex = 0;
    //    public LineData inheritedLineData;
//    public List<PhraseData> contextPhrases = new ArrayList<>();
//    public List<List<PhraseData>> inheritedContextPhrases = new ArrayList<>();
    private final String original;
    public String runningText = ", ";
    private final QuoteParser qp;
    private final BracketMasker bm;
    private final String fullyMasked;
    public final List<PhraseData> phrases = new ArrayList<>();
    public final List<PhraseData> executedPhrases = new ArrayList<>();
    private final Set<Character> delimiters; // characters that cause a split
    //    public final List<PhraseData> contextPhrases = new ArrayList<>();
    public PhraseData inheritancePhrase;
    public PhraseData inheritedPhrase;
    public int inheritedConditionalState;
    public int previousSiblingConditionalState = 1;
    public StepBase stepExtension;


    public void setInheritance(StepBase currentStep) {
        stepExtension = currentStep;

        PhraseData previousSiblingInheritancePhrase = currentStep.previousSibling == null ? null : currentStep.previousSibling.lineData.inheritancePhrase;
        previousSiblingConditionalState = previousSiblingInheritancePhrase == null ? 1 : previousSiblingInheritancePhrase.phraseConditionalMode;

        StepBase parentStep = currentStep.parentStep;
        inheritedPhrase = parentStep == null ? null : parentStep.lineData.inheritancePhrase;
        inheritedConditionalState = inheritedPhrase == null ? 1 : inheritedPhrase.phraseConditionalMode;

        currentStep.logAndIgnore =
                inheritedConditionalState < 1
                        || (parentStep != null && parentStep.logAndIgnore);
    }

    public List<String> lineComponents = new ArrayList<>();

    @Override
    public String toString() {
        return this.runningText;
    }

    public abstract void runPhraseFromLine(PhraseData phrase);

    public LineData(String input, Collection<Character> phraseSeparators) {
        input = stripObscureNonText(Objects.requireNonNull(input, "input"));
        Objects.requireNonNull(phraseSeparators, "phraseSeparators");

        // Build our own delimiter set (no side effects on caller)
        LinkedHashSet<Character> delims = new LinkedHashSet<>(phraseSeparators);
        delims.add('.'); // always allow '.' as a delimiter
        this.delimiters = Collections.unmodifiableSet(delims);

        // Ensure non-blank input ends with a delimiter
        if (!input.isBlank()) {
            String t = input.stripTrailing();
            char last = t.charAt(t.length() - 1);
            if (!this.delimiters.contains(last)) {
                input = t + "."; // note: uses stripped version so you don't keep trailing whitespace
            } else {
                input = t; // optional: normalize away trailing whitespace consistently
            }
        }

        this.original = input;

        // 1) Mask quotes first
        this.qp = new QuoteParser(input);
        String afterQuotes = qp.masked();

        // 2) Mask brackets second
        this.bm = new BracketMasker(afterQuotes);
        this.fullyMasked = bm.masked();

        if (!original.startsWith(",")) return;

        String preParsedNormalized = normalizeWhitespace(fullyMasked)
                .replaceAll("\\bno\\s+attribute\\b", "noattribute")
                .replaceAll("\\b(?:the|a)\\b", "")
                .replaceAll("\\bare\\b", "is")
                .replaceAll("\\bhave\\b", "has")
                .replaceAll("(\\d+)(?:\\s*(?:st|nd|rd|th)\\b)", "#$1")
                .replaceAll("\\bverifies\\b", "verify")
                .replaceAll("\\bensures\\b", "ensure")
                .replaceAll("\\bnot\\b|n't\\b", " no ")
                .replaceAll("\\s+", " ");

        StringBuilder buf = new StringBuilder();

        int sentenceCount = 0;
        for (int i = 0; i < preParsedNormalized.length(); i++) {
            char c = preParsedNormalized.charAt(i);
            if (this.delimiters.contains(c)) {
                String chunk = buf.toString();
                String unmasked = qp.restoreFromWithOuterBookend(bm.restoreFrom(chunk), BOOK_END);
                if (!unmasked.isBlank()) {
                    addPhrase(unmasked, c, sentenceCount);
                }
                buf.setLength(0);
                if (c != ',') sentenceCount++;
            } else {
                buf.append(c);
            }
        }

        PhraseData lastPhrase = null;
        for (PhraseData phrase : this.phrases) {
            if (lastPhrase != null) {
                phrase.setPreviousPhrase(lastPhrase);
                lastPhrase.setNextPhrase(phrase);
            }
            lastPhrase = phrase;
        }
    }


    public void addPhrase(String phraseText, char termination, int lineComponentIndex) {
        String phraseString = phraseText.replace(BOOK_END, "") + termination;
        if (lineComponentIndex == 0) {
            phrases.add(new Phrase(phraseText, termination, this));
            runningText += phraseString;
        } else {
            if (lineComponents.size() < lineComponentIndex) {
                lineComponents.add(", " + phraseString);
            } else {
                lineComponents.set(lineComponentIndex - 1, lineComponents.get(lineComponentIndex - 1).concat(phraseText + " " + termination));
            }
        }
    }


    /**
     * Original, unmodified input.
     */
    public String original() {
        return original;
    }

    /**
     * The phrases in order, each with its trailing delimiter (if any).
     */
    public List<PhraseData> phrases() {
        return phrases;
    }

    /**
     * Delimiters used for splitting (unmodifiable).
     */
    public Set<Character> delimiters() {
        return delimiters;
    }


    public PhraseData get(int index) {
        return phrases.get(index);
    }


    /**
     * Expose the QuoteParser used (useful if callers need placeholder map,
     * etc.).
     */
    public QuoteParser quoteParser() {
        return qp;
    }

    /**
     * Expose the BracketMasker used (in case callers want to inspect
     * placeholders).
     */
    public BracketMasker bracketMasker() {
        return bm;
    }

    @Override
    public LineData clone() {
        try {
            LineData copy = (LineData) super.clone();
            if (this.inheritedPhrase != null) {
                copy.inheritedPhrase = this.inheritedPhrase.cloneInheritedPhrase();
            }
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("Clone should be supported", e);
        }
    }


    public abstract void runPhrases();
}