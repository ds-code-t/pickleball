package tools.dscode.coredefinitions;

//import io.cucumber.java.en.Given;
//import tools.dscode.common.CoreSteps;
//import tools.dscode.extensions.StepExtension;
//import tools.dscode.extensions.StepData;
//
//import static io.cucumber.core.runner.ScenarioState.getScenarioState;

import io.cucumber.core.runner.StepExtension;
import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.annotations.DefinitionFlags;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.annotations.DefinitionFlag.NO_LOGGING;
import static tools.dscode.common.annotations.DefinitionFlag.IGNORE_CHILDREN_IF_FALSE;
import static tools.dscode.common.annotations.DefinitionFlag._NO_LOGGING;
import static tools.dscode.common.util.Reflect.getProperty;


public class ConditionalSteps extends CoreSteps {


    public enum ConditionalToken {
        IF("IF:"),
        ELSE_IF("ELSE-IF:"),  // order matters vs ELSE
        ELSE("ELSE:"),
        THEN("THEN:"),
        NONE("");             // indicates “no token / not applicable”

        private final String literal;

        ConditionalToken(String literal) {
            this.literal = literal;
        }

        public String literal() {
            return literal;
        }
    }

    public record TokenPart(ConditionalToken token, String text) {
    }

    // Put longer literals first to avoid ELSE matching inside ELSE-IF.
    private static final Pattern TOKEN =
            Pattern.compile("ELSE-IF:|ELSE:|IF:|THEN:");

    // Fast lookup from matched literal -> enum
    private static final Map<String, ConditionalToken> TOKEN_BY_LITERAL = buildTokenLookup();

    private static Map<String, ConditionalToken> buildTokenLookup() {
        Map<String, ConditionalToken> m = new java.util.HashMap<>();
        for (ConditionalToken t : ConditionalToken.values()) {
            if (!t.literal().isEmpty()) {
                m.put(t.literal(), t);
            }
        }
        return java.util.Collections.unmodifiableMap(m);
    }

//    @DefinitionFlags(_NO_LOGGING)
    @Given("^(?:IF:|ELSE:|ELSE-IF:).*$")
    public static void runConditional() {
        StepExtension currentStep = getRunningStep();
        currentStep.grandChildrenSteps.addAll(currentStep.childSteps);
        currentStep.childSteps.clear();

        String inputString = getProperty(getRunningStep().pickleStepTestStep, "unresolvedText").toString();

        List<TokenPart> parts = splitByTokens(inputString);
        StepExtension lastNonThenStep = null;
        String stepString = "";
        for (int i = 0; i < parts.size(); i++) {
            boolean last = i == parts.size() - 1;
            TokenPart part = parts.get(i);


            switch (part.token()) {
                case IF -> {
                    stepString += " , if " + part.text() + ":";
                }
                case ELSE_IF -> {
                    stepString += " , else if " + part.text() + ":";
                }
                case ELSE -> {
                    stepString += " , else " + ":";
                }
                case THEN -> {
                    stepString += part.text();
                }
                case NONE -> {
                    stepString += part.text();
                }
            }
            if(stepString.isBlank())
                continue;


            if (part.token() == ConditionalToken.THEN) {
                StepExtension modifiedStep = lastNonThenStep.modifyStepExtension(stepString);
                lastNonThenStep.addChildStep(modifiedStep);

            } else {
                StepExtension modifiedStep = currentStep.modifyStepExtension(stepString);
//                modifiedStep.addDefinitionFlag(NO_LOGGING);
//                modifiedStep.addDefinitionFlag(IGNORE_CHILDREN_IF_FALSE);

                currentStep.addChildStep(modifiedStep);
                if (lastNonThenStep != null) {
                    modifiedStep.previousSibling = lastNonThenStep;
                    lastNonThenStep.nextSibling = modifiedStep;
                }
                lastNonThenStep = modifiedStep;
                if (part.token() == ConditionalToken.ELSE) {
                    StepExtension modifiedStep2 = currentStep.modifyStepExtension(part.text());
                    modifiedStep2.addDefinitionFlag(IGNORE_CHILDREN_IF_FALSE);
                    lastNonThenStep.addChildStep(modifiedStep2);
                }
            }
            stepString = "";
        }
    }

    static List<TokenPart> splitByTokens(String s) {
        Matcher m = TOKEN.matcher(s);
        List<TokenPart> out = new ArrayList<>();

        // You said it always starts with a token, enforce that.
        if (!m.find() || m.start() != 0) {
            throw new IllegalArgumentException("Expected string to start with IF:/ELSE-IF:/ELSE:/THEN:");
        }

        while (true) {
            String tokLit = m.group();
            ConditionalToken tok = TOKEN_BY_LITERAL.getOrDefault(tokLit, ConditionalToken.NONE);

            int tokEnd = m.end();

            if (m.find()) {
                int nextTokStart = m.start();
                out.add(new TokenPart(tok, s.substring(tokEnd, nextTokStart)));
            } else {
                out.add(new TokenPart(tok, s.substring(tokEnd)));
                break;
            }
        }

        return out;
    }
}
