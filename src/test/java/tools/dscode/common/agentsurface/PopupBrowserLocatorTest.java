package tools.dscode.common.agentsurface;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupBrowserLocatorTest {
    @Test
    void propertyBeatsEnvAndTheDomEngineChromeVariable() {
        Fake lookup = new Fake();
        lookup.properties.put("pkb_agent_popup_browser", "/opt/edge");
        lookup.env.put("PKB_AGENT_POPUP_BROWSER", "/opt/chrome");
        lookup.env.put("DOMENGINE_CHROME", "/opt/chromium");
        lookup.files.add("/opt/edge");
        lookup.files.add("/opt/chrome");
        PopupBrowserLocator.Hit hit = PopupBrowserLocator.locate(lookup);
        assertEquals("/opt/edge", hit.path().orElse(""));
        assertEquals("property", hit.probes().get(0).name());
        assertTrue(hit.probes().get(0).hit());
    }

    @Test
    void windowsPrefersEdgeFromTheRegistry() {
        Fake lookup = new Fake();
        lookup.os = "Windows 11";
        lookup.reg.put("HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\msedge.exe", "C:\\Edge\\msedge.exe");
        lookup.files.add("C:\\Edge\\msedge.exe");
        PopupBrowserLocator.Hit hit = PopupBrowserLocator.locate(lookup);
        assertEquals("C:\\Edge\\msedge.exe", hit.path().orElse(""));
        assertTrue(hit.probes().stream().anyMatch(probe -> probe.name().startsWith("reg ") && probe.hit()));
    }

    @Test
    void noneFoundKeepsTheProbeList() {
        Fake lookup = new Fake();
        lookup.os = "Linux";
        PopupBrowserLocator.Hit hit = PopupBrowserLocator.locate(lookup);
        assertTrue(hit.path().isEmpty());
        assertTrue(hit.probes().stream().anyMatch(probe -> probe.name().contains("google-chrome")));
        assertTrue(hit.probes().stream().anyMatch(probe -> probe.name().equals("flatpak")));
    }

    private static final class Fake implements PopupBrowserLocator.Lookup {
        final Map<String, String> properties = new LinkedHashMap<>();
        final Map<String, String> env = new LinkedHashMap<>();
        final Map<String, String> reg = new LinkedHashMap<>();
        final List<String> files = new ArrayList<>();
        String os = "Linux";

        @Override public String property(String name) { return properties.get(name); }
        @Override public String env(String name) { return env.get(name); }
        @Override public String osName() { return os; }
        @Override public boolean exists(String path) { return files.contains(path); }
        @Override public String regQuery(String spec) { return reg.getOrDefault(spec, ""); }
        @Override public List<String> programFiles() { return List.of("C:\\Program Files"); }
        @Override public String home() { return "/home/me"; }
        @Override public String pathWhich(String name) { return ""; }
    }
}
