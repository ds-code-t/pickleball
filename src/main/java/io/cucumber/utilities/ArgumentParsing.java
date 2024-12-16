package io.cucumber.utilities;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ArgumentParsing {

    public static String[] convertHashMapToArgv(Map<String, Object> arguments) {
        List<String> argv = new ArrayList<>();

        arguments.forEach((key, value) -> {
            if (key.equalsIgnoreCase("features")) {
                // Positional argument for feature paths
                argv.add(quoteIfNecessary(value.toString()));
            } else {
                // Default behavior for most arguments: "--keyname value"
                argv.add("--" + key);
                argv.add(quoteIfNecessary(value.toString()));
            }
        });

        return argv.toArray(new String[0]);
    }

    public static String quoteIfNecessary(String value) {
        // Add quotes only if the value contains spaces or special characters
        if (value.contains(" ") || value.contains("\"")) {
            return "\"" + value.replace("\"", "\\\"") + "\""; // Escape inner quotes
        }
        return value;
    }

    public static String[] convertCommandLineToArgv(String commandLine) {
        // Split the command line by spaces, preserving quoted arguments
        return commandLine.split(" (?=([^\"]*\"[^\"]*\")*[^\"]*$)");
    }

}
