package tools.dscode.common.composite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebElement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectorAndAdapterTest {
    @TempDir
    Path temp;

    @AfterEach
    void clean() {
        System.clearProperty(CompositeBinaryLocator.BIN_KEY);
        System.clearProperty(CompositeMode.KEY);
        System.clearProperty(CompositeLog.KEY);
        System.clearProperty(CompositeLog.DIR_KEY);
        CompositeRuntime.resetForTests();
    }

    @Test
    void phrasesKeepMatchQuoteContextAndCategory() {
        CompositeSelector button = SelectorTranslator.phrase(ElementPhrase.of("Button", "Save"));
        assertTrue(button.pipeline().contains("role=button"), button.pipeline());
        assertTrue(button.pipeline().contains("text=\"Save\""), button.pipeline());

        CompositeSelector partial = SelectorTranslator.phrase(ElementPhrase.of("Link", "Log").match("containing").quote("single"));
        assertTrue(partial.pipeline().contains("text~=\"log\""), partial.pipeline());
        assertTrue(partial.pipeline().contains("role=link"), partial.pipeline());

        CompositeSelector full = SelectorTranslator.phrase(ElementPhrase.of("Button", "Save").match("matching"));
        assertTrue(full.pipeline().contains("text=\"^Save$\""), full.pipeline());

        CompositeSelector context = SelectorTranslator.phrase(
                ElementPhrase.of("Button", "Save").context("in", "Billing", "Section"));
        assertTrue(context.pipeline().startsWith("role=region"), context.pipeline());
        assertTrue(context.pipeline().contains("text=\"Billing\" >> role=button"), context.pipeline());

        CompositeSelector after = SelectorTranslator.phrase(
                ElementPhrase.of("Button", "Save").context("after", "Billing", "Section"));
        assertTrue(after.pipeline().contains("below=1"), after.pipeline());

        CompositeSelector custom = SelectorTranslator.phrase(ElementPhrase.of("Product Card", "Mug"));
        assertTrue(custom.pipeline().contains("desc=\"Product Card\""), custom.pipeline());

        CompositeSelector xpath = SelectorTranslator.phrase(ElementPhrase.of("Button", "Save").xpath("//button[@id='save']"));
        assertEquals("//button[@id='save']", xpath.xpath());
        assertTrue(xpath.pipeline() == null);

        CompositeSelector css = SelectorTranslator.fromBy(By.cssSelector("#go"));
        assertEquals("#go", css.css());

        CompositeSelector desktop = SelectorTranslator.phrase(
                ElementPhrase.of("Button", "Save").source("desktop").template("mark.png").ocr("Save"));
        assertTrue(desktop.pipeline().startsWith("source=desktop"), desktop.pipeline());
        assertTrue(desktop.pipeline().contains("image=template=mark.png"), desktop.pipeline());
        assertTrue(desktop.pipeline().contains("image=ocr~=\"Save\""), desktop.pipeline());
        assertEquals(-1, SelectorTranslator.ordinal(ElementPhrase.of("Button", "Save").ordinal("last")));
        assertEquals(1, SelectorTranslator.ordinal(ElementPhrase.of("Button", "Save").ordinal("2nd")));
    }

    @Test
    void adapterClicksTypesAndQueriesDesktopThroughOneClient() throws Exception {
        Path log = temp.resolve("calls.log");
        Path binary = stub(temp.resolve("composite"), log);
        System.setProperty(CompositeBinaryLocator.BIN_KEY, binary.toString());
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        assertTrue(ElementOperations.primary());
        List<WebElement> found = ElementOperations.findElements(null, By.xpath("//button"));
        assertEquals(1, found.size());
        CompositeWebElement element = assertInstanceOf(CompositeWebElement.class, found.get(0));
        assertEquals("Save", element.getText());
        assertTrue(element.isDisplayed());
        element.click();
        element.sendKeys("Ava");
        ElementOperations.hover(null, element);
        ElementOperations.scroll(null, element, 20);
        ElementOperations.drag(null, element, element, 4, 6);
        ElementOperations.keys(null, "Enter");
        ElementOperations.JsonDesktop desktop = ElementOperations.desktop("query", ElementPhrase.of("Button", "Save"));
        assertEquals(1, desktop.size());
        String calls = Files.readString(log);
        assertTrue(calls.contains("click"), calls);
        assertTrue(calls.contains("type"), calls);
        assertTrue(calls.contains("desktop"), calls);
        assertFalse(calls.isBlank());
    }

    @Test
    void missingBinaryUsesTheLegacySearchContext() {
        System.setProperty(CompositeMode.KEY, "off");
        AtomicInteger hits = new AtomicInteger();
        SearchContext context = new SearchContext() {
            @Override
            public List<WebElement> findElements(By by) {
                hits.incrementAndGet();
                return List.of();
            }

            @Override
            public WebElement findElement(By by) {
                hits.incrementAndGet();
                throw new NoSuchElementException(String.valueOf(by));
            }
        };
        assertFalse(ElementOperations.primary());
        assertTrue(ElementOperations.findElements(context, By.id("save")).isEmpty());
        assertEquals(1, hits.get());
    }

    private static Path stub(Path path, Path log) throws Exception {
        String script = """
                #!/usr/bin/env python3
                import json, sys
                log = sys.argv[1] if False else %s
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
                    joined = " ".join(argv)
                    if "version" in argv:
                        body = {"version": "2.4.0"}
                    elif "create" in argv:
                        body = {"ok": True, "id": "sess-1", "launched": False}
                    elif "desktop" in argv or "query" in argv or "click" in argv or "type" in argv or "scroll" in argv:
                        body = {"ok": True, "composite": [{
                            "id": "main:1",
                            "text": "Save",
                            "role": "button",
                            "bounds": {"x": 1, "y": 2, "width": 10, "height": 8},
                            "attributes": {"id": "save"},
                            "states": ["enabled"],
                            "source": "Dom" if "desktop" not in argv else "Desktop"
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
