package dev.domengine.desktop;

import dev.domengine.cdp.CdpException;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveDesktopTest {
    @AfterEach
    void reset() {
        DesktopTree.resetForTests();
    }

    @Test
    void liveAttachFailsClearlyAndForeignPlatformsDoNotRun() {
        CliResult live = DomEngineMain.dispatch(List.of("desktop", "attach", "--id", "live1", "--live"));
        assertEquals(1, live.exit(), live.stderr());
        assertTrue(live.stderr().contains("AT-SPI"), live.stderr());
        assertTrue(live.stderr().contains("session bus"), live.stderr());
        assertFalse(live.stdout().contains("\"ok\" : true"), live.stdout());

        CdpException windows = assertThrows(CdpException.class, () -> WindowsUia.capture(""));
        assertTrue(windows.getMessage().contains("requires Windows"), windows.getMessage());
        CdpException mac = assertThrows(CdpException.class, () -> MacAccessibility.capture(""));
        assertTrue(mac.getMessage().contains("requires macOS"), mac.getMessage());

        DomNode node = new DomNode();
        node.attributes.put("desktop-backend", "atspi");
        node.attributes.put("atspi-dest", "org.a11y.atspi.Registry");
        node.attributes.put("atspi-path", "/org/a11y/atspi/accessible/root");
        CdpException click = assertThrows(CdpException.class, () -> NativeDesktop.click(node));
        assertTrue(click.getMessage().contains("AT-SPI"), click.getMessage());

        assertTrue(WindowsUia.captureScript().contains("UIAutomationClient"));
        assertTrue(WindowsUia.clickScript().contains("InvokePattern"));
        assertTrue(WindowsUia.typeScript().contains("ValuePattern"));
        assertTrue(MacAccessibility.captureScript().contains("System Events"));
        assertTrue(MacAccessibility.clickScript().contains("AXPress"));
        assertEquals(12, NativeDesktop.WALK_DEPTH);
        assertEquals(500, NativeDesktop.WALK_NODES);
        assertTrue(NativeDesktop.platformId().startsWith("linux-"));
    }

    @Test
    void atspiRepliesAndLineTreesMapOntoTheSharedNode() {
        String sample = """
                method return time=1 sender=:1.0 -> destination=:1.1 serial=2 reply_serial=1
                   array [
                      struct {
                         string ":1.23"
                         object path "/org/a11y/atspi/accessible/0"
                      }
                      struct {
                         string ":1.24"
                         object path "/org/a11y/atspi/accessible/root"
                      }
                   ]
                """;
        assertEquals(2, AtSpi.objectRefs(sample).size());
        assertEquals(":1.23", AtSpi.objectRefs(sample).get(0).dest());
        assertEquals("/org/a11y/atspi/accessible/0", AtSpi.objectRefs(sample).get(0).path());
        assertEquals("unix:path=/tmp/bus", AtSpi.strings("   string \"unix:path=/tmp/bus\"\n").get(0));
        assertEquals(42, AtSpi.lastInt("variant uint32 42", -1));
        assertEquals("button", AtSpi.roleName(42));
        assertEquals("textbox", AtSpi.roleName(78));
        assertEquals("window", AtSpi.roleName(22));
        assertEquals("unknown", AtSpi.roleName(999));

        DomNode root = NativeDesktop.parseLines("""
                NODE\t0\tdesktop\t0\t0\t0\t0\t\tDesktop
                NODE\t1\tButton\t4\t6\t20\t10\t7.1\tSave
                NODE\t1\tAXTextField\t4\t30\t80\t16\t1/2\tTitle
                """, "ax");
        assertEquals("desktop", root.role);
        assertEquals("button", root.children.get(0).role);
        assertEquals("Save", root.children.get(0).accessibleName);
        assertEquals("textbox", root.children.get(1).role);
        assertEquals("1/2", root.children.get(1).attributes.get("ax-path"));

        DomNode window = new DomNode();
        window.role = "window";
        window.accessibleName = "Editor";
        root.children.add(window);
        DomNode picked = NativeDesktop.selectWindow(root, "Edit");
        assertEquals("Editor", picked.children.get(0).accessibleName);
        CdpException missing = assertThrows(CdpException.class, () -> NativeDesktop.selectWindow(root, "Missing"));
        assertTrue(missing.getMessage().contains("not found"), missing.getMessage());
    }

    @Test
    void fixtureStaysInMemoryAndBrowserOnlyStagesFail() throws Exception {
        Path fixture = Files.createTempFile("desktop", ".json");
        Files.writeString(fixture, """
                {"role":"window","name":"Fixture","children":[
                  {"role":"button","name":"Save","x":4,"y":6,"width":20,"height":10}
                ]}
                """);
        CliResult attached = DomEngineMain.dispatch(List.of(
                "desktop", "attach", "--id", "desk", "--fixture", fixture.toString()
        ));
        assertEquals(0, attached.exit(), attached.stderr());
        assertEquals("fixture", Jsons.MAPPER.readTree(attached.stdout()).path("backend").asText());
        assertFalse(Jsons.MAPPER.readTree(attached.stdout()).path("live").asBoolean());

        CliResult clicked = DomEngineMain.dispatch(List.of(
                "desktop", "click", "--id", "desk", "--pipeline", "role=button[name=\"Save\"]"
        ));
        assertEquals(0, clicked.exit(), clicked.stderr());
        assertEquals("fixture", Jsons.MAPPER.readTree(clicked.stdout()).path("backend").asText());

        CliResult pierced = DomEngineMain.dispatch(List.of(
                "desktop", "query", "--id", "desk", "--pipeline", "role=button", "--pierce", "open"
        ));
        assertEquals(1, pierced.exit(), pierced.stderr());
        assertTrue(pierced.stderr().contains("pierce is browser-only"), pierced.stderr());

        CliResult framed = DomEngineMain.dispatch(List.of(
                "desktop", "query", "--id", "desk", "--pipeline", "frame=#child >> css=#framed"
        ));
        assertEquals(1, framed.exit(), framed.stderr());
        assertTrue(framed.stderr().contains("browser-only"), framed.stderr());

        CliResult both = DomEngineMain.dispatch(List.of(
                "desktop", "attach", "--id", "both", "--live", "--fixture", fixture.toString()
        ));
        assertEquals(1, both.exit(), both.stderr());
        assertTrue(both.stderr().contains("not both"), both.stderr());

        CliResult help = DomEngineMain.dispatch(List.of("help"));
        assertTrue(help.stdout().contains("--live"), help.stdout());
        Files.deleteIfExists(fixture);
    }
}
