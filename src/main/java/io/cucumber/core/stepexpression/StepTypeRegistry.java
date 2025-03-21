/*
 * This file incorporates work covered by the following copyright and permission notice:
 *
 * Copyright (c) Cucumber Ltd
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.cucumber.core.stepexpression;

import io.cucumber.cucumberexpressions.ParameterByTypeTransformer;
import io.cucumber.cucumberexpressions.ParameterType;
import io.cucumber.cucumberexpressions.ParameterTypeRegistry;
import io.cucumber.datatable.DataTableType;
import io.cucumber.datatable.DataTableTypeRegistry;
import io.cucumber.datatable.TableCellByTypeTransformer;
import io.cucumber.datatable.TableEntryByTypeTransformer;
import io.cucumber.docstring.DocStringType;
import io.cucumber.docstring.DocStringTypeRegistry;
import io.pickleball.customtypes.DynamicStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import static io.pickleball.configs.Constants.*;
import static io.pickleball.stringutilities.QuoteExtracter.QUOTED_STRING_REGEX;
import static io.pickleball.valueresolution.BooleanResolver.resolveObjectToBoolean;

public final class StepTypeRegistry implements io.cucumber.core.api.TypeRegistry {

    private final ParameterTypeRegistry parameterTypeRegistry;
    private final DataTableTypeRegistry dataTableTypeRegistry;
    private final DocStringTypeRegistry docStringTypeRegistry;

    public StepTypeRegistry(Locale locale) {
        parameterTypeRegistry = new ParameterTypeRegistry(locale);
        dataTableTypeRegistry = new DataTableTypeRegistry(locale);
        docStringTypeRegistry = new DocStringTypeRegistry();

        // Register custom ParameterType for dynamicStep
        parameterTypeRegistry.defineParameterType(new ParameterType<>(
                "dynamicStep",
                ".*", // Regex with additional exclusion
                DynamicStep.class,            // Target class
                (String input) -> new DynamicStep(input)
        ));

        // Register custom ParameterType for dynamicStep
        parameterTypeRegistry.defineParameterType(new ParameterType<>(
                "stepReturn",
                ".*", // Regex with additional exclusion
                Object.class,            // Target class
                (String input) -> DynamicStep.runStep(input)
        ));


        final String component = "[^" + orSubstitue + "]*";
        final String IF = orSubstitue + "@IF:" + orSubstitue;
        final String ELSE = orSubstitue + "@ELSE:" + orSubstitue;
        final String ELSEIF = orSubstitue + "@ELSE-IF:" + orSubstitue;
        final String THEN = orSubstitue + "@THEN:" + orSubstitue;
        final String conditionalStepRegex = "(?:(" + IF + ")\\s*(" + component + ")\\s*" + THEN + "\\s*(" + component + "))(?:\\s*(" + ELSEIF + ")\\s*(" + component + ")\\s*" + THEN + "\\s*(" + component + "))*(?:\\s*(" + ELSE + ")\\s*(" + component + "))?" + orSubstitue;
        parameterTypeRegistry.defineParameterType(new ParameterType<>(
                "ifElseConditional",                 // Name
                conditionalStepRegex,              // Regex
                DynamicStep.class,                 // Target class
                (String[] groups) -> {             // CaptureGroupTransformer
                    int groupNum = -1;
                    while (true) {
                        int currentGroupNum = ++groupNum;
                        if (currentGroupNum >= groups.length)
                            return null;
                        String currentGroup = groups[currentGroupNum];
                        if (currentGroup == null)
                            continue;
                        if (currentGroup.equals(ELSE)) {
                            return new DynamicStep(groups[++groupNum], groups[0]);
                        } else {
                            String conditional = groups[++groupNum];
                            String stepText = groups[++groupNum];
                            if (resolveObjectToBoolean(conditional)) {
                                return new DynamicStep(stepText, groups[0]);
                            }
                        }
                    }
                }
        ));

        final String ifConditionalStepRegex = IF + "\\s*(" + component + ")\\s*" + orSubstitue;
        parameterTypeRegistry.defineParameterType(new ParameterType<>(
                "ifConditional",                 // Name
                ifConditionalStepRegex,              // Regex
                Boolean.class,                 // Target class
                (String[] groups) -> {             // CaptureGroupTransformer
                    return resolveObjectToBoolean(groups[0]);
                }
        ));


        parameterTypeRegistry.defineParameterType(new ParameterType<>(
                "metaParameter",                 // Name
                "[A-Z-\\s]*",              // Regex
                String.class,                 // Target class
                (String input) -> input
        ));


        final String RUN_REGEX = orSubstitue + "\\s*(NEXT-STEPS|STEP)\\s+(AFTER|IF)\\s+(?:(?:(" + SCENARIO + "|" + TEST + ")\\s+(" + String.join("|", STATE_LIST) + "))|(" + component + "))\\s+" + orSubstitue;
        //  @RUN: | NEXT-STEPS AFTER TEST ENDED |
        parameterTypeRegistry.defineParameterType(new ParameterType<>(
                "runCondition",                                      // Name of the parameter type
                RUN_REGEX, // Regex with no capturing groups
                String.class,                                        // Target class
                (String[] groups) -> {
                    String runObject = groups[0];
                    String timeConditional = groups[1];
                    String scenarioType = groups[2];
                    String scenarioStatus = groups[3];
                    String generalConditional = groups[4];

                    return null;

                }
        ));


        // Register custom ParameterType for EvalExpression
        parameterTypeRegistry.defineParameterType(new ParameterType<>(
                "booleanEvaluator",                // Name of the parameter type
                ".*",                              // Regex pattern
                Boolean.class,                      // Input type
                (String input) -> resolveObjectToBoolean(input) // Transformer returning Boolean
        ));
        parameterTypeRegistry.defineParameterType(new ParameterType<>(
                "quotedString",                                      // Name of the parameter type
                QUOTED_STRING_REGEX.toString(), // Regex with no capturing groups
                String.class,                                        // Target class
                (String input) -> {
                    char quote = input.charAt(0);                    // Get the opening quote character
                    String content = input.substring(1, input.length() - 1); // Remove surrounding quotes
                    return content.replace("\\" + quote, String.valueOf(quote)) // Unescape quotes for double and single
                            .replace("\\\\", "\\");             // Unescape backslashes
                }
        ));


        parameterTypeRegistry.defineParameterType(new ParameterType<>(
                "metaStepAttribute",                                      // Name of the parameter type
                "\\s*\\b[A-Z]+[-A-Z]*\\b", // Regex with no capturing groups
                String.class,                                        // Target class
                (String input) -> input
        ));

        parameterTypeRegistry.defineParameterType(new ParameterType<>(
                "metaConjunction",                                      // Name of the parameter type
                "\\s*\\bIF|WHEN|ON|GO-TO\\b", // Regex with no capturing groups
                String.class,                                        // Target class
                (String input) -> input
        ));


// Define the reusable quotedString regex

// Define the stringList ParameterType
        parameterTypeRegistry.defineParameterType(new ParameterType<>(
                "stringList",                            // Name of the composite parameter type
                "\\[\\s*(?:" + QUOTED_STRING_REGEX + ")(?:\\s*,\\s*(?:" + QUOTED_STRING_REGEX + "))*\\s*\\]", // Embed quotedString regex
                List.class,                              // Target class
                (String input) -> {
                    if (input == null || input.equals("[]")) {
                        return List.of();                // Handle empty list
                    }
                    // Remove square brackets and parse inner content
                    String innerContent = input.substring(1, input.length() - 1).trim();
                    Matcher matcher = QUOTED_STRING_REGEX.matcher(innerContent);
                    List<String> result = new ArrayList<>();
                    while (matcher.find()) {
                        String match = matcher.group();
                        char quote = match.charAt(0);     // Get the opening quote character
                        String content = match.substring(1, match.length() - 1); // Remove quotes
                        content = content.replace("\\" + quote, String.valueOf(quote)) // Unescape the quote character
                                .replace("\\\\", "\\"); // Unescape backslashes
                        result.add(content);
                    }
                    return result;
                }
        ));


        parameterTypeRegistry.defineParameterType(new ParameterType<>(
                "whiteSpace",              // Name of the parameter type
                "\\s*",                     // Regex to match zero or more whitespaces
                String.class,               // Target class (or DynamicStep if needed)
                (String whiteSpace) -> whiteSpace // Transformer function
        ));


    }

    public ParameterTypeRegistry parameterTypeRegistry() {
        return parameterTypeRegistry;
    }

    public DataTableTypeRegistry dataTableTypeRegistry() {
        return dataTableTypeRegistry;
    }

    public DocStringTypeRegistry docStringTypeRegistry() {
        return docStringTypeRegistry;
    }

    @Override
    public void defineParameterType(ParameterType<?> parameterType) {
        parameterTypeRegistry.defineParameterType(parameterType);
    }

    @Override
    public void defineDocStringType(DocStringType docStringType) {
        docStringTypeRegistry.defineDocStringType(docStringType);
    }

    @Override
    public void defineDataTableType(DataTableType tableType) {
        dataTableTypeRegistry.defineDataTableType(tableType);
    }

    @Override
    public void setDefaultParameterTransformer(ParameterByTypeTransformer defaultParameterByTypeTransformer) {
        parameterTypeRegistry.setDefaultParameterTransformer(defaultParameterByTypeTransformer);
    }

    @Override
    public void setDefaultDataTableEntryTransformer(
            TableEntryByTypeTransformer defaultDataTableEntryByTypeTransformer
    ) {
        dataTableTypeRegistry.setDefaultDataTableEntryTransformer(defaultDataTableEntryByTypeTransformer);
    }

    @Override
    public void setDefaultDataTableCellTransformer(TableCellByTypeTransformer defaultDataTableByTypeTransformer) {
        dataTableTypeRegistry.setDefaultDataTableCellTransformer(defaultDataTableByTypeTransformer);
    }

}
