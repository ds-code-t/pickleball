package io.cucumber.core.gherkin.messages;

import io.cucumber.core.gherkin.Step;
import io.cucumber.java.en.Given;
import io.cucumber.messages.types.PickleStep;
import org.junit.jupiter.api.Test;
import tools.dscode.coredefinitions.LogAndAssertSteps;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.util.Reflect.getProperty;

class MultilineArgumentPickleTest {
    private static final String WINDOWS_PATH = "C:\\Users\\Dan She'll\\page.html";
    private static final String SPACED_PATH = "C:/Program Files/Pickleball/body.json";

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

    @Test
    void multiLineHtmlAndPegaJsonInsideInfoStepTextRoundTrip() throws Exception {
        String html = "<html>\n"
                + "<body>\n"
                + "<p>" + WINDOWS_PATH + "</p>\n"
                + "<p>" + SPACED_PATH + "</p>\n"
                + "Feature: not a feature\n"
                + "  Scenario: not a scenario\n"
                + "    * INFO: not a nested step\n"
                + "</body>\n"
                + "</html>";
        String info = "INFO: body=" + html;
        assertRoundTrip(info);
        assertLogBody(info, "body=" + html);

        String pega = "{\n"
                + "  \"pxObjClass\": \"Data-Admin-Operator-ID\",\n"
                + "  \"pyUserIdentifier\": \"admin\",\n"
                + "  \"pyFile\": \"C:\\\\Users\\\\Dan She'll\\\\page.json\",\n"
                + "  \"pySpaces\": \"" + SPACED_PATH + "\"\n"
                + "}";
        String traced = "TRACE: body=" + pega;
        assertRoundTrip(traced);
        assertLogBody(traced, "body=" + pega);
        assertNull(firstStep(traced, null).getArgument());
    }

    @Test
    void crlfSentinelAndOneLineWindowsPathsStayIntact() {
        String crlf = "WARN: body=first\r\n" + WINDOWS_PATH + "\r\n" + SPACED_PATH;
        assertRoundTrip(crlf);
        assertLogBody(crlf, "body=first\r\n" + WINDOWS_PATH + "\r\n" + SPACED_PATH);

        String sentinel = "DEBUG: keep \uE000 this\n" + WINDOWS_PATH;
        assertRoundTrip(sentinel);

        String oneLine = "INFO: saved " + WINDOWS_PATH + " and " + SPACED_PATH;
        Step step = firstStep(oneLine, null);
        assertEquals(oneLine, step.getText());
        assertFalse(step.getText().contains("\n"));
        assertFalse(step.getText().contains("\r"));
        assertLogBody(oneLine, "saved " + WINDOWS_PATH + " and " + SPACED_PATH);

        String hashed = "INFO: body=foo # not a comment\n# not a comment line\n" + WINDOWS_PATH;
        assertRoundTrip(hashed);
        String pipes = "INFO: body=|\n| city |\n| " + SPACED_PATH + " |";
        Step piped = firstStep(pipes, null);
        assertEquals(pipes, piped.getText());
        assertNull(piped.getArgument());
    }

    @Test
    void multiLineStepTextKeepsASeparateArgument() {
        String stepText = "INFO: body={\n  \"path\": \"" + WINDOWS_PATH.replace("\\", "\\\\") + "\"\n}";
        Step table = firstStep(stepText, "| city |");
        assertEquals(stepText, table.getText());
        String argument = NGherkinFactory.realGherkinArgument(table);
        assertTrue(argument.contains("city"), argument);
        assertFalse(argument.contains("\"\"\""), argument);

        String doc = "      \"\"\"\n      hello " + SPACED_PATH + "\n      \"\"\"";
        Step noted = firstStep("ERROR: see note\n" + WINDOWS_PATH, doc);
        assertEquals("ERROR: see note\n" + WINDOWS_PATH, noted.getText());
        assertTrue(NGherkinFactory.realGherkinArgument(noted).contains(SPACED_PATH));
        assertLogBody(noted.getText(), "see note\n" + WINDOWS_PATH);
    }

    @Test
    void assertPatternsAcceptRestoredMultiLineText() {
        String hard = "ASSERT: \"A\" equals \"A\"\n|| " + WINDOWS_PATH + " contains She'll";
        String soft = "SOFT ASSERT: 1 == 2\n| \"" + SPACED_PATH + "\" equals \"" + SPACED_PATH + "\"";
        assertRoundTrip(hard);
        assertRoundTrip(soft);
        assertMatches("hardAssert", hard, "\"A\" equals \"A\"\n|| " + WINDOWS_PATH + " contains She'll");
        assertMatches("softAssert", soft, "1 == 2\n| \"" + SPACED_PATH + "\" equals \"" + SPACED_PATH + "\"");
    }

    private static void assertRoundTrip(String stepText) {
        assertEquals(stepText, firstStep(stepText, null).getText());
    }

    private static Step firstStep(String stepText, String argument) {
        return NGherkinFactory.createGherkinMessagesPickle("* ", stepText, argument)
                .getSteps()
                .getFirst();
    }

    private static void assertLogBody(String stepText, String expectedBody) {
        assertMatches("authorLogOrFail", stepText, expectedBody);
    }

    private static void assertMatches(String methodName, String stepText, String expectedBody) {
        String pattern = patternOf(methodName);
        var matcher = Pattern.compile(pattern).matcher(stepText);
        assertTrue(matcher.matches(), pattern + " did not match " + stepText);
        assertEquals(expectedBody, matcher.group(matcher.groupCount()));
    }

    private static String patternOf(String methodName) {
        for (Method method : LogAndAssertSteps.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                Given given = method.getAnnotation(Given.class);
                if (given != null) {
                    return given.value();
                }
            }
        }
        throw new AssertionError("No @Given on " + methodName);
    }
}