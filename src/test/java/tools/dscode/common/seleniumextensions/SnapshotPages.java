package tools.dscode.common.seleniumextensions;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import tools.dscode.common.composite.CompositeRuntime;

import java.net.URL;
import java.nio.file.Path;
import java.util.Objects;

/** Hermetic page for snapshot tests. Does not look outside this repo for a composite binary. */
final class SnapshotPages {
    private SnapshotPages() {
    }

    static void pinSelenium() {
        Path root = Path.of("target", "element-snapshot-pin");
        CompositeRuntime.use(root, java.util.Map.of(), new ClassLoader(null) {
        }, root.resolve("home"));
    }

    static void resetPin() {
        CompositeRuntime.resetForTests();
    }

    static HtmlUnitDriver open() {
        HtmlUnitDriver driver = new HtmlUnitDriver(true);
        URL page = SnapshotPages.class.getResource("/element-snapshot/fixture.html");
        driver.get(Objects.requireNonNull(page, "fixture").toString());
        return driver;
    }

    static WebElement byId(WebDriver driver, String id) {
        return driver.findElement(By.id(id));
    }
}
