package tools.dscode.common.util;

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



}
