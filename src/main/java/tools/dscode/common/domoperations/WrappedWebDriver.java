package tools.dscode.common.domoperations;


import org.jspecify.annotations.Nullable;
import org.openqa.selenium.*;
import org.openqa.selenium.logging.Logs;

import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Set;

public abstract class WrappedWebDriver extends  WrappedContext {

    public WrappedWebDriver(SearchContext searchContext) {
        super(searchContext);
    }

    @Override
    public void get(String url) {
        ((WebDriver) searchContext).get(url);
    }

    @Override
    public String getCurrentUrl() {
        return ((WebDriver)  searchContext).getCurrentUrl();
    }

    @Override
    public String getTitle() {
        return ((WebDriver)  searchContext).getTitle();
    }

//    @Override
//    public List<WebElement> findElements(By by) {
//        return searchContext.findElements(by);
//    }
//
//    @Override
//    public WebElement findElement(By by) {
//        return searchContext.findElement(by);
//    }

    @Override
    public String getPageSource() {
        return ((WebDriver)  searchContext).getPageSource();
    }

    @Override
    public void close() {
        ((WebDriver)  searchContext).close();
    }

    @Override
    public void quit() {
        ((WebDriver)  searchContext).quit();
    }

    @Override
    public Set<String> getWindowHandles() {
        return ((WebDriver)  searchContext).getWindowHandles();
    }

    @Override
    public String getWindowHandle() {
        return ((WebDriver)  searchContext).getWindowHandle();
    }

    @Override
    public Options manage() {
        return new DelegatingOptions(((WebDriver)  searchContext).manage());
    }

    @Override
    public Navigation navigate() {
        return new DelegatingNavigation(((WebDriver)  searchContext).navigate());
    }

    @Override
    public TargetLocator switchTo() {
        return new DelegatingTargetLocator(((WebDriver)  searchContext).switchTo());
    }

    // --- Nested interface forwarding helpers (default methods included via delegation) ---

    public static class DelegatingOptions implements Options {
        private final Options optionsDelegate;

        public DelegatingOptions(Options optionsDelegate) {
            this.optionsDelegate = optionsDelegate;
        }

        @Override
        public void addCookie(Cookie cookie) {
            optionsDelegate.addCookie(cookie);
        }

        @Override
        public void deleteCookieNamed(String name) {
            optionsDelegate.deleteCookieNamed(name);
        }

        @Override
        public void deleteCookie(Cookie cookie) {
            optionsDelegate.deleteCookie(cookie);
        }

        @Override
        public void deleteAllCookies() {
            optionsDelegate.deleteAllCookies();
        }

        @Override
        public Set<Cookie> getCookies() {
            return optionsDelegate.getCookies();
        }

        @Override
        public Cookie getCookieNamed(String name) {
            return optionsDelegate.getCookieNamed(name);
        }

        @Override
        public Timeouts timeouts() {
            return new DelegatingTimeouts(optionsDelegate.timeouts());
        }

        @Override
        public Window window() {
            return new DelegatingWindow(optionsDelegate.window());
        }

        @Override
        public Logs logs() {
            return optionsDelegate.logs();
        }
    }

    public static class DelegatingTimeouts implements Timeouts {
        private final Timeouts timeoutDelegate;

        public DelegatingTimeouts(Timeouts timeoutDelegate) {
            this.timeoutDelegate = timeoutDelegate;
        }

        @Override
        public Timeouts implicitlyWait(Duration duration) {
            timeoutDelegate.implicitlyWait(duration);
            return this;
        }

        @Override
        public Duration getImplicitWaitTimeout() {
            return timeoutDelegate.getImplicitWaitTimeout();
        }

        @Override
        public Timeouts scriptTimeout(Duration duration) {
            timeoutDelegate.scriptTimeout(duration);
            return this;
        }

        @Override
        public Duration getScriptTimeout() {
            return timeoutDelegate.getScriptTimeout();
        }

