package tools.dscode.launcher;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Page;
import org.openqa.selenium.WebDriver;
import tools.dscode.browser.PlaywrightConnection;
import tools.dscode.common.driver.DriverConstruction;
import tools.dscode.engine.EngineLog;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.match.ForeignStepHost;
import tools.dscode.engine.match.ForeignStepHosts;

import java.util.List;

/**
 * Executes a stored Playwright spec step. The spine keeps the handle string
 * {@code playwright.page}. The page object stays in this host.
 */
public final class PlaywrightSpecHost implements ForeignStepHost, AutoCloseable {
    private Page page;
    private String handle;
    private WebDriver launched;
    private PlaywrightConnection connection;
    private boolean opened;

    @Override
    public String language() {
        return "playwright";
    }

    @Override
    public boolean supports(String host) {
        return "playwright".equals(host);
    }

    @Override
    public Object invoke(String definitionId, String stepText, List<String> argsJson) {
        ensurePage();
        ExecutionContext current = ExecutionContext.current();
        if (current != null) current.resource("playwright.page", handle);
        String text = strip(stepText);
        String verb = verb(text);
        EngineLog.log(ForeignStepHosts.project(), "host", "playwright", verb);
        if (text.contains("page.goto(") || text.contains("page.navigate(")) {
            page.navigate(quoted(text));
            return handle;
        }
        if (text.contains(".textContent(")) {
            return page.locator(quoted(text)).textContent();
        }
        if (text.contains("toHaveTitle")) {
            String expected = quoted(text);
            String actual = page.title();
            if (!expected.equals(actual)) {
                throw new IllegalStateException("error: title was " + actual + "\nfix: expected " + expected);
            }
            return actual;
        }
        throw new IllegalStateException(
                "error: unrecognized playwright step\nfix: use page.goto, locator textContent, or toHaveTitle");
    }

    private void ensurePage() {
        if (page != null) return;
        String endpoint = PlaywrightConnection.configuredEndpoint(null);
        if (endpoint == null) {
            ObjectNode config = JsonNodeFactory.instance.objectNode();
            config.put("browser", "chrome");
            ArrayNode args = config.putObject("options").putArray("args");
            args.add("--headless=new");
            args.add("--remote-debugging-port=0");
            args.add("--remote-allow-origins=*");
            try {
                launched = DriverConstruction.createLocalDriver(config);
            } catch (Exception failure) {
                throw new IllegalStateException("error: Chrome did not start for the spec page\nfix: set pkb_cdp_endpoint to a Chromium that is already listening", failure);
            }
            endpoint = PlaywrightConnection.endpointFor(launched);
        }
        if (endpoint == null || endpoint.isBlank()) throw new IllegalStateException(PlaywrightConnection.NO_ENDPOINT);
        connection = PlaywrightConnection.connect(endpoint);
        opened = true;
        page = connection.page(null);
        handle = "page:" + System.identityHashCode(page);
    }

    private static String strip(String line) {
        String text = line == null ? "" : line.trim();
        if (text.endsWith(";")) text = text.substring(0, text.length() - 1).trim();
        if (text.startsWith("await ")) text = text.substring(6).trim();
        return text;
    }

    private static String verb(String text) {
        if (text.contains("toHaveTitle")) return "toHaveTitle";
        if (text.contains("textContent")) return "textContent";
        if (text.contains("page.goto") || text.contains("page.navigate")) return "goto";
        return "step";
    }

    private static String quoted(String text) {
        for (int index = 0; index < text.length(); index++) {
            char quote = text.charAt(index);
            if (quote != '\'' && quote != '"' && quote != '`') continue;
            int end = text.indexOf(quote, index + 1);
            if (end > index) return text.substring(index + 1, end);
        }
        return "";
    }

    @Override
    public void close() {
        page = null;
        handle = null;
        WebDriver driver = launched;
        launched = null;
        PlaywrightConnection open = opened ? connection : null;
        connection = null;
        opened = false;
        if (open != null) {
            try {
                open.close();
            } catch (RuntimeException ignored) {
                // The browser process is stopped by the caller when this host did not launch it.
            }
        }
        if (driver != null) {
            try {
                driver.quit();
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        }
    }
}
