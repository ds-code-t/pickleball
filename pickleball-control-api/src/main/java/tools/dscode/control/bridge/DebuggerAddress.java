package tools.dscode.control.bridge;

import org.openqa.selenium.Capabilities;

import java.util.Locale;
import java.util.Map;

/** Reads the debugger address Selenium already published. Does not start a browser. */
public final class DebuggerAddress {
    private DebuggerAddress() {
    }

    public static String from(Capabilities capabilities) {
        if (capabilities == null) return null;
        String devtools = text(capabilities.getCapability("se:cdp"));
        if (devtools != null) return devtools;
        String browser = text(capabilities.getCapability("browserName"));
        boolean edge = browser != null && browser.toLowerCase(Locale.ROOT).contains("edge");
        String preferred = edge ? "ms:edgeOptions" : "goog:chromeOptions";
        String address = debugger(capabilities.getCapability(preferred));
        if (address != null) return address;
        return debugger(capabilities.getCapability(edge ? "goog:chromeOptions" : "ms:edgeOptions"));
    }

    public static String browserType(Capabilities capabilities) {
        if (capabilities == null) return null;
        String browser = text(capabilities.getCapability("browserName"));
        if (browser == null) return null;
        String lower = browser.toLowerCase(Locale.ROOT);
        if (lower.contains("edge")) return "edge";
        if (lower.contains("chrome")) return "chrome";
        return null;
    }

    private static String debugger(Object options) {
        if (!(options instanceof Map<?, ?> map)) return null;
        return text(map.get("debuggerAddress"));
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
