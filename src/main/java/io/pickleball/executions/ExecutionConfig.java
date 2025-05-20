package io.pickleball.executions;

import java.util.List;

import static io.pickleball.stringutilities.Constants.PRIORITY_TAG;

public class ExecutionConfig {

    public static int getPriority(List<String> tags) {
        return getPriority(tags, "");
    }
    public static int getPriority(List<String> tags, String locationString) {
        try {
            List<String> priorityTags = tags.stream()
                    .filter(tag -> tag.startsWith(PRIORITY_TAG))
                    .toList(); // Collect matching tags
           if(priorityTags.isEmpty())
               return 101;
            return Integer.parseInt(priorityTags.get(priorityTags.size()-1).substring(PRIORITY_TAG.length()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid priority tag format " + locationString, e);
        }
    }
}
