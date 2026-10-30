package tools.dscode.common.treeparsing.preparsing;

import io.cucumber.core.runner.StepBase;
import tools.dscode.common.assertions.AssertionChain;
import tools.dscode.common.mappings.QuoteParser;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.cucumber.core.runner.StepBase.getInheritancePhrase;
import static tools.dscode.common.GlobalConstants.BOOK_END;
import static tools.dscode.common.GlobalConstants.META_TEXT_SEPARATOR;
import static tools.dscode.common.treeparsing.RegexUtil.stripObscureNonText;

public abstract class LineData implements Cloneable {
    public boolean isBlockConditionalStep;
    public int lineConditionalMode = 1;
    public int startPhraseIndex = 0;
    //    public LineData inheritedLineData;
    //    public List<PhraseData> contextPhrases = new ArrayList<>();
    //    public List<List<PhraseData>> inheritedContextPhrases = new ArrayList<>();
    private final String original;
    public String runningText = ", ";
    private final QuoteParser qp;
    private final BracketMasker bm;
    private final String fullyMasked;
    public final List<PhraseData> phrases = new ArrayList<>();
    public final List<PhraseData> executedPhrases = new ArrayList<>();
    private static final Set<Character> DELIMITERS = Set.of(',', ';', ':', '.', '!', '?');
    //    public final List<PhraseData> contextPhrases = new ArrayList<>();
    public List<PhraseData> inheritancePhrases = new ArrayList<>();
    public PhraseData inheritedPhrase;
    public int inheritedConditionalState;
    public int previousSiblingConditionalState = 1;
    public StepBase stepExtension;
    private boolean deferConditionalChain;


    public void setInheritance(StepBase currentStep) {
        stepExtension = currentStep;

        previousSiblingConditionalState = nearestSiblingConditionalState(currentStep.previousSibling);
        StepBase parentStep = currentStep.parentStep;
        inheritedPhrase = getInheritancePhrase(parentStep);
        inheritedConditionalState = inheritedPhrase == null ? 1 : inheritedPhrase.phraseConditionalMode;
        currentStep.logAndIgnore =
                inheritedConditionalState < 1
                        || (parentStep != null && parentStep.logAndIgnore);

        if(inheritedPhrase != null) {
            inheritedPhrase.setPhraseParsingMap(currentStep.getStepParsingMap());
        }
    }

    /**
     * A plain step has no inheritance phrase. ELSE still belongs to the nearest
     * earlier sibling that does, so a step between a false IF and ELSE does not
     * hide that IF.
     */
    private static int nearestSiblingConditionalState(StepBase sibling) {
        while (sibling != null) {
            PhraseData phrase = getInheritancePhrase(sibling);
            if (phrase != null) {
                return phrase.phraseConditionalMode;
            }
            PhraseData tail = singleLineConditionalTail(sibling);
            if (tail != null) {
                return tail.phraseConditionalMode;
            }
            sibling = sibling.previousSibling;
        }
        return 1;
    }

    /**
     * A single-line {@code IF: ... THEN: ...} (or separate-line ELSE-IF) has no
     * block inheritance phrase, but its last executed phrase carries the chain
     * state exactly as an inline ELSE on the same line would see it.
     */
    static PhraseData singleLineConditionalTail(StepBase sibling) {
        if (sibling == null || sibling.lineData == null) return null;
        List<PhraseData> executed = sibling.lineData.executedPhrases;
        if (executed.isEmpty()) return null;
        String first = executed.getFirst().getConditional();
        first = first == null ? "" : first.trim().toLowerCase();
        if (!first.startsWith("if") && !first.startsWith("else")) return null;
        return executed.getLast();
    }

    public List<String> lineComponents = new ArrayList<>();

    @Override
    public String toString() {
        return this.runningText;
    }

    public abstract PhraseData runPhraseFromLine(PhraseData phrase);

