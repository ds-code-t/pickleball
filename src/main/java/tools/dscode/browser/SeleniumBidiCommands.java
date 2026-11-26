package tools.dscode.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.common.driver.DriverConstruction;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Opt-in Selenium BiDi verbs: {@code bidi script}, {@code bidi intercept}, and {@code bidi grid-attach}.
 * Off unless {@code pkb_selenium_bidi=on} or {@code browser.selenium.bidi: on}.
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

    private SeleniumBidiCommands() {
    }

    public static void resetForTests() {
        session = null;
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
        if (tokens.isEmpty() || isHelp(tokens.get(0))) {
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
                case "intercept" -> intercept(tokens, stderr);
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
                       bidi intercept [--body=<text>]
                       bidi grid-attach --url=<remote>
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
        ScriptSession current = session;
        if (current == null) {
            stderr.println("error: no Selenium session for bidi script");
            stderr.println("fix: launch a Selenium browser with pkb_selenium_bidi=on");
            return 1;
        }
        current.script(flag(tokens, "--source"));
        stdout.println("bidi script");
        return 0;
    }

    private static int intercept(List<String> tokens, PrintStream stderr) {
        ScriptSession current = session;
        if (current == null) {
            stderr.println("error: no Selenium session for bidi intercept");
            stderr.println("fix: launch a Selenium browser with pkb_selenium_bidi=on");
            return 1;
        }
        if (current.playwright()) {
            stderr.println("error: bidi intercept is a Selenium response-body replacement");
            stderr.println("fix: use a Selenium session; Playwright sessions do not use bidi intercept");
            return 2;
        }
        current.script(flag(tokens, "--body"));
        return 0;
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
