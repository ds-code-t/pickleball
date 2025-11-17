package tools.dscode.common.domoperations;

public class SeleniumUtils {
    public static void explicitWait(int seconds) {
        long l = seconds;
        explicitWait(l);
    }

    public static void explicitWait(long seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }


    public static int portFromString(String key) {
        return 9000 + (Math.abs(key.hashCode()) % 1000);
    }
}
