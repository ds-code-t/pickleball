package tools.dscode.common.seleniumextensions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import tools.dscode.common.composite.CompositeBinaryLocator;
import tools.dscode.common.composite.CompositeWebElement;
import tools.dscode.common.composite.ElementOperations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ElementWrapper has one call per action. Composite on uses the adapter.
 * Composite off uses the Selenium element through ElementOperations.
 * Composite submit is click: 2.4.0 has no submit verb.
 */
class ElementWrapperActionTest {
    @TempDir
    Path temp;

    private HtmlUnitDriver driver;

    @AfterEach
    void tearDown() {
        if (driver != null) driver.quit();
        System.clearProperty(CompositeBinaryLocator.BIN_KEY);
        SnapshotPages.resetPin();
    }

    @Test
    void seleniumOffClearsTypesClicksAndSubmits() {
        SnapshotPages.pinSelenium();
        driver = SnapshotPages.open();
        ((JavascriptExecutor) driver).executeScript(
                "var form = document.createElement('form');"
                        + "form.id = 'act-form';"
                        + "form.action = 'form-submitted.html';"
                        + "var input = document.createElement('input');"
                        + "input.id = 'act-name';"
                        + "input.value = 'Ada';"
                        + "var button = document.createElement('button');"
                        + "button.id = 'act-go';"
                        + "button.type = 'button';"
                        + "button.setAttribute('onclick', \"this.setAttribute('data-clicked','yes');\");"
                        + "form.appendChild(input);"
                        + "form.appendChild(button);"
                        + "document.body.appendChild(form);"
        );
        ElementWrapper field = ElementWrapper.forResolvedElement(driver, driver.findElement(By.id("act-name")));
        ElementWrapper button = ElementWrapper.forResolvedElement(driver, driver.findElement(By.id("act-go")));
        field.clear();
        assertEquals("", driver.findElement(By.id("act-name")).getAttribute("value"));
        field.sendKeys("Bea");
        assertEquals("Bea", driver.findElement(By.id("act-name")).getAttribute("value"));
        button.click();
        assertEquals("yes", driver.findElement(By.id("act-go")).getAttribute("data-clicked"));
        field.submit();
        assertTrue(driver.getCurrentUrl().contains("form-submitted.html"), driver.getCurrentUrl());
    }

    @Test
    void compositeOnUsesTheAdapterForEachAction() throws Exception {
        driver = SnapshotPages.open();
        ((JavascriptExecutor) driver).executeScript(
                "var button = document.createElement('button');"
                        + "button.id = 'save';"
                        + "button.textContent = 'Save';"
                        + "document.body.appendChild(button);"
        );
        Path log = temp.resolve("calls.log");
        Path binary = stub(temp.resolve("composite"), log);
        System.setProperty(CompositeBinaryLocator.BIN_KEY, binary.toString());
        assertTrue(ElementOperations.primary());
        List<WebElement> found = ElementOperations.findElements(driver, By.cssSelector("#save"));
        CompositeWebElement hit = assertInstanceOf(CompositeWebElement.class, found.get(0));
        ElementWrapper wrapper = ElementWrapper.forResolvedElement(driver, hit);
        wrapper.click();
        wrapper.sendKeys("Ava");
        wrapper.clear();
        wrapper.submit();
        String calls = Files.readString(log);
        assertEquals(2, calls.lines().filter(line -> line.contains("click")).count(), calls);
        assertEquals(2, calls.lines().filter(line -> line.contains("type")).count(), calls);
    }

    private static Path stub(Path path, Path log) throws Exception {
        String script = """
                #!/usr/bin/env python3
                import json, sys
                log = %s
                def note(argv):
                    with open(log, "a", encoding="utf-8") as handle:
                        handle.write(" ".join(argv) + "\\n")
                for line in sys.stdin:
                    line = line.strip()
                    if not line:
                        continue
                    req = json.loads(line)
                    argv = req.get("argv") or []
                    note(argv)
                    if "version" in argv:
                        body = {"version": "2.4.0"}
                    elif "capabilities" in argv:
                        body = {"ok": True, "desktop": ["list", "query", "close"], "actions": ["click", "type", "scroll"]}
                    elif "create" in argv:
                        body = {"ok": True, "id": "sess-1", "launched": False}
                    elif "query" in argv or "click" in argv or "type" in argv:
                        body = {"ok": True, "composite": [{
                            "id": "main:1",
                            "text": "Save",
                            "role": "button",
                            "bounds": {"x": 1, "y": 2, "width": 10, "height": 8},
                            "attributes": {"id": "save"},
                            "states": ["enabled"],
                            "source": "Dom"
                        }]}
                    else:
                        body = {"ok": True}
                    sys.stdout.write(json.dumps({
                        "id": req.get("id"),
                        "exit": 0,
                        "stdout": json.dumps(body) + "\\n",
                        "stderr": ""
                    }) + "\\n")
                    sys.stdout.flush()
                """.formatted(quote(log.toString()));
        Files.createDirectories(path.getParent());
        Files.writeString(path, script);
        Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
        return path;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