    public LineData(String input) {
        input = stripObscureNonText(Objects.requireNonNull(input, "input"));
        input = protectMultilineComparisonOperands(input);
        // Ensure non-blank input ends with a delimiter
        if (!input.isBlank()) {
            String t = input.stripTrailing();
            char last = t.charAt(t.length() - 1);
            if (!DELIMITERS.contains(last)) {
                input = t + "."; // note: uses stripped version so you don't keep trailing whitespace
            } else {
                input = t; // optional: normalize away trailing whitespace consistently
            }
        }
//        input = wrapLooseConditionalExpression(input);

        this.original = input;

        // 1) Mask quotes first
        this.qp = new QuoteParser(input);
        String afterQuotes = qp.masked();

        // 2) Mask brackets second
        this.bm = getBracketMasker(afterQuotes);
        this.fullyMasked = bm.masked();

        if (!original.startsWith(",")) return;

//        String preParsedNormalized = preParseDynamicStepString(fullyMasked);
        String preParsedNormalized = fullyMasked;

        StringBuilder buf = new StringBuilder();

        int sentenceCount = 0;
        for (int i = 0; i < preParsedNormalized.length(); i++) {
            char c = preParsedNormalized.charAt(i);
            if (isDelimiterAt(preParsedNormalized, i)) {
                String chunk = buf.toString();
                String unmasked = qp.restoreFromWithOuterBookend(bm.restoreFrom(chunk), BOOK_END);
                if (!unmasked.isBlank()) {
                    addPhrase(unmasked, c, sentenceCount);
                }
                buf.setLength(0);
                if (c != ',' && c != ';') sentenceCount++;
            } else {
                buf.append(c);
            }
        }

        PhraseData lastPhrase = null;
        for (PhraseData phrase : this.phrases) {
            if (lastPhrase != null) {
                phrase.setPreviousPhrase(lastPhrase);
                lastPhrase.setNextPhrase(phrase);
            }
            lastPhrase = phrase;
        }
    }

    private static final Pattern LEADING_COMPARISON_VERB = Pattern.compile(
            "^\\s*(?:,\\s*)?(?:(?:and|or|the|then)\\s+)*(?:ensure|verify|until|if)\\b\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPARISON_OPERATOR = Pattern.compile(
            "(?i)\\b(?:starts?\\s+with|ends?\\s+with|less\\s+than\\s+or\\s+equals?|greater\\s+than\\s+or\\s+equals?|contains?|match(?:es)?|equals?|less\\s+than|greater\\s+than)\\b");
    private static final Pattern TRAILING_DOES_NOT = Pattern.compile(
            "(?i)\\b(?:does\\s+not|do\\s+not)\\s*$");
    private static final Pattern TRAILING_NOT = Pattern.compile(
            "(?i)\\b(?:not|no)\\s*$");

    /**
     * A comma phrase with a line break quotes an operand that is not already one
     * quoted span, so {@code contains} sees the whole value. A step with no line
     * break, and a step that does not start with a comma, is returned unchanged.
     */
    public static String protectMultilineComparisonOperands(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        if (input.indexOf('\n') < 0 && input.indexOf('\r') < 0) {
            return input;
        }
        if (!input.stripLeading().startsWith(",")) {
            return input;
        }
        List<String> parts = splitTopLevelCommas(input);
        boolean changed = false;
        for (int i = 0; i < parts.size(); i++) {
            String updated = protectComparisonSegment(parts.get(i));
            if (!updated.equals(parts.get(i))) {
                parts.set(i, updated);
                changed = true;
            }
        }
        return changed ? String.join(",", parts) : input;
    }

