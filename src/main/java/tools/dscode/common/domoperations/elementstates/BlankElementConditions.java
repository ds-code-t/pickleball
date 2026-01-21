package tools.dscode.common.domoperations.elementstates;

import com.xpathy.XPathy;

/**
 * XPath predicates for selecting elements that are "blank" vs "non-blank"
 * from a UI text/value perspective.
 *
 * Definition used here:
 *  - "Blank" means: (self + descendants) contain no non-whitespace visible text nodes
 *    AND (self + descendants) have no @value OR only @value that is null/empty/blank.
 *  - "Non-blank" is the inverse: there exists (self + descendants) either
 *    a non-whitespace text node OR a @value with non-blank content.
 *
 * Notes:
 *  - XPath cannot see JS properties like element.value unless it is reflected into @value.
 *  - We also treat "null" (case-insensitive) in @value as blank, because you asked for it.
 */
public final class BlankElementConditions {

    private BlankElementConditions() {
        // utility class
    }

    /**
     * Matches elements whose self-or-descendants have:
     *  - no non-whitespace text node, AND
     *  - no non-blank @value (treating "", whitespace, and "null" as blank).
     */
    public static XPathy blankElement() {
        String base = new XPathy().getXpath(); // "//*"

        // Any text node (self or descendants) that has non-whitespace content
        // normalize-space(.) != ''  => contains something besides whitespace
        String hasAnyNonBlankText =
                "descendant-or-self::node()[self::text() and normalize-space(.) != '']";

        // Any element (self or descendants) with a @value attribute that is non-blank
        // and not the literal string "null" (any casing).
        String hasAnyNonBlankValueAttr =
                "descendant-or-self::*[@" +
                        "value and " +
                        "normalize-space(@value) != '' and " +
                        "translate(normalize-space(@value), " +
                        "  'ABCDEFGHIJKLMNOPQRSTUVWXYZ', " +
                        "  'abcdefghijklmnopqrstuvwxyz'" +
                        ") != 'null'" +
                        "]";

        String pred =
                "not(" + hasAnyNonBlankText + ") and " +
                        "not(" + hasAnyNonBlankValueAttr + ")";

        return new XPathy(base + "[" + pred + "]");
    }

    /**
     * Matches elements whose self-or-descendants have either:
     *  - a non-whitespace text node, OR
     *  - a non-blank @value attribute (treating "", whitespace, and "null" as blank).
     */
    public static XPathy nonBlankElement() {
        String base = new XPathy().getXpath(); // "//*"

        String hasAnyNonBlankText =
                "descendant-or-self::node()[self::text() and normalize-space(.) != '']";

        String hasAnyNonBlankValueAttr =
                "descendant-or-self::*[@" +
                        "value and " +
                        "normalize-space(@value) != '' and " +
                        "translate(normalize-space(@value), " +
                        "  'ABCDEFGHIJKLMNOPQRSTUVWXYZ', " +
                        "  'abcdefghijklmnopqrstuvwxyz'" +
                        ") != 'null'" +
                        "]";

        String pred =
                "(" + hasAnyNonBlankText + ") or (" + hasAnyNonBlankValueAttr + ")";

        return new XPathy(base + "[" + pred + "]");
    }
}
