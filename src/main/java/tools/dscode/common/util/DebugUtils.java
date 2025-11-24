package tools.dscode.common.util;

import io.cucumber.java.bs.A;

import java.util.ArrayList;
import java.util.List;

public class DebugUtils {

    public static List<String> prefixes = new ArrayList<>();
    public static List<String> substrings = new ArrayList<>();


    static {
//        prefixes.add("@@");
//        substrings.add("@@##");
    }

    public static void printDebug(String message) {
        if (message == null) return;

        String trimmed = message.strip();

        // Check prefixes
        for (String p : prefixes) {
            if (trimmed.startsWith(p)) {
                System.out.println(message);
                return;
            }
        }

        // Check substrings
        for (String s : substrings) {
            if (trimmed.contains(s)) {
                System.out.println(message);
                return;
            }
        }
    }
}

