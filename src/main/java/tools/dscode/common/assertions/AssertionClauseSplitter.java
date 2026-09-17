package tools.dscode.common.assertions;

import tools.dscode.common.mappings.QuoteParser;
import tools.dscode.common.treeparsing.preparsing.BracketMasker;
import tools.dscode.common.treeparsing.preparsing.LineData;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits author-facing ASSERT / SOFT ASSERT payloads on a top-level single {@code |}.
 *
 * <p>{@code ||} remains the boolean OR operator inside one clause. Quoted text,
 * {@code <{...}>} expressions, and balanced parentheses are not split.
 */
public final class AssertionClauseSplitter {
    private AssertionClauseSplitter() {
    }

    public static List<String> split(String payload) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }

        QuoteParser quoteParser = new QuoteParser(payload);
        BracketMasker bracketMasker = LineData.getBracketMasker(quoteParser.masked());
        String masked = bracketMasker.masked();

        List<String> clauses = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < masked.length(); i++) {
            char character = masked.charAt(i);
            if (character == '|' && !isPartOfOrOperator(masked, i)) {
                addClause(clauses, quoteParser, bracketMasker, current.toString());
                current.setLength(0);
                continue;
            }
            current.append(character);
        }
        addClause(clauses, quoteParser, bracketMasker, current.toString());
        return List.copyOf(clauses);
    }

    private static boolean isPartOfOrOperator(String masked, int index) {
        return (index + 1 < masked.length() && masked.charAt(index + 1) == '|')
                || (index > 0 && masked.charAt(index - 1) == '|');
    }

    private static void addClause(
            List<String> clauses,
            QuoteParser quoteParser,
            BracketMasker bracketMasker,
            String maskedFragment
    ) {
        String restored = quoteParser.restoreFrom(bracketMasker.restoreFrom(maskedFragment)).trim();
        if (!restored.isBlank()) {
            clauses.add(restored);
        }
    }
}
