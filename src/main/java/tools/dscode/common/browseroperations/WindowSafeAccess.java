package tools.dscode.common.browseroperations;

import org.openqa.selenium.WebDriver;

import java.time.Duration;
import java.util.concurrent.*;

public final class WindowSafeAccess {

    private static final ExecutorService EXEC =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "webdriver-safe-access");
                t.setDaemon(true);
                return t;
            });

    private WindowSafeAccess() {}

    public static String getTitleWithTimeout(WebDriver driver, Duration timeout) {
        return callWithTimeout(driver::getTitle, timeout);
    }

    public static String getUrlWithTimeout(WebDriver driver, Duration timeout) {
        return callWithTimeout(driver::getCurrentUrl, timeout);
    }

    private static String callWithTimeout(Callable<String> call, Duration timeout) {
        Future<String> future = EXEC.submit(call);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true); // interrupt if stuck
            return null;
        } catch (Exception e) {
            future.cancel(true);
            return null;
        }
    }
}
