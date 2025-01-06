package io.pickleball.valueresolution;

import io.cucumber.core.backend.Status;
import org.mvel2.MVEL;
import org.mvel2.ParserContext;


import java.util.Map;
import java.util.Set;

import static io.pickleball.cacheandstate.PrimaryScenarioData.getCurrentScenarioCompletionStatus;
import static io.pickleball.cacheandstate.PrimaryScenarioData.getPrimaryScenarioCompletionStatus;
import static io.pickleball.configs.Constants.*;
import static io.pickleball.stringutilities.ObjectTransformer.transformUntilStable;


public class MVELWrapper extends ParseTransformer {

    static {
        String stateRegex = "(?<scenarioState>(?:(?:(?:HARD|SOFT)\\s+)?FAIL)|END|PASS)[EDSING]*";
        addGlobalTransformation(
                "\\b(?<scenarioType>" + SCENARIO + "|" + TEST + ")\\s+(?:(?<currently>CURRENTLY)\\s+)?" + stateRegex,
                match -> {
                    String scenarioType = match.group("scenarioType");
                    String scenarioState = match.group("scenarioState");
                    String currently = match.group("currently");
                    Status[] completionStatus = scenarioType.equals(TEST) ? getPrimaryScenarioCompletionStatus() : getCurrentScenarioCompletionStatus();
                    boolean isCompleted = completionStatus[0].equals(Status.COMPLETED);

                    if (currently == null && !isCompleted)
                        return "false";

                    else if (scenarioState.equals("END"))
                        return String.valueOf(isCompleted);


                    if (scenarioState.equals("PASS"))
                        return String.valueOf(completionStatus[1].equals(Status.PASSED));
                    if (scenarioState.contains("FAIL")) {
                        if (scenarioState.contains("SOFT"))
                            return String.valueOf(completionStatus[1].equals(Status.SOFT_FAILED));
                        return String.valueOf(completionStatus[1].equals(Status.FAILED));
                    }

                    throw new RuntimeException("Failed to parse Scenario completion status");

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
        System.out.println("@@:evaluate -expression " + expression);
//        printCallStack();
        return transformUntilStable(expression, exp -> evaluateOnce(String.valueOf(exp), variables));
    }

    public Object evaluateOnce(String expression, Map<String, Object> variables) {
        String preprocessedString = String.valueOf(transformUntilStable(expression, exp -> preprocess(String.valueOf(exp))));
        return transformUntilStable(preprocessedString, exp -> evaluateExpression(String.valueOf(exp), variables));
    }


    public Object evaluateExpression(String expression, Map<String, Object> variables) {

        try {
            return MVEL.eval(preprocess(expression), context, variables);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    public String preprocess(String expression) {
        return transform(expression);
    }


}
