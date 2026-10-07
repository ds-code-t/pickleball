package tools.dscode.coredefinitions;

import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.control.ControlExecutionScope;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.treeparsing.preparsing.ParsedLine;

import java.util.regex.Pattern;

import static io.cucumber.core.runner.GlobalState.getRunningStep;

/**
 * Evaluates a reserved RUN-table cell with the block-conditional IF: phrase engine.
 * Blank, the word null, and a whole-cell reference that does not resolve are false
 * before that engine runs, because those inputs are not phrases.
 * A whole-cell reference that does resolve is quoted text, so {@code hello} follows
 * {@code IF: "hello"} and is not an element name. An expression cell such as
 * {@code <{ 1 }>} is left for that same IF engine.
 */
final class RunRowCondition {
    private static final Pattern SINGLE_REFERENCE = Pattern.compile("^<[^<>]+>$");

    private RunRowCondition() {
    }

    static boolean truthy(String cell) {
        String text = cell == null ? "" : cell.trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return false;
        }
        boolean resolvedReference = false;
        if (isWholeDataReference(text)) {
            String resolved = ParsingMap.getRunningParsingMap().resolveWholeText(text);
            if (unresolved(resolved)) {
                return false;
            }
            text = resolved.trim();
            resolvedReference = true;
        }
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.parseBoolean(text);
        }
        if (resolvedReference) {
            text = quoteLiteral(text);
        }
        return evaluateBlockCondition(text);
    }

    /**
     * {@code <{ 1 }>} is an expression, not a saved value. Other whole-cell
     * {@code <name>} references are saved text.
     */
    private static boolean isWholeDataReference(String text) {
        if (!SINGLE_REFERENCE.matcher(text).matches()) {
            return false;
        }
        return !(text.startsWith("<{") && text.endsWith("}>"));
    }

    private static String quoteLiteral(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean unresolved(String resolved) {
        if (resolved == null) {
            return true;
        }
        String trimmed = resolved.trim();
        return trimmed.isEmpty()
                || "null".equalsIgnoreCase(trimmed)
                || SINGLE_REFERENCE.matcher(trimmed).matches();
    }

    private static boolean evaluateBlockCondition(String cell) {
        StepExtension parent = getRunningStep();
        if (parent == null) {
            throw new IllegalStateException("A RUN row condition requires a running step");
        }
        StepExtension child = parent.createNewStepExtension("IF: " + cell);
        child.parentStep = parent;
        child.nextSibling = null;
        child.previousSibling = null;
        child.waitForPageReady = false;
        DynamicSteps dynamicSteps = new DynamicSteps();
        ControlExecutionScope.withStep(child, () -> {
            child.lineData = ParsedLine.createParsedLine(child);
            child.lineData.setInheritance(child);
            dynamicSteps.executeDynamicStep(cell);
            return null;
        });
        return conditionResult(child);
    }

    private static boolean conditionResult(StepExtension child) {
        if (child.lineData == null || child.lineData.phrases().isEmpty()) {
            throw new IllegalStateException(
                    "Run condition did not parse: " + child.getUnmodifiedText());
        }
        Boolean found = null;
        for (PhraseData phrase : child.lineData.phrases()) {
            found = later(found, booleanOf(phrase));
            PhraseData resolved = phrase.getResolvedPhrase();
            if (resolved != null && resolved != phrase) {
                found = later(found, booleanOf(resolved));
            }
        }
        for (PhraseData phrase : child.lineData.executedPhrases) {
            found = later(found, booleanOf(phrase));
        }
        if (found == null) {
            throw new IllegalStateException(
                    "Run condition did not evaluate to a boolean: " + child.getUnmodifiedText());
        }
        return found;
    }

    private static Boolean later(Boolean current, Boolean next) {
        return next == null ? current : next;
    }

    /**
     * The IF engine stores the boolean on the resolved phrase, often as the
     * assertion-chain status, after {@code resolvePhrase} copies the original.
     */
    private static Boolean booleanOf(PhraseData phrase) {
        if (phrase == null) {
            return null;
        }
        if (phrase.assertionChain != null && phrase.assertionChain.chainStatus != null) {
            return phrase.assertionChain.chainStatus;
        }
        if (phrase.result != null && phrase.result.value() instanceof Boolean value) {
            return value;
        }
        return null;
    }
}
