package tools.dscode.common.seleniumextensions;

import dev.domcomposite.StaleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import tools.dscode.common.composite.CompositeElements;
import tools.dscode.common.composite.CompositeWebElement;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One refresh path. Selenium stale and composite stale both re-find by
 * stableSelector. A missing node fails with the selector, the mode, and a fix.
 */
class ElementWrapperRefreshTest {
    private HtmlUnitDriver driver;

    @BeforeEach
    void setUp() {
        SnapshotPages.pinSelenium();
        driver = SnapshotPages.open();
        driver.manage().timeouts().scriptTimeout(java.time.Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        if (driver != null) driver.quit();
        SnapshotPages.resetPin();
    }

    @Test
    void readsDoNotRefresh() {
        ElementWrapper wrapper = wrapTarget();
        ElementSnapshot first = wrapper.snapshot();
        assertEquals("div", wrapper.getTagName());
        assertSame(first, wrapper.snapshot());
    }

    @Test
    void seleniumStaleRefreshKeepsPositionsAndReadsTheNewText() {
        ElementWrapper wrapper = wrapTarget();
        assertEquals(3, wrapper.getIndex());
        assertEquals(1, wrapper.getSameTagIndex());
        assertEquals("#target", wrapper.snapshot().stableSelector());
        replaceTarget();
        wrapper.element = throwing(StaleElementReferenceException.class);
        String text = wrapper.getText();
        assertTrue(text.contains("After"), text);
        assertTrue(wrapper.snapshot().text().contains("After"), wrapper.snapshot().text());
        assertEquals(3, wrapper.snapshot().siblingIndex());
        assertEquals(1, wrapper.snapshot().sameTagIndex());
        assertEquals("sv", wrapper.snapshot().selectedValue());
        assertEquals("#target", wrapper.snapshot().stableSelector());
        assertNull(wrapper.snapshot().nodeId());
        assertNull(wrapper.snapshot().bounds());
    }

    @Test
    void compositeHeldStaleRefreshReadsTheLiveNode() {
        CompositeWebElement hit = CompositeElements.of("target", "div", "STALE-CACHED", 8, 9, 10, 11);
        ElementWrapper wrapper = ElementWrapper.forResolvedElement(driver, hit);
        assertEquals("main:42", wrapper.snapshot().nodeId());
        assertTrue(wrapper.snapshot().text().contains("Hello"), wrapper.snapshot().text());
        replaceTarget();
        wrapper.element = throwing(StaleException.class);
        String text = wrapper.getText();
        assertTrue(text.contains("After"), text);
        assertTrue(wrapper.snapshot().text().contains("After"), wrapper.snapshot().text());
        assertEquals(3, wrapper.snapshot().siblingIndex());
        assertEquals(1, wrapper.snapshot().sameTagIndex());
        assertEquals("sv", wrapper.snapshot().selectedValue());
        assertNull(wrapper.snapshot().nodeId());
    }

    @Test
    void missingNodeFailsClearlyInSeleniumMode() {
        ElementWrapper wrapper = wrapTarget();
        removeTarget();
        wrapper.element = throwing(StaleElementReferenceException.class);
        IllegalStateException error = assertThrows(IllegalStateException.class, wrapper::getText);
        assertRefreshError(error, "selenium", "#target", "");
    }

    @Test
    void missingNodeFailsClearlyWhileTheElementIsStillComposite() {
        CompositeWebElement hit = CompositeElements.of("target", "div", "STALE-CACHED", 1, 2, 3, 4);
        ElementWrapper wrapper = ElementWrapper.forResolvedElement(driver, hit);
        removeTarget();
        IllegalStateException error = assertThrows(IllegalStateException.class, wrapper::refreshSnapshot);
        assertRefreshError(error, "composite", "#target", "main:42");
        assertTrue(wrapper.getWrappedElement() instanceof CompositeWebElement);
    }

    @Test
    void moreThanOneMatchFailsClearly() {
        ElementWrapper wrapper = wrapTarget();
        ((JavascriptExecutor) driver).executeScript(
                "var t = document.getElementById('target');"
                        + "t.parentNode.appendChild(t.cloneNode(true));");
        IllegalStateException error = assertThrows(IllegalStateException.class, wrapper::refreshSnapshot);
        assertRefreshError(error, "selenium", "#target", "");
        assertTrue(error.getMessage().contains("matched 2"), error.getMessage());
    }

    private ElementWrapper wrapTarget() {
        return ElementWrapper.forResolvedElement(driver, SnapshotPages.byId(driver, "target"));
    }

    private void replaceTarget() {
        ((JavascriptExecutor) driver).executeScript(
                "var old = document.getElementById('target');"
                        + "var neu = document.createElement('div');"
                        + "neu.id = 'target';"
                        + "neu.setAttribute('data-testid', 'target');"
                        + "neu.setAttribute('name', 'targetName');"
                        + "neu.setAttribute('title', 'Target Title');"
                        + "var span = document.createElement('span');"
                        + "span.id = 'sel';"
                        + "span.setAttribute('selected', '');"
                        + "span.setAttribute('value', 'sv');"
                        + "span.appendChild(document.createTextNode('Sel'));"
                        + "neu.appendChild(span);"
                        + "neu.appendChild(document.createTextNode(' After'));"
                        + "old.parentNode.replaceChild(neu, old);");
    }

    private void removeTarget() {
        ((JavascriptExecutor) driver).executeScript(
                "var old = document.getElementById('target'); old.parentNode.removeChild(old);");
    }

    private static void assertRefreshError(IllegalStateException error, String mode, String selector, String nodeId) {
        String message = error.getMessage();
        assertTrue(message.startsWith("error:"), message);
        assertTrue(message.contains("mode=" + mode), message);
        assertTrue(message.contains("selector=" + selector), message);
        assertTrue(message.contains("nodeId=" + nodeId), message);
        assertTrue(message.contains("tried=stableSelector"), message);
        assertTrue(message.contains("fix:"), message);
    }

    private static WebElement throwing(Class<? extends RuntimeException> type) {
        return (WebElement) Proxy.newProxyInstance(
                WebElement.class.getClassLoader(),
                new Class<?>[]{WebElement.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "stale-proxy";
                            case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                            case "equals" -> Boolean.valueOf(proxy == (args == null ? null : args[0]));
                            default -> null;
                        };
                    }
                    if ("getText".equals(method.getName())) {
                        if (type == StaleException.class) {
                            throw new StaleException("forced stale", 1, null, null);
                        }
                        throw new StaleElementReferenceException("forced stale");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
