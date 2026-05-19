package tools.dscode.common.treeparsing.preparsing;

import io.cucumber.core.runner.StepBase;
import tools.dscode.common.assertions.AssertionChain;
import tools.dscode.common.mappings.QuoteParser;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static io.cucumber.core.runner.StepBase.getInheritancePhrase;
import static tools.dscode.common.GlobalConstants.BOOK_END;
import static tools.dscode.common.treeparsing.RegexUtil.stripObscureNonText;

public abstract class LineData implements Cloneable {
    public boolean isBlockConditionalStep;
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
    private static final Set<Character> DELIMITERS = Set.of(',', ';', ':', '.', '!', '?');
    //    public final List<PhraseData> contextPhrases = new ArrayList<>();
    public List<PhraseData> inheritancePhrases = new ArrayList<>();
    public PhraseData inheritedPhrase;
    public int inheritedConditionalState;
    public int previousSiblingConditionalState = 1;
    public StepBase stepExtension;


    public void setInheritance(StepBase currentStep) {
        stepExtension = currentStep;

        PhraseData previousSiblingInheritancePhrase = getInheritancePhrase(currentStep.previousSibling);
        previousSiblingConditionalState = previousSiblingInheritancePhrase == null ? 1 : previousSiblingInheritancePhrase.phraseConditionalMode;
        StepBase parentStep = currentStep.parentStep;
        inheritedPhrase = getInheritancePhrase(parentStep);
        inheritedConditionalState = inheritedPhrase == null ? 1 : inheritedPhrase.phraseConditionalMode;
        currentStep.logAndIgnore =
                inheritedConditionalState < 1
                        || (parentStep != null && parentStep.logAndIgnore);

        if(inheritedPhrase != null) {
            inheritedPhrase.setPhraseParsingMap(currentStep.getStepParsingMap());
        }
    }

    public List<String> lineComponents = new ArrayList<>();

    @Override
    public String toString() {
        return this.runningText;
    }

    public abstract PhraseData runPhraseFromLine(PhraseData phrase);

    public LineData(String input) {
        input = stripObscureNonText(Objects.requireNonNull(input, "input"));

        // Ensure non-blank input ends with a delimiter
        if (!input.isBlank()) {
            String t = input.stripTrailing();
            char last = t.charAt(t.length() - 1);
            if (!DELIMITERS.contains(last)) {
                input = t + "."; // note: uses stripped version so you don't keep trailing whitespace
            } else {
                input = t; // optional: normalize away trailing whitespace consistently
            }
        }

        input = wrapLooseConditionalExpression(input);

        this.original = input;

        // 1) Mask quotes first
        this.qp = new QuoteParser(input);
        String afterQuotes = qp.masked();

        // 2) Mask brackets second
        this.bm = getBracketMasker(afterQuotes);
        this.fullyMasked = bm.masked();

        if (!original.startsWith(",")) return;

//        String preParsedNormalized = preParseDynamicStepString(fullyMasked);
        String preParsedNormalized = fullyMasked;

        StringBuilder buf = new StringBuilder();

        int sentenceCount = 0;
        for (int i = 0; i < preParsedNormalized.length(); i++) {
            char c = preParsedNormalized.charAt(i);
            if (isDelimiterAt(preParsedNormalized, i)) {
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

    private static boolean isDelimiterAt(String s, int index) {
        char c = s.charAt(index);

        if (!DELIMITERS.contains(c)) {
            return false;
        }

        if (c != '.') {
            return true;
        }

        boolean previousIsDigit = index > 0 && Character.isDigit(s.charAt(index - 1));
        boolean nextIsDigit = index + 1 < s.length() && Character.isDigit(s.charAt(index + 1));

        return !(previousIsDigit && nextIsDigit);
    }

    private static String wrapLooseConditionalExpression(String input) {
        QuoteParser qp = new QuoteParser(input);
        BracketMasker bm = getBracketMasker(qp.masked());

        String masked = bm.masked().trim();
        if (!containsConditionalOperator(masked)) return input;
        if (masked.startsWith("IF:") || masked.startsWith("ELSE")) return input;

        String replaced = masked.replaceAll(
                "([.,:?!;][^.,:?!;{]*\\s(?:(?:(?:else\\s+)?if\\s+)|until\\s+)?)([^.,:?!;]*(?:==|!=|&&|\\|\\||>|<)[^.,:?!;]*)(?=[.,:?!;]|$)",
                "$1  { $2 } "
        );


        String restored = qp.restoreFrom(bm.restoreFrom(replaced));
        return restored;
    }

    private static boolean containsConditionalOperator(String masked) {
        return masked.contains("==")
                || masked.contains("!=")
                || masked.contains("&&")
                || masked.contains("||")
                || masked.contains(">")
                || masked.contains("<");
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
                lineComponents.set(
                        lineComponentIndex - 1,
                        lineComponents.get(lineComponentIndex - 1).concat(phraseText + " " + termination)
                );
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
     * Delimiters used for splitting.
     */
    public static Set<Character> delimiters() {
        return DELIMITERS;
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
            copy.inheritancePhrases = new ArrayList<>();
            if (this.inheritedPhrase != null) {
                copy.inheritedPhrase = this.inheritedPhrase.cloneInheritedPhrase();
            }
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("Clone should be supported", e);
        }
    }

    public static BracketMasker getBracketMasker(String input) {
        return new BracketMasker(input);
    }

    public abstract void runPhrases();
}