    private static String protectComparisonSegment(String segment) {
        if (segment.indexOf('\n') < 0 && segment.indexOf('\r') < 0) {
            return segment;
        }
        int[] operator = lastComparisonOperator(segment);
        if (operator == null) {
            return segment;
        }
        int leftStart = leadingVerbEnd(segment);
        if (leftStart > operator[0]) {
            return segment;
        }
        String leftTrim = segment.substring(leftStart, operator[0]).trim();
        String rightTrim = segment.substring(operator[1]).trim();
        if (leftTrim.isEmpty() || rightTrim.isEmpty()) {
            return segment;
        }
        String negation = "";
        Matcher doesNot = TRAILING_DOES_NOT.matcher(leftTrim);
        if (doesNot.find()) {
            negation = leftTrim.substring(doesNot.start()).trim();
            leftTrim = leftTrim.substring(0, doesNot.start()).trim();
        } else {
            Matcher not = TRAILING_NOT.matcher(leftTrim);
            if (not.find()) {
                String before = leftTrim.substring(0, not.start()).trim();
                if (isWholeQuotedSpan(before)) {
                    negation = leftTrim.substring(not.start()).trim();
                    leftTrim = before;
                }
            }
        }
        if (leftTrim.isEmpty()) {
            return segment;
        }
        String quotedLeft = quoteOperand(leftTrim);
        String quotedRight = quoteOperand(rightTrim);
        if (quotedLeft.equals(leftTrim) && quotedRight.equals(rightTrim)) {
            return segment;
        }
        String operatorText = segment.substring(operator[0], operator[1]);
        StringBuilder rebuilt = new StringBuilder();
        rebuilt.append(segment, 0, leftStart);
        rebuilt.append(quotedLeft);
        if (!negation.isEmpty()) {
            rebuilt.append(' ').append(negation);
        }
        rebuilt.append(' ').append(operatorText).append(' ').append(quotedRight);
        return rebuilt.toString();
    }

    private static int leadingVerbEnd(String segment) {
        Matcher verb = LEADING_COMPARISON_VERB.matcher(segment);
        return verb.find() ? verb.end() : 0;
    }

    private static int[] lastComparisonOperator(String segment) {
        boolean[] quoted = quotedMask(segment);
        Matcher matcher = COMPARISON_OPERATOR.matcher(segment);
        int start = -1;
        int end = -1;
        while (matcher.find()) {
            if (!quoted[matcher.start()]) {
                start = matcher.start();
                end = matcher.end();
            }
        }
        return start < 0 ? null : new int[] {start, end};
    }

