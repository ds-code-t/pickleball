package io.pickleball.stringutilities;

public class StringComponents {

    public static String[] extractPrefix(String text, String pattern) {
        if (text == null) return new String[]{"", ""};
        var matcher = java.util.regex.Pattern.compile("^(" + pattern + ")").matcher(text);
        return matcher.find()
                ? new String[]{matcher.group(1), text.substring(matcher.end())}
                : new String[]{"", text};
    }



}
