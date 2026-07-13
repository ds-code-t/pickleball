package tools.dscode.common.mappings.queries;

import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch.TextOp;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import static tools.dscode.common.domoperations.ExecutionDictionary.Op.EQUALS;

/**
 * Builds JSONata queries for retrieving object properties whose names match
 * the TextOp conditions contained in an ElementMatch.
 */
public final class DataQuery {

    private DataQuery() {
        // Utility class
    }

    /**
     * Creates a JSONata query that retrieves values from an object based on
     * the property-name conditions in {@link ElementMatch#textOps}.
     *
     * <p>Multiple TextOps are combined with {@code and}. For example,
     * STARTS_WITH "prop" and ENDS_WITH "A" matches "propA".</p>
     *
     * <p>Return behavior:</p>
     * <ul>
     *     <li>No TextOps: returns the complete input value.</li>
     *     <li>No matching properties: returns an empty array.</li>
     *     <li>One matching property: returns that property's value.</li>
     *     <li>Multiple matching properties: returns an array of values.</li>
     * </ul>
     *
     * @param elementMatch element containing the property-name operations
     * @return a legal JSONata query string
     */
    public static String buildKeyQuery(ElementMatch elementMatch) {
        Objects.requireNonNull(elementMatch, "elementMatch must not be null");

        List<TextOp> textOps = elementMatch.textOps;

        if (textOps == null || textOps.isEmpty()) {
            return "$";
        }

        /*
         * A single exact property name can use a direct dynamic lookup.
         */
        if (textOps.size() == 1 && textOps.getFirst().op() == EQUALS) {
            String propertyName = getTextOpValue(textOps.getFirst());

            return "$lookup($, "
                    + jsonataStringLiteral(propertyName)
                    + ")";
        }

        String predicate = String.join(
                " and ",
                textOps.stream()
                        .map(DataQuery::toJsonataKeyPredicate)
                        .map(expression -> "(" + expression + ")")
                        .toList()
        );

        return """
                (
                  $root := $;

                  $matchingKeys := $filter(
                    $keys($root),
                    function($key) {
                      %s
                    }
                  );

                  $count($matchingKeys) = 0
                    ? []
                    : $count($matchingKeys) = 1
                      ? $lookup($root, $matchingKeys[0])
                      : [
                          $map(
                            $matchingKeys,
                            function($key) {
                              $lookup($root, $key)
                            }
                          )
                        ]
                )
                """.formatted(predicate).strip();
    }

    private static String toJsonataKeyPredicate(TextOp textOp) {
        String value = getTextOpValue(textOp);
        String literal = jsonataStringLiteral(value);

        return switch (textOp.op()) {
            case EQUALS ->
                    "$key = " + literal;

            case CONTAINS ->
                    "$contains($key, " + literal + ")";

            case STARTS_WITH ->
                    "$substring($key, 0, $length(" + literal + ")) = "
                            + literal;

            case ENDS_WITH ->
                    "$substring($key, -$length(" + literal + ")) = "
                            + literal;

            /*
             * MATCHES searches using a regular expression.
             * Use ^ and $ when the complete property name must match.
             */
            case MATCHES ->
                    "$contains($key, "
                            + jsonataRegexLiteral(value)
                            + ")";

            default ->
                    throw new IllegalArgumentException(
                            "Unsupported JSONata property operation: "
                                    + textOp.op()
                    );
        };
    }

    private static String getTextOpValue(TextOp textOp) {
        Objects.requireNonNull(textOp, "textOp must not be null");

        if (textOp.op() == null) {
            throw new IllegalArgumentException(
                    "TextOp operation must not be null: " + textOp
            );
        }

        if (textOp.text() == null || textOp.text().isNullOrBlank()) {
            throw new IllegalArgumentException(
                    "TextOp value must not be null or blank for operation: "
                            + textOp.op()
            );
        }

        return textOp.text().asNormalizedText();
    }

    /**
     * Creates a JSONata string literal and protects quotes, backslashes,
     * control characters, and line breaks.
     */
    private static String jsonataStringLiteral(String value) {
        StringBuilder result =
                new StringBuilder(value.length() + 2).append('"');

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);

            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");

                default -> {
                    if (character < 0x20) {
                        result.append(
                                "\\u%04x".formatted((int) character)
                        );
                    } else {
                        result.append(character);
                    }
                }
            }
        }

        return result.append('"').toString();
    }

    /**
     * Creates a slash-delimited JSONata regular-expression literal.
     */
    private static String jsonataRegexLiteral(String regex) {
        /*
         * Validate the expression before inserting it into the query.
         */
        Pattern.compile(regex);

        StringBuilder result =
                new StringBuilder(regex.length() + 2).append('/');

        for (int index = 0; index < regex.length(); index++) {
            char character = regex.charAt(index);

            /*
             * Preserve existing regex escapes.
             */
            if (character == '\\') {
                if (index + 1 >= regex.length()) {
                    throw new IllegalArgumentException(
                            "Regular expression cannot end with a backslash: "
                                    + regex
                    );
                }

                char escapedCharacter = regex.charAt(++index);

                switch (escapedCharacter) {
                    case ' ' -> result.append("\\x20");
                    case '\t' -> result.append("\\t");
                    case '\n' -> result.append("\\n");
                    case '\r' -> result.append("\\r");
                    case '\f' -> result.append("\\f");
                    default -> result
                            .append('\\')
                            .append(escapedCharacter);
                }

                continue;
            }

            switch (character) {
                /*
                 * Protect the JSONata regex delimiter.
                 */
                case '/' -> result.append("\\/");

                /*
                 * Avoid literal whitespace inside slash-delimited patterns.
                 */
                case ' ' -> result.append("\\x20");
                case '\t' -> result.append("\\t");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\f' -> result.append("\\f");

                default -> result.append(character);
            }
        }

        return result.append('/').toString();
    }
}