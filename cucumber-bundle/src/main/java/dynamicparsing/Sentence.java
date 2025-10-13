package dynamicparsing;

import tools.ds.modkit.mappings.QuoteParser;

import java.util.*;

/**
 * Splits an input into Phrase objects by any UNMASKED delimiter character.
 * Masking order for split-protection:
 *   1) QuoteParser (', ", `, and any extras you added, e.g., ''')
 *   2) BracketMasker ((), {}, [], <>)
 * Unmasking is performed in the reverse order per-phrase:
 *   1) BracketMasker.restoreFrom(...)
 *   2) QuoteParser.restoreFrom(...)
 *
 * Default delimiters: ',', ';', ':', '.'
 * You can supply a custom set via the (String, Collection<Character>) constructor.
 */
public final class Sentence implements Iterable<Phrase> {
    private final String original;
    private final QuoteParser qp;
    private final BracketMasker bm;
    private final List<Phrase> phrases;
    private final Set<Character> delimiters; // characters that cause a split when unmasked

    /** Uses default delimiters: ',', ';', ':', '.' */
    public Sentence(String input) {
        this(input, List.of(',', ';', ':', '.'));
    }

    /** Uses the provided delimiters (e.g., List.of(',', ';', '|')). */
    public Sentence(String input, Collection<Character> delimiters) {
        this.original = Objects.requireNonNull(input, "input");
        Objects.requireNonNull(delimiters, "delimiters");
        if (delimiters.isEmpty()) throw new IllegalArgumentException("Delimiters cannot be empty");
        this.delimiters = Collections.unmodifiableSet(new LinkedHashSet<>(delimiters));

        // 1) Mask quotes first
        this.qp = new QuoteParser(input);
        String afterQuotes = qp.masked();

        // 2) Mask brackets second (BracketMasker is in the same package)
        this.bm = new BracketMasker(afterQuotes);
        String fullyMasked = bm.masked();

        // Split on UNMASKED delimiters in the fully-masked string
        List<Phrase> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (int i = 0; i < fullyMasked.length(); i++) {
            char c = fullyMasked.charAt(i);
            if (this.delimiters.contains(c)) {
                // Unmask in reverse order for the chunk before the delimiter
                String chunk = buf.toString();
                String unmasked = qp.restoreFrom(bm.restoreFrom(chunk));
                out.add(new Phrase(unmasked, c));
                buf.setLength(0);
            } else {
                buf.append(c);
            }
        }
        // Final chunk (no trailing delimiter)
        String lastChunk = buf.toString();
        String unmaskedLast = qp.restoreFrom(bm.restoreFrom(lastChunk));
        out.add(new Phrase(unmaskedLast, null));

        this.phrases = Collections.unmodifiableList(out);
    }

    /** Original, unmodified input. */
    public String original() { return original; }

    /** The phrases in order, each with its trailing delimiter (if any). */
    public List<Phrase> phrases() { return phrases; }

    /** Delimiters used for splitting (unmodifiable). */
    public Set<Character> delimiters() { return delimiters; }

    public int size() { return phrases.size(); }

    public Phrase get(int index) { return phrases.get(index); }

    @Override public Iterator<Phrase> iterator() { return phrases.iterator(); }

    /** Expose the QuoteParser used (useful if callers need placeholder map, etc.). */
    public QuoteParser quoteParser() { return qp; }

    /** Expose the BracketMasker used (in case callers want to inspect placeholders). */
    public BracketMasker bracketMasker() { return bm; }
}
