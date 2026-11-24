package dev.domengine.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** D9: Grid desktop driver selection, fallback order, doctor rows, and page-source projection. */
class GridDriversTest {
    @AfterEach
    void reset() {
        GridDrivers.resetForTests();
    }

    @Test
    void unsetAndNativeProbeNothing() {
        List<String> probed = new ArrayList<>();
        GridDrivers.Probe probe = url -> {
            probed.add(url);
            return Optional.of("{}");
        };
        assertEquals("native", GridDrivers.select(Map.of(), "windows", probe).driver());
        assertEquals("native", GridDrivers.select(Map.of("pkb_desktop_driver", "native"), "windows", probe).driver());
        assertTrue(probed.isEmpty(), "unset never probes: " + probed);
    }

    @Test
    void autoFollowsTheWindowsOrderAndFallsBackToNative() {
        assertEquals(List.of("flaui", "novawindows", "winappdriver"),
                GridDrivers.forFamily("windows").stream().map(GridDrivers.Driver::name).toList());
        assertEquals(List.of("mac2"), GridDrivers.forFamily("macos").stream().map(GridDrivers.Driver::name).toList());
        assertTrue(GridDrivers.forFamily("linux").isEmpty(), "no Linux Grid desktop driver");
        Map<String, String> auto = Map.of("pkb_desktop_driver", "auto");
        assertEquals("winappdriver", GridDrivers.select(auto, "windows", url -> Optional.of("{\"value\":{\"build\":{\"version\":\"1.2.1\"},\"message\":\"WinAppDriver\"}}")).driver());
        assertEquals("flaui", GridDrivers.select(auto, "windows", url -> Optional.of("{\"value\":{\"ready\":true}}")).driver(),
                "an anonymous /status answer goes to the first driver in order");
        GridDrivers.Choice none = GridDrivers.select(auto, "windows", url -> Optional.empty());
        assertEquals("native", none.driver());
        assertEquals("none-running", none.reason());
        assertEquals("native", GridDrivers.select(auto, "linux", url -> Optional.of("{}")).driver());
        GridDrivers.Choice remote = GridDrivers.select(Map.of("pkb_desktop_driver", "auto", "pkb_desktop_driver_url", "http://grid.example:4444"), "macos",
                url -> url.equals("http://grid.example:4444") ? Optional.of("{\"value\":{\"message\":\"mac2\"}}") : Optional.empty());
        assertEquals("mac2", remote.driver());
        assertEquals("http://grid.example:4444", remote.url());
    }

    @Test
    void explicitDriverChecksFamilyAndRunning() {
        assertEquals("explicit", GridDrivers.select(Map.of("pkb_desktop_driver", "flaui"), "windows", url -> Optional.of("{}")).reason());
        assertEquals("wrong-family", GridDrivers.select(Map.of("pkb_desktop_driver", "mac2"), "windows", url -> Optional.of("{}")).reason());
        assertEquals("not-running", GridDrivers.select(Map.of("pkb_desktop_driver", "winappdriver"), "windows", url -> Optional.empty()).reason());
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> GridDrivers.select(Map.of("pkb_desktop_driver", "selenium"), "windows", url -> Optional.empty()));
        assertTrue(bad.getMessage().startsWith("error: pkb_desktop_driver must be"), bad.getMessage());
    }

    @Test
    void doctorRowsNameWhatIsMissing() {
        JsonNode windows = GridDrivers.report(Map.of(), "windows", url -> Optional.empty(), name -> false);
        assertEquals(3, windows.path("drivers").size());
        JsonNode nova = windows.path("drivers").get(1);
        assertEquals("novawindows", nova.path("name").asText());
        assertFalse(nova.path("running").asBoolean());
        assertTrue(nova.path("missing").toString().contains("appium"), nova.toString());
        assertTrue(nova.path("fix").asText().contains("setup desktop-driver --driver=novawindows"), nova.toString());
        assertTrue(windows.path("drivers").get(2).path("fix").asText().contains("Developer Mode"), windows.toString());
        JsonNode mac = GridDrivers.report(Map.of(), "macos", url -> Optional.of("{\"value\":{\"message\":\"mac2\"}}"), name -> true);
        assertTrue(mac.path("drivers").get(0).path("running").asBoolean(), mac.toString());
        assertEquals("[\"xcode\",\"macos-accessibility\"]", mac.path("drivers").get(0).path("missing").toString());
        assertTrue(GridDrivers.report(Map.of(), "linux", url -> Optional.empty(), name -> true).path("note").asText().contains("AT-SPI"));
        for (GridDrivers.Driver d : GridDrivers.DRIVERS) {
            assertFalse(d.license().isBlank());
            assertTrue(d.source().startsWith("https://"), d.source());
            if (!d.artifact().isBlank()) assertEquals(64, d.sha256().length(), d.name());
        }
    }

    @Test
    void pageSourceBecomesTheCanonicalDesktopTree() throws Exception {
        DomNode windows = PageSourceTree.parse(Files.readString(Path.of("src/test/resources/desktop-drivers/flaui-notepad.xml")), "flaui");
        assertEquals("window", windows.role);
        assertEquals("Untitled - Notepad", windows.accessibleName);
        DomNode save = windows.children.get(1);
        assertEquals("button", save.role);
        assertEquals("Save", save.accessibleName);
        assertEquals("save", save.attributes.get("automation-id"));
        assertEquals("42,1,16", save.attributes.get("uia-runtime-id"));
        assertEquals("/Window[1]/Button[1]", save.attributes.get("grid-xpath"));
        assertEquals("grid", save.attributes.get("desktop-backend"));
        assertEquals(30.0, save.x);
        assertTrue(windows.children.get(2).states.contains("disabled"));
        DomNode mac = PageSourceTree.parse(Files.readString(Path.of("src/test/resources/desktop-drivers/mac2-textedit.xml")), "mac2");
        DomNode button = mac.children.get(0).children.get(1);
        assertEquals("button", button.role);
        assertEquals("Save", button.accessibleName);
        assertEquals("/XCUIElementTypeApplication[1]/XCUIElementTypeWindow[1]/XCUIElementTypeButton[1]", button.attributes.get("grid-xpath"));
        assertEquals("hello", mac.children.get(0).children.get(0).nodeValue);
        assertThrows(IllegalStateException.class, () -> PageSourceTree.parse("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>", "flaui"));
    }

    @Test
    void liveDriverOnlyWhenOneIsRunning() {
        GridDrivers.Choice choice = GridDrivers.select(Map.of("pkb_desktop_driver", "auto"), NativeDesktop.family(), GridDrivers.httpProbe());
        assumeTrue(choice.grid(), "skipped: no FlaUI.WebDriver, NovaWindows, WinAppDriver, or Mac2 driver is running on this machine");
        DomNode root = GridDrivers.capture(choice, null);
        assertTrue(root.children.size() > 0 || !root.accessibleName.isBlank());
    }
}
