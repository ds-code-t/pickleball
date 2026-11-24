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
 * {@link SeleniumAdapter}.
 *
 * <p>Playwright (D12) is selected with {@link Preference#PLAYWRIGHT}, or with {@code pkb_protocol=playwright}
 * (property, then env {@code PKB_PROTOCOL}) for {@link Preference#AUTO} and {@link Preference#DIRECT}. It joins the
 * driver's own browser over CDP ({@code pkb_cdp_endpoint} first, then the driver's {@code se:cdp} or
 * {@code debuggerAddress}) and never launches or downloads a browser. When it cannot attach, the selector logs one
 * WARN per reason and falls back quietly to the CDP, BiDi, or Selenium choice above. The default, with no setting,
 * is unchanged. Since D12 Playwright is a normal (unshaded) dependency, so this choice needs no extra jar.
 *
 * <p>{@link #both(WebDriver)} (E1) keeps a BiDi and a CDP socket together for Chromium when the session publishes
 * both: Selenium sessions ask for {@code webSocketUrl=true} by default ({@code pkb_bidi=false} turns it off).
 * {@link #protocols(WebDriver)} returns the per-session {@link BrowserProtocols} registry that resolves each
 * capability to Playwright, BiDi, CDP, or classic Selenium in that fallback order.
 */
public final class ProtocolSelector {
    public enum Preference {
        AUTO, LIBRARY, DIRECT, PLAYWRIGHT
    }

    public static final String PROTOCOL_PROPERTY = "pkb_protocol";
    public static final String PROTOCOL_ENV = "PKB_PROTOCOL";

    /** Attaches Playwright to an endpoint and returns the adapter for the page at {@code url}. Tests inject one. */
    @FunctionalInterface
    public interface PlaywrightConnector {
        BrowserFacade attach(String endpoint, String url);
    }

    private static final PlaywrightConnector LIVE_CONNECTOR = (endpoint, url) ->
            PlaywrightAdapter.open(PlaywrightConnection.connect(endpoint).page(url));
    private static volatile PlaywrightConnector connector = LIVE_CONNECTOR;

    /** {@code pkb_protocol} property, then env {@code PKB_PROTOCOL}, lower-cased; empty when unset. */
    public static String configuredProtocol() {
        String value = System.getProperty(PROTOCOL_PROPERTY);
        if (value == null || value.isBlank()) value = System.getenv(PROTOCOL_ENV);
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static void useConnectorForTests(PlaywrightConnector next) {
        connector = next == null ? LIVE_CONNECTOR : next;
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
        if (choice == Preference.PLAYWRIGHT || "playwright".equals(configuredProtocol())) {
            BrowserFacade playwright = playwright(driver, family);
            if (playwright != null) return playwright;
        }
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

    /** The primary facade from {@link #forDriver} plus a BiDi adapter when the session also publishes BiDi. */
    public record Sockets(BrowserFacade primary, BidiAdapter bidi) {
    }

    /** Chromium: CDP (or Playwright) primary and BiDi beside it when both exist. Firefox: BiDi for both. */
    public static Sockets both(WebDriver driver) {
        BrowserFacade primary = forDriver(driver);
        if (primary instanceof BidiAdapter bidiOnly) return new Sockets(primary, bidiOnly);
        BidiAdapter bidi = null;
        URI uri = endpoints.bidi(driver);
        if (uri != null && classify(driver.getClass().getName(), browserName(driver)) == Family.CHROMIUM) {
            try {
                bidi = new BidiAdapter(ProtocolSockets.open(uri));
                BrowserLog.info("selector", "bidi beside " + primary.protocol() + " " + uri);
            } catch (RuntimeException failure) {
                BrowserLog.warnOnce("bidi-beside", "BiDi socket failed: " + firstLine(failure.getMessage()) + "; CDP only");
            }
        }
        return new Sockets(primary, bidi);
    }

    /** The capability registry for this session, from the endpoints this selector sees. */
    public static BrowserProtocols protocols(WebDriver driver) {
        String name = browserName(driver);
        String version = "";
        if (driver instanceof HasCapabilities has) version = has.getCapabilities().getBrowserVersion();
        Family family = classify(driver.getClass().getName(), name);
        BrowserProtocols protocols = new BrowserProtocols(family == Family.FIREFOX ? "firefox" : name, version,
                "playwright".equals(configuredProtocol()));
        protocols.live(BrowserProtocols.Backend.CLASSIC, "selenium", null);
        URI bidi = endpoints.bidi(driver);
        if (bidi != null) protocols.live(BrowserProtocols.Backend.BIDI, "webSocketUrl", bidi.toString());
        if (family == Family.CHROMIUM) {
            URI cdp = endpoints.cdp(driver);
            if (cdp != null) {
                protocols.live(BrowserProtocols.Backend.CDP, version, cdp.toString());
                protocols.live(BrowserProtocols.Backend.PLAYWRIGHT, BrowserProtocols.playwrightVersion(), cdp.toString());
            }
        }
        return protocols;
    }

    private static BrowserFacade playwright(WebDriver driver, Family family) {
        if (family != Family.CHROMIUM) {
            BrowserLog.warnOnce("playwright-family", "Playwright attaches over CDP to Chromium only (family " + family + "); not selected");
            return null;
        }
        String endpoint = PlaywrightConnection.configuredEndpoint(null);
        if (endpoint == null) {
            URI uri = endpoints.cdp(driver);
            endpoint = uri == null ? null : uri.toString();
        }
        if (endpoint == null) {
            BrowserLog.warnOnce("playwright-endpoint", "no CDP endpoint for Playwright; falling back");
            return null;
        }
        try {
            String url = null;
            try {
                url = driver.getCurrentUrl();
            } catch (RuntimeException ignored) {
                // No current page: Playwright takes the first page.
            }
            BrowserFacade facade = connector.attach(endpoint, url);
            BrowserLog.info("selector", "playwright " + PlaywrightConnection.redact(endpoint));
            return facade;
        } catch (RuntimeException failure) {
            BrowserLog.warnOnce("playwright-connect", "Playwright attach failed: " + firstLine(failure.getMessage()) + "; falling back");
            return null;
        }
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
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
        connector = LIVE_CONNECTOR;
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
