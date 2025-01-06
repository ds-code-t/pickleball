package io.pickleball.debugtools;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TimeUtils {
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static String getCurrentTimestamp() {
        return LocalDateTime.now().format(FORMATTER);
    }

    public static long getCurrentTimeMillis() {
        return System.currentTimeMillis();
    }

}