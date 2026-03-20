package tools.dscode.common.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.Callable;

import static tools.dscode.common.domoperations.SeleniumUtils.waitMilliseconds;

public class GeneralUtils {

    public static <T> T retry(
            int maxAttempts,
            long delayMillis,
            Callable<T> action
    )  {

        int attempt = 0;

        while (true) {
            try {
                attempt++;
                return action.call();
            } catch (Exception e) {
                if (attempt >= maxAttempts) {
                    throw new RuntimeException(e);
                }

                waitMilliseconds(delayMillis);
            }
        }
    }

    public static boolean isUsableValue(Object value) {
        if (value == null) return false;
        if (!(value instanceof String s)) return true;

        int len = s.length();
        int i = 0;
        while (i < len && s.charAt(i) <= ' ') {
            i++;
        }

        return i < len && s.charAt(i) != '<';
    }

    public static String stackTraceToString(Throwable t) {
        if (t == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

}
