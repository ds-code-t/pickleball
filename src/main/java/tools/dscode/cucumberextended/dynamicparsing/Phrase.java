package tools.dscode.cucumberextended.dynamicparsing;

import java.util.Optional;

/**
 * A piece of a Sentence: the text (unmasked, delimiter removed) + its delimiter
 * kept separately.
 */
public class Phrase {
    private final String text; // unmasked, with the delimiter stripped
    private final Character delimiter; // ',' or ';' or null if none

    Phrase(String unmaskedTextWithoutDelimiter, Character delimiter) {
        this.text = unmaskedTextWithoutDelimiter;
        this.delimiter = delimiter; // may be null
    }

    /**
     * The phrase content, fully restored (no masking, no trailing delimiter).
     */
    public String text() {
        return text;
    }

    /**
     * The trailing delimiter if present (',' or ';').
     */
    public Optional<Character> delimiter() {
        return Optional.ofNullable(delimiter);
    }

    /**
     * True if this phrase ended with a delimiter in the source.
     */
    public boolean hasDelimiter() {
        return delimiter != null;
    }

    @Override
    public String toString() {
        return hasDelimiter() ? text + delimiter : text;
    }
}
