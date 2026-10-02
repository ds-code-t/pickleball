package io.cucumber.core.gherkin.messages;

import io.cucumber.core.gherkin.Step;
import io.cucumber.messages.types.PickleStep;
import org.junit.jupiter.api.Test;
import tools.dscode.common.treeparsing.preparsing.ParsedLine;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.util.Reflect.getProperty;

class ConditionalArgumentPickleTest {
    private static final String DEFAULT_TABLE = "      | city |\n      | Tempe |";
    private static final String DEFAULT_DOC = "      \"\"\"\n      hello from the if line\n      \"\"\"";

    @Test
    void realArgumentIgnoresAnInlineTableSoBranchesDoNotShareIt() throws Exception {
        Step parent = NGherkinFactory.createGherkinMessagesPickle(
                "* ",
                "IF: ready THEN: enter one ELSE: enter two DT:::city|Paris|",
                DEFAULT_TABLE).getSteps().getFirst();
        PickleStep pickleStep = (PickleStep) getProperty(parent, "pickleStep");
        assertEquals("DT", inline(pickleStep, "getInlineArgumentType"));
        assertEquals("city|Paris|", inline(pickleStep, "getInlineArgumentText"));
        assertFalse(parent.getText().contains("DT:::"));

        String realArgument = NGherkinFactory.realGherkinArgument(parent);
        assertTrue(realArgument.contains("Tempe"));
        assertFalse(realArgument.contains("Paris"));
        assertTrue(NGherkinFactory.getGherkinArgumentText(parent).contains("Paris"));

        String restored = parent.getText() + " " + inline(pickleStep, "getInlineArgumentType")
                + ":::" + inline(pickleStep, "getInlineArgumentText");
        assertTrue(restored.endsWith("DT:::city|Paris|"));
        assertTrue(restored.contains("enter one"));
        assertFalse(restored.substring(0, restored.indexOf("ELSE:")).contains("DT:::"));
    }

    @Test
    void innerStepInheritsADocStringAndOnlyDtBecomesADataTable() throws Exception {
        Step parent = NGherkinFactory.createGherkinMessagesPickle(
                "* ",
                "IF: ready THEN: enter the note",
                DEFAULT_DOC).getSteps().getFirst();
        String inherited = NGherkinFactory.realGherkinArgument(parent);
        assertTrue(inherited.contains("hello from the if line"));

        Step child = NGherkinFactory.createGherkinMessagesPickle(
                "* ",
                "enter the note",
                ParsedLine.gherkinArgumentForBranch("enter the note", inherited)).getSteps().getFirst();
        assertTrue(NGherkinFactory.realGherkinArgument(child).contains("hello from the if line"));

        Step noted = NGherkinFactory.createGherkinMessagesPickle(
                "* ",
                "enter the note NOTE:::hello|",
                inherited).getSteps().getFirst();
        PickleStep noteStep = (PickleStep) getProperty(noted, "pickleStep");
        assertEquals("enter the note", noted.getText());
        assertEquals("NOTE", inline(noteStep, "getInlineArgumentType"));
        assertEquals("hello|", inline(noteStep, "getInlineArgumentText"));
        assertTrue(NGherkinFactory.getGherkinArgumentText(noted).contains("hello from the if line"));
        assertFalse(NGherkinFactory.getGherkinArgumentText(noted).contains("NOTE:::"));

        Step overridden = NGherkinFactory.createGherkinMessagesPickle(
                "* ",
                "`enter the note DT:::city|Paris|`",
                ParsedLine.gherkinArgumentForBranch("`enter the note DT:::city|Paris|`", inherited))
                .getSteps().getFirst();
        PickleStep overriddenStep = (PickleStep) getProperty(overridden, "pickleStep");
        assertEquals("enter the note", overridden.getText());
        assertEquals("DT", inline(overriddenStep, "getInlineArgumentType"));
        assertEquals("city|Paris|", inline(overriddenStep, "getInlineArgumentText"));
        String table = NGherkinFactory.realGherkinArgument(overridden);
        assertTrue(table.contains("Paris"));
        assertFalse(table.contains("hello from the if line"));
    }

    private static String inline(PickleStep pickleStep, String method) throws Exception {
        Method getter = pickleStep.getClass().getMethod(method);
        return (String) getter.invoke(pickleStep);
    }
}
