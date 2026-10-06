package tools.dscode.common.treeparsing.preparsing;

import org.junit.jupiter.api.Test;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LazyBranchResolutionTest {

    @Test
    void skippedThenAndLaterElseIfStayUnresolvedAtConstruction() throws Exception {
        ParsedLine skippedThen = parse(ParsedLine.rewriteConditionalBranches(
                "IF: \"<A>\" THEN: save \"<$DateTime:now <MISSING> to stamp>\""));
        String thenText = phraseText(skippedThen);
        assertTrue(thenText.contains("$DateTime"), thenText);
        assertTrue(thenText.contains("<MISSING>"), thenText);
        assertTrue(skippedThen.phrases().stream().allMatch(phrase -> phrase.deferEvaluations));
        assertTrue(skippedThen.phrases().stream().noneMatch(phrase -> phrase.evaluationsResolved));

        ParsedLine laterElseIf = parse(ParsedLine.rewriteConditionalBranches(
                "IF: <A> > 5 THEN: , save \"a\" ELSE-IF: <B> > 5 THEN: , save \"b\""));
        String elseIfText = phraseText(laterElseIf);
        assertTrue(elseIfText.contains("<{"), elseIfText);
        assertTrue(elseIfText.contains("<B>"), elseIfText);
        assertFalse(elseIfText.contains("DateTime"));
        PhraseData ifPhrase = laterElseIf.phrases().getFirst();
        PhraseData thenPhrase = laterElseIf.phrases().get(1);
        PhraseData elseIf = laterElseIf.phrases().stream()
                .filter(phrase -> phrase.getConditional() != null && phrase.getConditional().startsWith("else"))
                .findFirst()
                .orElseThrow();
        assertTrue(elseIf.getText().contains("<{"), elseIf.getText());
        assertTrue(elseIf.getText().contains("<B>"), elseIf.getText());
        ifPhrase.evaluationsResolved = true;
        ifPhrase.phraseConditionalMode = 1;
        thenPhrase.phraseConditionalMode = 1;
        assertTrue(elseIf.skipReferenceResolution(), elseIf.getConditional());
        assertFalse(elseIf.shouldResolveBranchReferences());

        ifPhrase.phraseConditionalMode = -1;
        thenPhrase.phraseConditionalMode = -1;
        assertFalse(elseIf.skipReferenceResolution(), elseIf.getConditional());
        assertTrue(elseIf.shouldResolveBranchReferences());
    }

    @Test
    void falseConditionDoesNotResolveItsRunStepBody() throws Exception {
        ParsedLine line = parse(ParsedLine.rewriteConditionalBranches(
                "IF: 1 == 0 THEN: save \"<$DateTime:now <MISSING> format: uuuu>\" ELSE: save \"kept\""));
        PhraseData condition = line.phrases().getFirst();
        condition.evaluationsResolved = true;
        condition.phraseConditionalMode = -1;
        PhraseData body = line.phrases().get(1);
        assertTrue(body.getText().contains("$DateTime"), body.getText());
        assertTrue(body.skipReferenceResolution(), body.getConditional() + " / " + body.getText());
        assertFalse(body.evaluationsResolved);
    }

    private static String phraseText(ParsedLine line) {
        return line.phrases().stream().map(PhraseData::getText).reduce("", (left, right) -> left + " | " + right);
    }

    private static ParsedLine parse(String input) throws Exception {
        Constructor<ParsedLine> constructor = ParsedLine.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(input);
    }
}
