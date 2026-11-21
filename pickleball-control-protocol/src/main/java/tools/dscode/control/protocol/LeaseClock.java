package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Seconds left on {@code start --hold}. {@code 0} is expired. {@code none} is no hold.
 * A pause-hold marker with no clock file is the worker's 3600 second hold.
 */
public final class LeaseClock {
    private LeaseClock() {
    }

    public static String left(Path project) {
        if (project == null) return "none";
        Path file = project.resolve(".pickleball").resolve("live-browser").resolve("hold.json");
        if (!Files.isRegularFile(file)) {
            Path marker = project.resolve(".pickleball").resolve("workbench").resolve("pause-hold");
            return Files.isRegularFile(marker) ? "3600" : "none";
        }
        try {
            String json = Files.readString(file);
            if (json.contains("\"forever\":true")) return "forever";
            long expires = number(json, "expires");
            if (expires <= 0) return "none";
            long left = (expires - System.currentTimeMillis()) / 1000L;
            return left < 0 ? "0" : Long.toString(left);
        } catch (IOException failure) {
            return "none";
        }
    }

    /** An unexpired hold. {@code 0} and {@code none} are not held. */
    public static boolean fresh(Path project) {
        String left = left(project);
        return left != null && !"none".equals(left) && !"0".equals(left);
    }

    private static long number(String json, String name) {
        String key = "\"" + name + "\":";
        int start = json.indexOf(key);
        if (start < 0) return 0L;
        start += key.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (end == start) return 0L;
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException failure) {
            return 0L;
        }
    }
}
