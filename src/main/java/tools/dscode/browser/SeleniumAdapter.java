package tools.dscode.browser;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Scripted library adapter. Element operations use the WebDriver API only.
 * This class does not call {@code executeCdpCommand} or open a BiDi socket.
 */
public final class SeleniumAdapter implements BrowserFacade {
    private final WebDriver driver;
    private final Map<String, WebElement> handles = new LinkedHashMap<>();
    private final List<String> preloads = new ArrayList<>();
    private final Map<String, BindingListener> bindings = new LinkedHashMap<>();

    public SeleniumAdapter(WebDriver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
    }

    public WebDriver driver() {
        return driver;
    }

    public List<String> preloads() {
        return List.copyOf(preloads);
    }

    @Override
    public String protocol() {
        return "selenium";
    }

    @Override
    public Object send(String method, Map<String, ?> params) {
        throw new UnsupportedOperationException(
                "error: SeleniumAdapter does not send protocol method " + method + ".\n"
                        + "fix: Use CdpAdapter or BidiAdapter for a direct method. "
                        + "Scripted code uses find, click, and type.");
    }

    @Override
    public Object evaluateIsolated(String worldName, String fn, Object... args) {
        if (!(driver instanceof JavascriptExecutor executor)) {
            throw new IllegalStateException(
                    "error: this driver cannot evaluate script.\n"
                            + "fix: Use a JavascriptExecutor, or CdpAdapter / BidiAdapter for an isolated world.");
        }
        if (fn == null) {
            throw new IllegalArgumentException("error: function text is null.\nfix: Pass the script source.");
        }
        Object[] values = args == null ? new Object[0] : args;
        if (values.length == 0) return executor.executeScript(fn);
        return executor.executeScript(fn, values);
    }

    @Override
    public void addPreload(String script) {
        if (script == null) {
            throw new IllegalArgumentException("error: preload script is null.\nfix: Pass the script text.");
        }
        preloads.add(script);
        if (driver instanceof JavascriptExecutor executor) {
            executor.executeScript(script);
        }
    }

    @Override
    public void addBinding(String name, BindingListener listener) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("error: binding name is blank.\nfix: Pass the binding name.");
        }
        bindings.put(name, listener);
    }

    public BindingListener binding(String name) {
        return bindings.get(name);
    }

    @Override
    public byte[] screenshot(Map<String, ?> options) {
        if (!(driver instanceof TakesScreenshot shot)) {
            throw new IllegalStateException(
                    "error: this driver cannot take a screenshot.\n"
                            + "fix: Use a TakesScreenshot driver, or CdpAdapter / BidiAdapter.");
        }
        return shot.getScreenshotAs(OutputType.BYTES);
    }

    @Override
    public FrameNode frameTree() {
        String url = "";
        String id = "root";
        try {
            String current = driver.getCurrentUrl();
            if (current != null) url = current;
        } catch (RuntimeException ignored) {
            url = "";
        }
        try {
            String handle = driver.getWindowHandle();
            if (handle != null && !handle.isBlank()) id = handle;
        } catch (RuntimeException ignored) {
            id = "root";
        }
        return FrameNode.leaf(id, null, url);
    }

    @Override
    public List<String> find(String using, String value) {
        List<WebElement> found = driver.findElements(by(using, value));
        List<String> ids = new ArrayList<>();
        for (WebElement element : found) {
            String id = "selenium-" + (handles.size() + 1);
            handles.put(id, element);
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    @Override
    public void click(String handle) {
        element(handle).click();
    }

    @Override
    public void type(String handle, String text) {
        element(handle).sendKeys(text == null ? "" : text);
    }

    private WebElement element(String handle) {
        WebElement element = handles.get(handle);
        if (element == null) {
            throw new IllegalArgumentException(
                    "error: unknown selenium handle " + handle + ".\n"
                            + "fix: Use a handle returned by find on this adapter.");
        }
        return element;
    }

    static By by(String using, String value) {
        String key = using == null ? "" : using.trim().toLowerCase(Locale.ROOT);
        String target = value == null ? "" : value;
        return switch (key) {
            case "css", "css selector" -> By.cssSelector(target);
            case "xpath" -> By.xpath(target);
            case "id" -> By.id(target);
            case "name" -> By.name(target);
            case "link", "link text" -> By.linkText(target);
            case "tag", "tag name" -> By.tagName(target);
            default -> throw new IllegalArgumentException(
                    "error: unknown locator " + using + ".\n"
                            + "fix: Use css, xpath, id, name, link text, or tag name.");
        };
    }
}
