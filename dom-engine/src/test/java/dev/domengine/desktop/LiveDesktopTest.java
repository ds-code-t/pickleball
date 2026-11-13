package dev.domengine.desktop;

import dev.domengine.cdp.CdpException;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.query.Query;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveDesktopTest {
    @AfterEach
    void reset() {
        DesktopTree.resetForTests();
    }

    @Test
    void liveAttachFailsClearlyAndForeignPlatformsDoNotRun() {
        AtSpi.useSessionBusForTests("unix:path=/tmp/pickleball-no-atspi-bus");
        CliResult live = DomEngineMain.dispatch(List.of("desktop", "attach", "--id", "live1", "--live"));
        assertEquals(1, live.exit(), live.stderr());
        assertTrue(live.stderr().contains("AT-SPI unavailable"), live.stderr());
        assertFalse(live.stderr().contains("\"ok\" : true"), live.stderr());
        assertFalse(live.stdout().contains("\"ok\" : true"), live.stdout());
        assertFalse(live.stdout().contains("\"ok\":true"), live.stdout());
        assertFalse(live.stderr().contains("\"ok\": true"), live.stderr());
        assertFalse(live.stdout().contains("\"ok\": true"), live.stdout());

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
        assertEquals("unknown", AtSpi.roleName(22));
        assertEquals("unknown", AtSpi.roleName(42));
        assertEquals("unknown", AtSpi.roleName(78));
        assertNotEquals("button", AtSpi.roleName(42));
        assertNotEquals("radio", AtSpi.roleName(43));
        assertNotEquals("textbox", AtSpi.roleName(39));
        assertNotEquals("menuitem", AtSpi.roleName(34));
        assertEquals("button", AtSpi.roleName(43));
        assertEquals("button", AtSpi.roleName(62));
        assertEquals("radio", AtSpi.roleName(44));
        assertEquals("window", AtSpi.roleName(14));
        assertEquals("window", AtSpi.roleName(23));
        assertEquals("window", AtSpi.roleName(69));
        assertEquals("menu", AtSpi.roleName(33));
        assertEquals("menubar", AtSpi.roleName(34));
        assertEquals("menuitem", AtSpi.roleName(35));
        assertEquals("tab", AtSpi.roleName(37));
        assertEquals("panel", AtSpi.roleName(39));
        assertEquals("terminal", AtSpi.roleName(60));
        assertEquals("textbox", AtSpi.roleName(61));
        assertEquals("application", AtSpi.roleName(75));
        assertEquals("checkbox", AtSpi.roleName(7));
        assertEquals("dialog", AtSpi.roleName(16));
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

    @Test
    void accessibilityBusCallUsesSessionAndNotAddress() {
        AtSpi.BusCall role = AtSpi.busCall(
                "/usr/bin/dbus-send",
                "unix:path=/tmp/at-spi/bus_7,guid=abc",
                "org.a11y.atspi.Registry",
                "/org/a11y/atspi/accessible/root",
                AtSpi.GET_ROLE
        );
        assertTrue(role.command().contains("--session"), role.command().toString());
        assertFalse(role.command().stream().anyMatch(part -> part.startsWith("--address=")), role.command().toString());
        assertFalse(role.command().stream().anyMatch(part -> part.startsWith("--bus=")), role.command().toString());
        assertEquals("unix:path=/tmp/at-spi/bus_7,guid=abc", role.env().get("DBUS_SESSION_BUS_ADDRESS"));
        assertEquals(AtSpi.GET_ROLE, role.command().get(role.command().size() - 1));
        assertFalse(AtSpi.GET_ROLE.contains("Properties"));
        assertFalse(AtSpi.GET_ROLE_NAME.contains("Properties"));

        AtSpi.BusCall roleName = AtSpi.busCall(
                "/usr/bin/dbus-send", "unix:path=/tmp/at-spi/bus_7",
                ":1.2", "/org/a11y/atspi/accessible/1", AtSpi.GET_ROLE_NAME
        );
        assertEquals(AtSpi.GET_ROLE_NAME, roleName.command().get(roleName.command().size() - 1));
        assertFalse(roleName.command().contains(AtSpi.PROPERTIES_GET), roleName.command().toString());
        assertFalse(roleName.command().contains("string:Role"), roleName.command().toString());

        AtSpi.BusCall name = AtSpi.busCall(
                "/usr/bin/dbus-send", "unix:path=/tmp/at-spi/bus_7",
                ":1.2", "/org/a11y/atspi/accessible/1",
                AtSpi.PROPERTIES_GET, "string:org.a11y.atspi.Accessible", "string:Name"
        );
        assertTrue(name.command().contains("string:Name"), name.command().toString());
        assertFalse(name.command().contains("string:Role"), name.command().toString());
        assertEquals("window", AtSpi.mappedRole("frame", 43));
        assertEquals("button", AtSpi.mappedRole("button", 39));
        assertEquals("button", AtSpi.mappedRole("", 43));
        assertEquals("panel", AtSpi.mappedRole(null, 39));
    }

    @Test
    void namedFrameAndButtonRoleComeFromGetRoleName() {
        assertEquals("window", NativeDesktop.mapRole("atspi", "frame"));
        assertEquals("window", NativeDesktop.mapRole("atspi", "desktop frame"));
        assertEquals("button", NativeDesktop.mapRole("atspi", "button"));
        assertEquals("panel", NativeDesktop.mapRole("atspi", "panel"));
        assertEquals("terminal", NativeDesktop.mapRole("atspi", "terminal"));
        assertEquals("menu", NativeDesktop.mapRole("atspi", "menu"));
        assertEquals("menubar", NativeDesktop.mapRole("atspi", "menu bar"));
        assertEquals("menuitem", NativeDesktop.mapRole("atspi", "menu item"));
        assertEquals("tab", NativeDesktop.mapRole("atspi", "page tab"));
        assertEquals("application", NativeDesktop.mapRole("atspi", "application"));
        assertEquals("window", AtSpi.mappedRole("desktop frame", 14));

        DomNode root = node("desktop", "Desktop", 1);
        DomNode frame = node(AtSpi.mappedRole("frame", 23), "PickleballLiveTest", 2);
        frame.attributes.put("atspi-role", "23");
        DomNode copy = node(AtSpi.mappedRole("button", 43), "Copy", 3);
        copy.attributes.put("atspi-role", "43");
        DomNode paste = node(AtSpi.mappedRole("button", 43), "Paste", 4);
        DomNode panel = node(AtSpi.mappedRole("panel", 39), "Toolbar", 5);
        frame.children.add(copy);
        frame.children.add(paste);
        frame.children.add(panel);
        root.children.add(frame);

        DomNode picked = NativeDesktop.selectWindow(root, "PickleballLiveTest");
        assertEquals(1, picked.children.size());
        assertEquals("window", picked.children.get(0).role);
        assertEquals("PickleballLiveTest", picked.children.get(0).accessibleName);

        var found = Query.run(picked, Map.of("pipeline", "role=button"));
        assertEquals(2, found.path("nodes").size());
        assertEquals("button", found.path("nodes").get(0).path("role").asText());
        assertEquals("Copy", found.path("composite").get(0).path("name").asText());
        assertEquals("Paste", found.path("composite").get(1).path("name").asText());
        assertEquals(0, Query.run(picked, Map.of("pipeline", "role=radio")).path("nodes").size());
    }

    @Test
    void liveAttachSucceedsWhenAccessibilityBusIsUp() {
        Assumptions.assumeTrue(accessibilityBusIsUp(), "no AT-SPI bus");
        CliResult live = DomEngineMain.dispatch(List.of("desktop", "attach", "--id", "liveok", "--live"));
        assertEquals(0, live.exit(), live.stderr());
        assertTrue(live.stdout().contains("\"ok\":true") || live.stdout().contains("\"ok\": true")
                || live.stdout().contains("\"ok\" : true"), live.stdout());
    }

    private static boolean accessibilityBusIsUp() {
        try {
            AtSpi.busAddress();
            return true;
        } catch (CdpException ignored) {
            return false;
        }
    }

    private static DomNode node(String role, String name, long id) {
        DomNode node = new DomNode();
        node.nodeType = 1;
        node.displayed = true;
        node.frameId = "desktop";
        node.backendNodeId = id;
        node.nodeId = id;
        node.role = role;
        node.localName = role;
        node.nodeName = role.toUpperCase();
        node.accessibleName = name;
        return node;
    }
}
