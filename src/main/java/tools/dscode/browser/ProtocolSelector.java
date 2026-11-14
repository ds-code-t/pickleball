package tools.dscode.browser;

import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.WebDriver;

import java.net.URI;
import java.util.Locale;

/**
 * Picks the adapter for a driver.
 *
 * <p>{@link Preference#AUTO} and {@link Preference#DIRECT} use {@link CdpAdapter}
 * for Chrome, Chromium, and Edge when a debugger websocket resolves, and
 * {@link BidiAdapter} for Firefox when a BiDi websocket is published.
 * Anything else, including a Chromium or Firefox driver with no endpoint,
 * is {@link SeleniumAdapter}. That fallback logs one WARN per run and reason.
 * {@link Preference#LIBRARY} is the scripted path and always returns
 * {@link SeleniumAdapter}. Playwright is not selected from a WebDriver.
 */
public final class ProtocolSelector {
    public enum Preference {
        AUTO, LIBRARY, DIRECT
    }

    enum Family {
        CHROMIUM, FIREFOX, OTHER
    }

    @FunctionalInterface
    public interface Endpoints {
        URI cdp(WebDriver driver);

        default URI bidi(WebDriver driver) {
            return DriverEndpoints.bidi(driver);
        }
    }

    private static volatile Endpoints endpoints = new Endpoints() {
        @Override
        public URI cdp(WebDriver driver) {
            return DriverEndpoints.cdp(driver);
        }

        @Override
        public URI bidi(WebDriver driver) {
            return DriverEndpoints.bidi(driver);
        }
    };

    private ProtocolSelector() {
    }

    public static BrowserFacade forDriver(WebDriver driver) {
        return forDriver(driver, Preference.AUTO);
    }

    public static BrowserFacade forDriver(WebDriver driver, Preference preference) {
        if (driver == null) {
            throw new IllegalArgumentException("error: driver is null.\nfix: Pass the live WebDriver.");
        }
        Preference choice = preference == null ? Preference.AUTO : preference;
        if (choice == Preference.LIBRARY) {
            BrowserLog.info("selector", "library selenium");
            return new SeleniumAdapter(driver);
        }
        Family family = classify(driver.getClass().getName(), browserName(driver));
        try {
            if (family == Family.FIREFOX) {
                URI uri = endpoints.bidi(driver);
                if (uri != null) {
                    BrowserLog.info("selector", "bidi " + uri);
                    return new BidiAdapter(ProtocolSockets.open(uri));
                }
                BrowserLog.warnOnce("bidi-missing", "Firefox has no BiDi webSocketUrl; SeleniumAdapter");
                return new SeleniumAdapter(driver);
            }
            if (family == Family.CHROMIUM) {
                URI uri = endpoints.cdp(driver);
                if (uri != null) {
                    BrowserLog.info("selector", "cdp " + uri);
                    return new CdpAdapter(ProtocolSockets.open(uri));
                }
                BrowserLog.warnOnce("cdp-missing", "Chromium has no debugger websocket; SeleniumAdapter");
                return new SeleniumAdapter(driver);
            }
        } catch (RuntimeException failure) {
            String reason = family == Family.FIREFOX ? "bidi-connect" : "cdp-connect";
            BrowserLog.warnOnce(reason, "socket failed: " + failure.getMessage() + "; SeleniumAdapter");
            return new SeleniumAdapter(driver);
        }
        BrowserLog.info("selector", "library selenium family=" + family);
        return new SeleniumAdapter(driver);
    }

    static Family classify(String className, String browserName) {
        String type = className == null ? "" : className.toLowerCase(Locale.ROOT);
        String browser = browserName == null ? "" : browserName.toLowerCase(Locale.ROOT);
        if (type.contains("firefox") || browser.contains("firefox")) return Family.FIREFOX;
        if (type.contains("chrome") || type.contains("chromium") || type.contains("edge")
                || browser.contains("chrome") || browser.contains("edge")) {
            return Family.CHROMIUM;
        }
        return Family.OTHER;
    }

    public static void useEndpointsForTests(Endpoints next) {
        endpoints = next == null ? new Endpoints() {
            @Override
            public URI cdp(WebDriver driver) {
                return DriverEndpoints.cdp(driver);
            }
        } : next;
    }

    public static void resetForTests() {
        endpoints = new Endpoints() {
            @Override
            public URI cdp(WebDriver driver) {
                return DriverEndpoints.cdp(driver);
            }
        };
        ProtocolSockets.resetForTests();
        BrowserLog.resetForTests();
    }

    private static String browserName(WebDriver driver) {
        if (!(driver instanceof HasCapabilities has)) return "";
        Object name = has.getCapabilities().getBrowserName();
        return name == null ? "" : String.valueOf(name);
    }
}
