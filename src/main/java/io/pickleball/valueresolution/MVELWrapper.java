package io.pickleball.valueresolution;

import io.cucumber.core.backend.Status;
import io.pickleball.exceptions.PickleballException;
import org.mvel2.MVEL;
import org.mvel2.ParserContext;


import java.util.Map;

import static io.pickleball.cacheandstate.PrimaryScenarioData.getCurrentScenarioStatus;
import static io.pickleball.cacheandstate.PrimaryScenarioData.getPrimaryScenarioStatus;
import static io.pickleball.configs.Constants.*;
import static io.pickleball.stringutilities.ObjectTransformer.transformUntilStable;


public class MVELWrapper extends ParseTransformer {

    static {
        String stateRegex = "(?<scenarioState>(?:(?:(?:HARD|SOFT)\\s+)?FAIL)|PASS)[EDSING]*";
        addGlobalTransformation(
                "\\b(?<scenarioType>" + SCENARIO + "|" + TEST + ")\\s+(?:(?<currently>CURRENTLY)\\s+)?" + stateRegex,
                match -> {
                    String scenarioType = match.group("scenarioType");
                    String scenarioState = match.group("scenarioState").replaceAll("\\s+", " ");
                    Status status = scenarioType.equals(TEST) ? getPrimaryScenarioStatus() : getCurrentScenarioStatus();

                    boolean returnVal = switch (scenarioState) {
                        case "FAIL" -> (status.equals(Status.FAILED) || status.equals(Status.SOFT_FAILED));

                        case "SOFT FAIL" -> status.equals(Status.SOFT_FAILED);

                        case "HARD FAIL" -> status.equals(Status.FAILED);

                        case "PASS" -> (status.equals(Status.PASSED));

                        default -> throw new IllegalStateException("Unexpected value: " + scenarioState);
                    };

                     return String.valueOf(returnVal);
                }

        );
    }

    private final ParserContext context;

    public MVELWrapper() {
        context = new ParserContext();
        context.setStrictTypeEnforcement(false);
        context.setStrongTyping(false);
    }


    public Object evaluate(String expression, Map<String, Object> variables) {
        try {
            return transformUntilStable(expression, exp -> evaluateOnce(String.valueOf(exp), variables));
        }
        catch (Exception e)
        {
            if(e.getMessage().toLowerCase().contains("mvel"))
            {
                throw new PickleballException("MVEL2 failed to evaluate expression '" + expression + "'", e);
            }
            throw new RuntimeException(e);
        }
    }

    public Object evaluateOnce(String expression, Map<String, Object> variables) {
        String preprocessedString = String.valueOf(transformUntilStable(expression, exp -> preprocess(String.valueOf(exp))));
        return transformUntilStable(preprocessedString, exp -> evaluateExpression(String.valueOf(exp), variables));
    }


    public Object evaluateExpression(String expression, Map<String, Object> variables) {


            return MVEL.eval(preprocess(expression), context, variables);

    }

    public String preprocess(String expression) {
        return transform(expression);
    }


}
