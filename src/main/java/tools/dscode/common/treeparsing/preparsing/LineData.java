package tools.dscode.common.treeparsing.preparsing;

import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.mappings.JsonPathUtil;
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


import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.GlobalConstants.BOOK_END;
import static tools.dscode.common.treeparsing.RegexUtil.normalizeWhitespace;
import static tools.dscode.common.treeparsing.RegexUtil.stripObscureNonText;

public abstract class LineData implements Cloneable {
    public int lineConditionalMode = 0;
    public int startPhraseIndex = 0;
    //    public LineData inheritedLineData;
//    public List<PhraseData> contextPhrases = new ArrayList<>();
    public List<List<PhraseData>> inheritedContextPhrases = new ArrayList<>();
    private final String original;
    private final QuoteParser qp;
    private final BracketMasker bm;
    private final String fullyMasked;
    public final List<PhraseData> phrases = new ArrayList<>();
    public final List<PhraseData> executedPhrases = new ArrayList<>();
    private final Set<Character> delimiters; // characters that cause a split
    //    public final List<PhraseData> contextPhrases = new ArrayList<>();

    @Override
    public String toString() {
        return this.original + " " + phrases;
    }

    public LineData(String input, Collection<Character> delimiters) {

        this.original = stripObscureNonText(Objects.requireNonNull(input, "input"));
        Objects.requireNonNull(delimiters, "delimiters");
//        if (delimiters.isEmpty())
//            throw new IllegalArgumentException("Delimiters cannot be empty");
        this.delimiters = Collections.unmodifiableSet(new LinkedHashSet<>(delimiters));

        // 1) Mask quotes first
        this.qp = new QuoteParser(input);
        String afterQuotes = qp.masked();

        // 2) Mask brackets second (BracketMasker is in the same package)
        this.bm = new BracketMasker(afterQuotes);
        fullyMasked = bm.masked();

        if (!original.startsWith(","))
            return;

        String preParsedNormalized = normalizeWhitespace(fullyMasked)
//                .replaceAll("\\b(?:the|then|a)\\b", "")
                .replaceAll("\\b(?:the|a)\\b", "")
                .replaceAll("\\bare\\b", "is")
                .replaceAll("\\bhave\\b", "has")
                .replaceAll("(\\d+)(?:\\s*(?:st|nd|rd|th)\\b)", "#$1")
                .replaceAll("\\bverifies\\b", "verify")
                .replaceAll("\\bensures\\b", "ensure")
                .replaceAll("\\bno\\b|n't\\b", " not ").replaceAll("\\s+", " ");

        // Split on UNMASKED delimiters in the fully-masked string
        StringBuilder buf = new StringBuilder();

        for (int i = 0; i < preParsedNormalized.length(); i++) {
            char c = preParsedNormalized.charAt(i);
            if (this.delimiters.contains(c)) {
                // Unmask in reverse order for the chunk before the delimiter
                String chunk = buf.toString();


                String unmasked = qp.restoreFromWithOuterBookend(bm.restoreFrom(chunk), BOOK_END);

                if (!unmasked.isBlank()) {
                    this.phrases.add(new Phrase(unmasked, c, this));
                }
                buf.setLength(0);
            } else {
                buf.append(c);
            }
        }
        // Final chunk (no trailing delimiter)
        String lastChunk = buf.toString();
        if (!lastChunk.isBlank()) {
            String unmaskedLast = qp.restoreFromWithOuterBookend(bm.restoreFrom(lastChunk), BOOK_END);
            this.phrases.add(new Phrase(unmaskedLast, ' ', this));
        }

        PhraseData lastPhrase = null;
        for (PhraseData phrase : this.phrases) {
            if (lastPhrase != null) {
                phrase.previousPhrase = lastPhrase;
                lastPhrase.nextPhrase = phrase;
            }

            if (lastPhrase != null) {
                if (phrase.phraseType == null) {
                    if (lastPhrase.phraseType == PhraseData.PhraseType.ACTION) {
                        if(!phrase.elementMatches.isEmpty()) {
                            phrase.phraseType = PhraseData.PhraseType.ACTION;
                            phrase.setAction(lastPhrase.getAction());
                            phrase.operationPhrase = true;
                        }
                    }
                }
            }

            lastPhrase = phrase;

        }

        checkForMissingData();



        //TODO:
        PhraseData currentPhrase = this.phrases.isEmpty() ? null : this.phrases.getFirst();
        lastPhrase = null;

        while (currentPhrase != null) {
            if (currentPhrase.phraseType == null) {
                if(!currentPhrase.elementMatches.isEmpty())
                {




                }


            }


            lastPhrase = currentPhrase;
            currentPhrase = currentPhrase.nextPhrase;

        }

        for (PhraseData phrase : this.phrases) {

            if (phrase.context.isBlank() && !phrase.operationPhrase) {


            }

            if (!phrase.assertionType.isBlank()) {


            }

        }

    }


    private boolean checkForMissingData() {
        boolean anyMissingData = false;
        for (PhraseData phrase : this.phrases) {
            if (phrase.phraseType == null) {
                phrase.missingData = true;
                anyMissingData = true;
            }

            if (phrase.phraseType == PhraseData.PhraseType.CONDITIONAL && phrase.getAssertion().isBlank()) {
                phrase.missingData = true;
                anyMissingData = true;
            }

            if (phrase.phraseType == PhraseData.PhraseType.ASSERTION && (phrase.getAssertion().isBlank() || phrase.assertionType.isBlank())) {
                phrase.missingData = true;
                anyMissingData = true;
            }
        }
        return anyMissingData;
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

            copy.inheritedContextPhrases = new ArrayList<>(this.inheritedContextPhrases.size());

            // clone each inner list wrapper, but keep the same PhraseData objects
            for (List<PhraseData> inner : this.inheritedContextPhrases) {
                List<PhraseData> innerCopy =
                        (inner == null ? null : new ArrayList<>(inner));  // shallow copy of inner list
                copy.inheritedContextPhrases.add(innerCopy);
            }

            return copy;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("Clone should be supported", e);
        }
    }


    public abstract void runPhrases();
}