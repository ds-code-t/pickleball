package tools.dscode.common.composite;

/**
 * One Pickleball element description before it becomes a composite selector.
 * Field meanings match the Gherkin phrase, not a second locator language.
 */
public record ElementPhrase(
        String text,
        String match,
        String quote,
        String category,
        String state,
        String ordinal,
        String contextWord,
        String contextText,
        String contextCategory,
        String xpath,
        String css,
        String source,
        String template,
        String ocr
) {
    public ElementPhrase {
        match = match == null || match.isBlank() ? "equals" : match;
        quote = quote == null || quote.isBlank() ? "double" : quote;
        source = source == null || source.isBlank() ? "web" : source;
    }

    public static ElementPhrase of(String category, String text) {
        return new ElementPhrase(text, "equals", "double", category, null, null, null, null, null, null, null, "web", null, null);
    }

    public ElementPhrase match(String mode) {
        return new ElementPhrase(text, mode, quote, category, state, ordinal, contextWord, contextText, contextCategory, xpath, css, source, template, ocr);
    }

    public ElementPhrase quote(String style) {
        return new ElementPhrase(text, match, style, category, state, ordinal, contextWord, contextText, contextCategory, xpath, css, source, template, ocr);
    }

    public ElementPhrase state(String word) {
        return new ElementPhrase(text, match, quote, category, word, ordinal, contextWord, contextText, contextCategory, xpath, css, source, template, ocr);
    }

    public ElementPhrase ordinal(String word) {
        return new ElementPhrase(text, match, quote, category, state, word, contextWord, contextText, contextCategory, xpath, css, source, template, ocr);
    }

    public ElementPhrase context(String word, String contextText, String contextCategory) {
        return new ElementPhrase(text, match, quote, category, state, ordinal, word, contextText, contextCategory, xpath, css, source, template, ocr);
    }

    public ElementPhrase xpath(String value) {
        return new ElementPhrase(text, match, quote, category, state, ordinal, contextWord, contextText, contextCategory, value, css, source, template, ocr);
    }

    public ElementPhrase css(String value) {
        return new ElementPhrase(text, match, quote, category, state, ordinal, contextWord, contextText, contextCategory, xpath, value, source, template, ocr);
    }

    public ElementPhrase source(String value) {
        return new ElementPhrase(text, match, quote, category, state, ordinal, contextWord, contextText, contextCategory, xpath, css, value, template, ocr);
    }

    public ElementPhrase template(String path) {
        return new ElementPhrase(text, match, quote, category, state, ordinal, contextWord, contextText, contextCategory, xpath, css, source, path, ocr);
    }

    public ElementPhrase ocr(String value) {
        return new ElementPhrase(text, match, quote, category, state, ordinal, contextWord, contextText, contextCategory, xpath, css, source, template, value);
    }
}
