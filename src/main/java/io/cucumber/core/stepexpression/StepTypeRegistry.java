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
import io.pickleball.customtypes.EvalExpression;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                "dynamicStep",                // Name of the parameter type
                ".*",
                DynamicStep.class,            // Target class
                (String[] args) -> new DynamicStep(
                        args[0]
                )
        ));

        // Register custom ParameterType for EvalExpression
        parameterTypeRegistry.defineParameterType(new ParameterType<>(
                "evalExpression",                // Name of the parameter type
                ".*",
                EvalExpression.class,            // Target class
                (String[] args) -> new EvalExpression(
                        args[0]
                )
        ));

        final String QUOTED_STRING_REGEX = "\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*'|`(?:\\\\.|[^`])*`";

        parameterTypeRegistry.defineParameterType(new ParameterType<>(
                "quotedString",                                      // Name of the parameter type
                QUOTED_STRING_REGEX, // Regex with no capturing groups
                String.class,                                        // Target class
                (String input) -> {
                    char quote = input.charAt(0);                    // Get the opening quote character
                    String content = input.substring(1, input.length() - 1); // Remove surrounding quotes
                    return content.replace("\\" + quote, String.valueOf(quote)) // Unescape quotes for double and single
                            .replace("\\\\", "\\");             // Unescape backslashes
                }
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
                    Matcher matcher = Pattern.compile(QUOTED_STRING_REGEX).matcher(innerContent);
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