    private static boolean[] quotedMask(String text) {
        boolean[] inside = new boolean[text.length()];
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if ((current == '"' || current == '\'' || current == '`') && !isEscaped(text, index)) {
                int next = endOfQuotedSpan(text, index);
                if (next > index) {
                    for (int cursor = index; cursor < next; cursor++) {
                        inside[cursor] = true;
                    }
                    index = next;
                    continue;
                }
            }
            index++;
        }
        return inside;
    }

    private static boolean isWholeQuotedSpan(String text) {
        return text.length() >= 2 && endOfQuotedSpan(text, 0) == text.length();
    }

    private static int endOfQuotedSpan(String text, int open) {
        if (open < 0 || open >= text.length() || isEscaped(text, open)) {
            return -1;
        }
        if (text.startsWith("'''", open)) {
            int index = open + 3;
            while (index < text.length()) {
                if (blocksMultilineQuote(text.charAt(index))) {
                    return -1;
                }
                if (text.startsWith("'''", index) && backslashCount(text, index, open + 3) % 2 == 0) {
                    return index + 3;
                }
                index++;
            }
            return -1;
        }
        char quote = text.charAt(open);
        if (quote != '"' && quote != '\'' && quote != '`') {
            return -1;
        }
        int index = open + 1;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (blocksMultilineQuote(current)) {
                return -1;
            }
            if (current == quote && backslashCount(text, index, open + 1) % 2 == 0) {
                return index + 1;
            }
            index++;
        }
        return -1;
    }

    private static boolean blocksMultilineQuote(char current) {
        return current == '\u0085' || current == '\u2028' || current == '\u2029';
    }

    private static boolean isEscaped(String text, int index) {
        return backslashCount(text, index, 0) % 2 == 1;
    }

    private static int backslashCount(String text, int index, int minIndex) {
        int count = 0;
        int cursor = index - 1;
        while (cursor >= minIndex && text.charAt(cursor) == '\\') {
            count++;
            cursor--;
        }
        return count;
    }

    private static String quoteOperand(String operand) {
        if (isWholeQuotedSpan(operand)) {
            return operand;
        }
        return "\"" + operand.replace("\"", "\\\"") + "\"";
    }

    private static List<String> splitTopLevelCommas(String input) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (index < input.length()) {
            char current = input.charAt(index);
            if ((current == '"' || current == '\'' || current == '`') && !isEscaped(input, index)) {
                int next = endOfQuotedSpan(input, index);
                if (next > index) {
                    index = next;
                    continue;
                }
            }
            if (current == ',') {
                parts.add(input.substring(start, index));
                start = index + 1;
                index++;
                continue;
            }
            index++;
        }
        parts.add(input.substring(start));
        return parts;
    }

    private static boolean isDelimiterAt(String s, int index) {
        char c = s.charAt(index);

        if (!DELIMITERS.contains(c)) {
            return false;
        }
        if (c == ',')
            return true;

        return index == s.length() - 1
                || Character.isWhitespace(s.charAt(index + 1));
    }

    public static String wrapLooseConditionalExpression(String input) {
        QuoteParser qp = new QuoteParser(input);
        BracketMasker bm = getBracketMasker(qp.masked());

        String masked = bm.masked().trim();
        if (!containsConditionalOperator(masked)) return input;

        String replaced = masked.trim().replaceAll(
                "^((?:,\\s+)?(?:(?:and|or|the|then)\\b)*\\s*(?:(?:else\\b\\s*)?if\\b|until\\b|ensures?\\b|verify\\b|verfies\\b)?(?:\\s+the)?)(.*)$",
                "$1 <{ $2 }> "
        );

        return qp.restoreFrom(bm.restoreFrom(replaced));
    }

    private static boolean containsConditionalOperator(String masked) {
        masked = masked.replaceAll("\\b[A-Z]+:::.*$", "");
        return masked.contains("=")
                || masked.contains("!")
                || masked.contains("&")
                || masked.contains("|")
                || masked.contains(">")
                || masked.contains("<");
    }

    public void addPhrase(String phraseText, char termination, int lineComponentIndex) {
        String phraseString = phraseText.replace(BOOK_END, "") + termination;
        if (lineComponentIndex == 0) {
            boolean defer = deferConditionalChain || opensConditionalChain(phraseText);
            phrases.add(new Phrase(phraseText, termination, this, null, !defer));
            if (defer) {
                deferConditionalChain = true;
            }
            runningText += phraseString;
        } else {
            if (lineComponents.size() < lineComponentIndex) {
                lineComponents.add(", " + phraseString);
            } else {
                lineComponents.set(
                        lineComponentIndex - 1,
                        lineComponents.get(lineComponentIndex - 1).concat(phraseText + " " + termination)
                );
            }
        }
    }

    /**
     * A conditional phrase, and every later comma phrase in that sentence,
     * is constructed without evaluating mappings, expressions, or $ functions.
     */
    static boolean opensConditionalChain(String phraseText) {
        String stripped = stripConditionalMeta(phraseText);
        return stripped.matches("(?is)^(?:(?:and|or|the)\\b\\s*)*(?:then\\b\\s*)?(?:else\\s+if|else|if|until)\\b.*")
                || stripped.matches("(?is)^then\\b.*");
    }

    private static String stripConditionalMeta(String phraseText) {
        if (phraseText == null || phraseText.isBlank()) {
            return "";
        }
        String stripped = phraseText;
        int start = stripped.indexOf(META_TEXT_SEPARATOR);
        while (start >= 0) {
            int end = stripped.indexOf(META_TEXT_SEPARATOR, start + META_TEXT_SEPARATOR.length());
            if (end < 0) {
                break;
            }
            stripped = stripped.substring(0, start) + stripped.substring(end + META_TEXT_SEPARATOR.length());
            start = stripped.indexOf(META_TEXT_SEPARATOR);
        }
        return stripped.trim();
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
     * Delimiters used for splitting.
     */
    public static Set<Character> delimiters() {
        return DELIMITERS;
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
            copy.inheritancePhrases = new ArrayList<>();
            if (this.inheritedPhrase != null) {
                copy.inheritedPhrase = this.inheritedPhrase.cloneInheritedPhrase();
            }
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("Clone should be supported", e);
        }
    }

    public static BracketMasker getBracketMasker(String input) {
        return new BracketMasker(input);
    }

    public abstract void runPhrases();
}