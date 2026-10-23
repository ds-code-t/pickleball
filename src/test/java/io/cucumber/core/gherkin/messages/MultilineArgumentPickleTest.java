package io.cucumber.core.gherkin.messages;

import io.cucumber.core.gherkin.Step;
import io.cucumber.messages.types.PickleStep;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.util.Reflect.getProperty;

class MultilineArgumentPickleTest {
    @Test
    void multiLineJsonValueParsesAsADocString() throws Exception {
        String json = "{\n  \"name\": \"Ada\",\n  \"ok\": true\n}";
        Step step = NGherkinFactory.createGherkinMessagesPickle("* ", "INFO: the body", json)
                .getSteps()
                .getFirst();
        assertEquals("INFO: the body", step.getText());
        PickleStep pickleStep = (PickleStep) getProperty(step, "pickleStep");
        String content = pickleStep.getArgument().orElseThrow().getDocString().orElseThrow().getContent();
        assertEquals(json, content.replace("\r\n", "\n"));
        assertTrue(NGherkinFactory.realGherkinArgument(step).contains("\"name\": \"Ada\""));
    }

    @Test
    void oneLineTableValueStaysATable() {
        Step step = NGherkinFactory.createGherkinMessagesPickle("* ", "INFO: the body", "| city |")
                .getSteps()
                .getFirst();
        assertEquals("INFO: the body", step.getText());
        String argument = NGherkinFactory.realGherkinArgument(step);
        assertTrue(argument.contains("city"), argument);
        assertFalse(argument.contains("\"\"\""));
    }
}
