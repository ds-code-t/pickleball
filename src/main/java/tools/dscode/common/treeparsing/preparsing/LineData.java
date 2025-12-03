package tools.dscode.common.treeparsing.preparsing;

import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.mappings.QuoteParser;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static tools.dscode.common.domoperations.ExecutionDictionary.STARTING_CONTEXT;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.treeparsing.RegexUtil.normalizeWhitespace;
import static tools.dscode.common.treeparsing.RegexUtil.stripObscureNonText;

public abstract class LineData implements Iterable<Phrase> {
    public LineData inheritedLineData;
//    public List<PhraseData> contextPhrases = new ArrayList<>();
    public List<List<PhraseData>> inheritedContextPhrases = new ArrayList<>();
    private final String original;
    private final QuoteParser qp;
    private final BracketMasker bm;
    public final List<Phrase> phrases = new ArrayList<>();
    private final Set<Character> delimiters; // characters that cause a split
    //    public final List<PhraseData> contextPhrases = new ArrayList<>();
    public ExecutionDictionary.CategoryResolution defaultCategory = getExecutionDictionary().andThenOrWithFlags(STARTING_CONTEXT, null, ExecutionDictionary.Op.DEFAULT);




//    public Phrase getDefaultContextPhrase() {
//        Phrase initialPhrase = null;
//        if(getExecutionDictionary().categoryHasRegistration(STARTING_CONTEXT)) {
//            initialPhrase = new Phrase("from " + STARTING_CONTEXT, ',', this);
//            initialPhrase.phraseType = PhraseData.PhraseType.INITIAL;
//        }
//        return initialPhrase;
//    }

    public LineData(String input, Collection<Character> delimiters) {
//        contextPhrases.addAll(getRunningStep().contextPhraseData);


        this.original = stripObscureNonText(Objects.requireNonNull(input, "input"));
        Objects.requireNonNull(delimiters, "delimiters");
        if (delimiters.isEmpty())
            throw new IllegalArgumentException("Delimiters cannot be empty");
        this.delimiters = Collections.unmodifiableSet(new LinkedHashSet<>(delimiters));

        // 1) Mask quotes first
        this.qp = new QuoteParser(input);
        String afterQuotes = qp.masked();

        // 2) Mask brackets second (BracketMasker is in the same package)
        this.bm = new BracketMasker(afterQuotes);
        String fullyMasked = bm.masked();

        String preParsedNormalized = normalizeWhitespace(fullyMasked)
                .replaceAll("(?i)\\b(?:the|then|a)\\b", "")
                .replaceAll("(\\d+)(?:\\\s*(?:st|nd|rd|th))", "#$1")
                .replaceAll("\\bverifies\\b", "verify")
                .replaceAll("\\bensures\\b", "ensure")
                .replaceAll("\\bno\\b|n't\\b", " not").replaceAll("\\s+", " ");

        // Split on UNMASKED delimiters in the fully-masked string
        StringBuilder buf = new StringBuilder();

        for (int i = 0; i < preParsedNormalized.length(); i++) {
            char c = preParsedNormalized.charAt(i);
            if (this.delimiters.contains(c)) {
                // Unmask in reverse order for the chunk before the delimiter
                String chunk = buf.toString();
                System.out.println("@@chunk: " + chunk);

                String unmasked = qp.restoreFrom(bm.restoreFrom(chunk));
                this.phrases.add(new Phrase(unmasked, c, this));
                buf.setLength(0);
            } else {
                buf.append(c);
            }
        }
        // Final chunk (no trailing delimiter)
        String lastChunk = buf.toString();
        if(!lastChunk.isBlank()) {
            System.out.println("@@chunk-lastChunk: " + lastChunk);
            String unmaskedLast = qp.restoreFrom(bm.restoreFrom(lastChunk));
            this.phrases.add(new Phrase(unmaskedLast, ' ', this));
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
    public List<Phrase> phrases() {
        return phrases;
    }

    /**
     * Delimiters used for splitting (unmodifiable).
     */
    public Set<Character> delimiters() {
        return delimiters;
    }

    public int size() {
        return phrases.size();
    }

    public Phrase get(int index) {
        return phrases.get(index);
    }

    @Override
    public Iterator<Phrase> iterator() {
        return phrases.iterator();
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

}