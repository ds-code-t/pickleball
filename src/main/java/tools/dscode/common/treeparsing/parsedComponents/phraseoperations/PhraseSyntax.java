package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

/**
 * One syntax failure for a phrase that is missing a required part.
 * Callers share this instead of dereferencing a null element.
 */
public final class PhraseSyntax {
    private PhraseSyntax() {
    }

    public static IllegalArgumentException missing(String part) {
        String name = part == null || part.isBlank() ? "element" : part.trim();
        throw new IllegalArgumentException("syntax error: missing " + name);
    }
}