        @Override
        public Timeouts pageLoadTimeout(Duration duration) {
            timeoutDelegate.pageLoadTimeout(duration);
            return this;
        }

        @Override
        public Duration getPageLoadTimeout() {
            return timeoutDelegate.getPageLoadTimeout();
        }
    }

    public static class DelegatingWindow implements Window {
        private final Window windowDelegate;

        public DelegatingWindow(Window windowDelegate) {
            this.windowDelegate = windowDelegate;
        }

        @Override
        public Dimension getSize() {
            return windowDelegate.getSize();
        }

        @Override
        public void setSize(Dimension targetSize) {
            windowDelegate.setSize(targetSize);
        }

        @Override
        public Point getPosition() {
            return windowDelegate.getPosition();
        }

        @Override
        public void setPosition(Point targetPosition) {
            windowDelegate.setPosition(targetPosition);
        }

        @Override
        public void maximize() {
            windowDelegate.maximize();
        }

        @Override
        public void minimize() {
            windowDelegate.minimize();
        }

        @Override
        public void fullscreen() {
            windowDelegate.fullscreen();
        }
    }

    public static class DelegatingNavigation implements Navigation {
        private final Navigation navigationDelegate;

        public DelegatingNavigation(Navigation navigationDelegate) {
            this.navigationDelegate = navigationDelegate;
        }

        @Override
        public void back() {
            navigationDelegate.back();
        }

        @Override
        public void forward() {
            navigationDelegate.forward();
        }

        @Override
        public void to(String url) {
            navigationDelegate.to(url);
        }

        @Override
        public void to(URL url) {
            navigationDelegate.to(url);
        }

        @Override
        public void refresh() {
            navigationDelegate.refresh();
        }
    }

    public static class DelegatingTargetLocator implements TargetLocator {
        private final TargetLocator targetLocatorDelegate;

        public DelegatingTargetLocator(TargetLocator targetLocatorDelegate) {
            this.targetLocatorDelegate = targetLocatorDelegate;
        }

        @Override
        public WebDriver frame(int index) {
            return targetLocatorDelegate.frame(index);
        }

        @Override
        public WebDriver frame(String nameOrId) {
            return targetLocatorDelegate.frame(nameOrId);
        }

        @Override
        public WebDriver frame(WebElement frameElement) {
            return targetLocatorDelegate.frame(frameElement);
        }

        @Override
        public WebDriver parentFrame() {
            return targetLocatorDelegate.parentFrame();
        }

        @Override
        public WebDriver window(String nameOrHandle) {
            return targetLocatorDelegate.window(nameOrHandle);
        }

        @Override
        public WebDriver newWindow(WindowType typeHint) {
            return targetLocatorDelegate.newWindow(typeHint);
        }

        @Override
        public WebDriver defaultContent() {
            return targetLocatorDelegate.defaultContent();
        }

        @Override
        public WebElement activeElement() {
            return targetLocatorDelegate.activeElement();
        }

        @Override
        public Alert alert() {
            return targetLocatorDelegate.alert();
        }
    }


    // Javascript executor

    @Override
    public @Nullable Object executeScript(String script, @Nullable Object... args) {
        return ((JavascriptExecutor) searchContext). executeScript( script,  args);
    }

    @Override
    public @Nullable Object executeAsyncScript(String script, @Nullable Object... args) {
        return ((JavascriptExecutor) searchContext). executeAsyncScript( script,  args);
    }

    @Override
    public ScriptKey pin(String script) {
        return ((JavascriptExecutor) searchContext).pin(script);
    }

    @Override
    public void unpin(ScriptKey key) {
        ((JavascriptExecutor) searchContext).unpin(key);
    }

    @Override
    public Set<ScriptKey> getPinnedScripts() {
        return ((JavascriptExecutor) searchContext).getPinnedScripts();
    }

    @Override
    public @Nullable Object executeScript(ScriptKey key, @Nullable Object... args) {
        return ((JavascriptExecutor) searchContext).executeScript(key, args);
    }

}
