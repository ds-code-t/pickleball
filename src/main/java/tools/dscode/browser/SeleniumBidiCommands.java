package tools.dscode.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.bidi.HasBiDi;
import org.openqa.selenium.bidi.module.Network;
import org.openqa.selenium.bidi.module.Script;
import org.openqa.selenium.bidi.network.AddInterceptParameters;
import org.openqa.selenium.bidi.network.BytesValue;
import org.openqa.selenium.bidi.network.ContinueResponseParameters;
import org.openqa.selenium.bidi.network.InterceptPhase;
import org.openqa.selenium.bidi.network.ProvideResponseParameters;
import org.openqa.selenium.bidi.network.ResponseDetails;
import org.openqa.selenium.bidi.script.ContextTarget;
import org.openqa.selenium.bidi.script.EvaluateParameters;
import org.openqa.selenium.bidi.script.EvaluateResult;
import org.openqa.selenium.bidi.script.EvaluateResultExceptionValue;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.common.agentcontrol.AgentControl;
import tools.dscode.common.driver.DriverConstruction;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Opt-in Selenium BiDi verbs: {@code bidi script}, {@code bidi intercept}, and {@code bidi grid-attach}.
 * Off unless {@code pkb_selenium_bidi=on} or {@code browser.selenium.bidi: on}.
 * {@code bidi script} evaluates on the live Selenium session. {@code bidi intercept} replaces the
 * response body through {@code network.provideResponse} for {@code --url} or {@code --all}.
 * The original status stays unless {@code --status} is set. {@code bidi intercept off --id=} removes one.
 * A Playwright session refuses intercept.
 * Event names stay the five in {@link SeleniumBidiEvents}. Recording stays {@code pw codegen}.
 */
public final class SeleniumBidiCommands {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A Selenium session the script and intercept verbs can call. Playwright sessions refuse intercept. */
    public interface ScriptSession {
        void script(String source);

        boolean playwright();
    }

    private static volatile ScriptSession session;
    private static final ConcurrentHashMap<String, LiveIntercept> ACTIVE = new ConcurrentHashMap<>();
    private static final AtomicReference<Network> NETWORK = new AtomicReference<>();
    private static final AtomicReference<WebDriver> NETWORK_DRIVER = new AtomicReference<>();
    private static final AtomicBoolean LISTENING = new AtomicBoolean();

    private record LiveIntercept(Pattern url, boolean all, String body, Integer status) {
    }

    private SeleniumBidiCommands() {
    }

    public static void resetForTests() {
        session = null;
        ACTIVE.clear();
        LISTENING.set(false);
        NETWORK_DRIVER.set(null);
        Network network = NETWORK.getAndSet(null);
        if (network != null) {
            try {
                network.close();
            } catch (RuntimeException ignored) {
                // The test driver is already closed.
            }
        }
    }

    public static void useSessionForTests(ScriptSession next) {
        session = next;
    }

    public static boolean handles(String[] args) {
        return args != null && args.length > 0 && args[0] != null && "bidi".equals(args[0].trim());
    }

