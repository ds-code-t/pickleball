package io.cucumber.messages.types;

import java.util.regex.Pattern;

public final class InlinePickleArgument {

    private static final Pattern INLINE_ARGUMENT_MARKER = Pattern.compile("\\b([A-Z]+):::");

    private InlinePickleArgument() {
    }

    public static Extracted extract(String text) {
        if (text == null) {
            return null;
        }

        String strippedText = text.stripTrailing();
        if (!strippedText.endsWith("|")) {
            return null;
        }

        var matcher = INLINE_ARGUMENT_MARKER.matcher(strippedText);
        String type = null;
        int markerStart = -1;
        int markerEnd = -1;
        while (matcher.find()) {
            type = matcher.group(1);
            markerStart = matcher.start();
            markerEnd = matcher.end();
        }

        if (type == null) {
            return null;
        }

        String argumentText = strippedText.substring(markerEnd).strip();
        if (argumentText.isEmpty()) {
            return null;
        }

        String stepText = strippedText.substring(0, markerStart).stripTrailing();
        return new Extracted(stepText, type, argumentText);
    }

    public record Extracted(String stepText, String argumentType, String argumentText) {
    }
}