    public static int execute(String[] args, PrintStream out, PrintStream err) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        if (!handles(args)) {
            stderr.println("error: bidi CLI expected the bidi command");
            stderr.println("fix: bidi help");
            return 2;
        }
        List<String> tokens = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            if (args[index] != null) tokens.add(args[index]);
        }
        if (tokens.isEmpty() || tokens.stream().anyMatch(SeleniumBidiCommands::isHelp)) {
            stdout.print(help());
            return 0;
        }
        String verb = tokens.get(0);
        if (!"script".equals(verb) && !"intercept".equals(verb) && !"grid-attach".equals(verb)) {
            stderr.println("error: unknown bidi command " + verb);
            stderr.println("fix: bidi help");
            return 2;
        }
        if (!enabled()) {
            stderr.println("error: bidi commands are off");
            stderr.println("fix: set pkb_selenium_bidi=on or browser.selenium.bidi: on");
            return 2;
        }
        try {
            return switch (verb) {
                case "script" -> script(tokens, stdout, stderr);
                case "intercept" -> intercept(tokens, stdout, stderr);
                default -> gridAttach(tokens, stdout, stderr);
            };
        } catch (RuntimeException failure) {
            String message = failure.getMessage() == null ? "bidi command failed" : failure.getMessage();
            if (!message.startsWith("error:")) stderr.println("error: " + message);
            else stderr.println(message);
            return 1;
        }
    }

    public static String help() {
        return """
                Usage: bidi script [--source=<javascript>]
                       bidi intercept (--url=<pattern> | --all) [--body=<text>] [--status=<code>]
                       bidi intercept off --id=<id>
                       bidi grid-attach --url=<remote>
                bidi script, bidi intercept, and bidi grid-attach need --session or the default session.
                bidi help is exempt (Dan, 2026-11-26). A missing session on those three exits 2.
                fix: spine session new
                Off unless pkb_selenium_bidi=on or browser.selenium.bidi: on.
                Events stay onConsoleEntry, onJavaScriptException, onBeforeRequestSent, onResponseCompleted, and onFetchError.
                """;
    }

    static boolean enabled() {
        String value = BrowserProtocols.setting("pkb_selenium_bidi");
        if (value != null) {
            String text = value.trim().toLowerCase(Locale.ROOT);
            return "on".equals(text) || "true".equals(text) || "yes".equals(text) || "1".equals(text);
        }
        return BrowserVersionResolver.seleniumBidi(Path.of("").toAbsolutePath());
    }

    private static int script(List<String> tokens, PrintStream stdout, PrintStream stderr) {
        String source = flag(tokens, "--source");
        ScriptSession current = session;
        if (current != null) {
            current.script(source);
            stdout.println("bidi script");
            return 0;
        }
        WebDriver driver = liveSelenium();
        if (driver == null) {
            stderr.println("error: no Selenium session for bidi script");
            stderr.println("fix: launch a Selenium browser with pkb_selenium_bidi=on");
            return 1;
        }
        String context = driver.getWindowHandle();
        if (context == null || context.isBlank()) {
            stderr.println("error: no browsing context for bidi script");
            stderr.println("fix: launch a Selenium browser with pkb_selenium_bidi=on");
            return 1;
        }
        Script module = new Script(driver);
        EvaluateResult evaluated = module.evaluateFunction(
                new EvaluateParameters(new ContextTarget(context), source, false));
        if (evaluated instanceof EvaluateResultExceptionValue failed) {
            String text = String.valueOf(failed.getExceptionDetails());
            stderr.println("error: bidi script failed: " + text);
            stderr.println("fix: bidi script --source=<expression>");
            return 1;
        }
        stdout.println("bidi script");
        return 0;
    }

    private static int intercept(List<String> tokens, PrintStream stdout, PrintStream stderr) {
        if (tokens.size() > 1 && "off".equals(tokens.get(1))) return interceptOff(tokens, stderr);
        String body = flag(tokens, "--body");
        ScriptSession current = session;
        WebDriver driver = AgentControl.scenarioDriver();
        if (current != null && current.playwright()) return playwrightRefusal(stderr);
        if (playwrightSession(driver)) return playwrightRefusal(stderr);
        boolean all = has(tokens, "--all");
        String url = flag(tokens, "--url");
        boolean urlSet = url != null && !url.isBlank();
        if (all && urlSet) {
            stderr.println("error: bidi intercept takes --url or --all");
            stderr.println("fix: pass one of --url=<pattern> or --all");
            return 2;
        }
        if (!all && !urlSet) {
            stderr.println("error: bidi intercept needs --url or --all");
            stderr.println("fix: bidi intercept --url=<pattern> or bidi intercept --all");
            return 2;
        }
        Integer status = null;
        String statusText = flag(tokens, "--status");
        if (statusText != null && !statusText.isBlank()) {
            try {
                status = Integer.parseInt(statusText.trim());
            } catch (NumberFormatException failure) {
                stderr.println("error: bidi intercept --status is not a number");
                stderr.println("fix: bidi intercept --status=<code>");
                return 2;
            }
        }
        Pattern pattern = null;
        if (!all) {
            try {
                pattern = Pattern.compile(url);
            } catch (PatternSyntaxException failure) {
                stderr.println("error: bidi intercept --url is not a pattern");
                stderr.println("fix: pass a regular expression or --all");
                return 2;
            }
        }
        if (current != null && !(driver instanceof HasBiDi)) {
            current.script(body);
            return 0;
        }
        if (!(driver instanceof HasBiDi)) {
            stderr.println("error: no Selenium session for bidi intercept");
            stderr.println("fix: launch a Selenium browser with pkb_selenium_bidi=on");
            return 1;
        }
        Network network = sharedNetwork(driver);
        AddInterceptParameters parameters = new AddInterceptParameters(InterceptPhase.RESPONSE_STARTED);
        if (!all && literalUrl(url)) parameters.urlStringPattern(url);
        String id = network.addIntercept(parameters);
        ACTIVE.put(id, new LiveIntercept(pattern, all, body == null ? "" : body, status));
        arm(network);
        stdout.println("intercept " + id);
        return 0;
    }

    private static int interceptOff(List<String> tokens, PrintStream stderr) {
        String id = flag(tokens, "--id");
        if (id == null || id.isBlank()) {
            stderr.println("error: bidi intercept off needs --id");
            stderr.println("fix: bidi intercept off --id=<id>");
            return 2;
        }
        if (ACTIVE.remove(id) == null) {
            stderr.println("error: no bidi intercept " + id);
            stderr.println("fix: bidi intercept off --id=<id> uses the id that intercept printed");
            return 1;
        }
        Network network = NETWORK.get();
        if (network != null) {
            try {
                network.removeIntercept(id);
            } catch (RuntimeException failure) {
                String message = failure.getMessage() == null ? "bidi intercept off failed" : failure.getMessage();
                stderr.println(message.startsWith("error:") ? message : "error: " + message);
                stderr.println("fix: bidi intercept off --id=<id>");
                return 1;
            }
            if (ACTIVE.isEmpty()) {
                try {
                    network.close();
                } catch (RuntimeException ignored) {
                    // The listener is already gone.
                }
                NETWORK.compareAndSet(network, null);
                NETWORK_DRIVER.set(null);
                LISTENING.set(false);
            }
        }
        return 0;
    }

    private static Network sharedNetwork(WebDriver driver) {
        Network current = NETWORK.get();
        if (current != null && NETWORK_DRIVER.get() == driver) return current;
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException ignored) {
                // The previous session is already closed.
            }
        }
        Network created = new Network(driver);
        NETWORK.set(created);
        NETWORK_DRIVER.set(driver);
        LISTENING.set(false);
        return created;
    }

    /** Register after the id is stored. The test stub delivers the first event inside this call. */
    private static void arm(Network network) {
        if (!LISTENING.compareAndSet(false, true)) return;
        network.onResponseStarted(details -> onResponse(network, details));
    }

    private static void onResponse(Network network, ResponseDetails details) {
        if (details == null || details.getRequest() == null || details.getRequest().getRequestId() == null) return;
        String requestId = details.getRequest().getRequestId();
        String requestUrl = details.getRequest().getUrl() == null ? "" : details.getRequest().getUrl();
        LiveIntercept match = null;
        for (LiveIntercept held : ACTIVE.values()) {
            if (held.all() || (held.url() != null && held.url().matcher(requestUrl).find())) {
                match = held;
                break;
            }
        }
        if (match == null) {
            network.continueResponse(new ContinueResponseParameters(requestId));
            return;
        }
        ProvideResponseParameters provided = new ProvideResponseParameters(requestId)
                .body(new BytesValue(BytesValue.Type.STRING, match.body() == null ? "" : match.body()));
        Integer status = match.status();
        if (status == null && details.getResponseData() != null) status = details.getResponseData().getStatus();
        if (status != null) provided.statusCode(status);
        network.provideResponse(provided);
    }

    private static boolean literalUrl(String url) {
        for (int index = 0; index < url.length(); index++) {
            if ("\\^$.|?*+()[]{}".indexOf(url.charAt(index)) >= 0) return false;
        }
        return true;
    }

    private static boolean has(List<String> tokens, String name) {
        for (String token : tokens) {
            if (name.equals(token) || token.startsWith(name + "=")) return true;
        }
        return false;
    }

    /** The registered Selenium driver when it supports BiDi. The test-hook session is not consulted. */
    private static WebDriver liveSelenium() {
        WebDriver driver = AgentControl.scenarioDriver();
        return driver instanceof HasBiDi ? driver : null;
    }

    /** A registered driver that is not a Selenium BiDi session while the protocol is Playwright. */
    private static boolean playwrightSession(WebDriver driver) {
        if (driver == null || driver instanceof HasBiDi) return false;
        String protocol = BrowserProtocols.setting("pkb_protocol");
        if (protocol != null && "playwright".equalsIgnoreCase(protocol.trim())) return true;
        return driver.getClass().getName().toLowerCase(Locale.ROOT).contains("playwright");
    }

    private static int playwrightRefusal(PrintStream stderr) {
        stderr.println("error: bidi intercept is a Selenium response-body replacement");
        stderr.println("fix: use a Selenium session; Playwright sessions do not use bidi intercept");
        return 2;
    }

    private static int gridAttach(List<String> tokens, PrintStream stdout, PrintStream stderr) throws RuntimeException {
        String url = flag(tokens, "--url");
        if (url == null || url.isBlank()) {
            stderr.println("error: bidi grid-attach needs --url");
            stderr.println("fix: bidi grid-attach --url=<remote>");
            return 2;
        }
        ObjectNode config = JSON.createObjectNode();
        config.put("browser", "chrome");
        config.putObject("driver").putObject("connection").put("remoteUrl", url);
        try {
            RemoteWebDriver driver = DriverConstruction.createRemoteDriver(config);
            stdout.println("grid-attach " + (driver.getSessionId() == null ? "open" : driver.getSessionId()));
            return 0;
        } catch (Exception failure) {
            stderr.println("error: grid attach failed");
            stderr.println("fix: check --url and the Selenium grid");
            return 1;
        }
    }

    private static String flag(List<String> tokens, String name) {
        String prefix = name + "=";
        for (String token : tokens) {
            if (token.startsWith(prefix)) return token.substring(prefix.length());
        }
        return "";
    }

    private static boolean isHelp(String token) {
        return "help".equals(token) || "--help".equals(token) || "-h".equals(token);
    }
}
