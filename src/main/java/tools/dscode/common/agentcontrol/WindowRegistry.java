package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.browseroperations.WindowSwitch;
import tools.dscode.common.composite.CompositeLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Window and tab handles. Live drivers use {@link WindowSwitch}; otherwise the registry. */
final class WindowRegistry {
    private WindowRegistry() {
    }

    record Handle(String handle, String kind, String template, String title, String url, boolean open) {
    }

    static List<Handle> list(Path project, String runId) throws IOException {
        List<Handle> stored = read(project, runId);
        WebDriver driver = PageSurfaces.driver();
        if (driver == null) return stored;
        List<Handle> live = new ArrayList<>();
        try {
            for (String handle : driver.getWindowHandles()) {
                live.add(new Handle(handle, "window", "", "", "", true));
            }
        } catch (RuntimeException failure) {
            return stored;
        }
        return live.isEmpty() ? stored : live;
    }

    static Handle open(Path project, String runId, String template, String kind) throws IOException {
        List<Handle> all = new ArrayList<>(read(project, runId));
        String handle = kind + "-" + (all.size() + 1);
        Handle created = new Handle(handle, kind, template == null ? "" : template, "", "", true);
        all.add(created);
        write(project, runId, all);
        CompositeLog.info("window", "open " + kind + " " + handle + " template=" + template);
        return created;
    }

    static Handle switchTo(Path project, String runId, String handle) throws IOException {
        if (handle == null || handle.isBlank()) {
            throw new IllegalArgumentException("missing handle");
        }
        WebDriver driver = PageSurfaces.driver();
        if (driver != null) {
            WindowSwitch.switchToHandleOrThrow(driver, handle);
            CompositeLog.info("window", "switch " + handle);
            return new Handle(handle, "window", "", safeTitle(driver), safeUrl(driver), true);
        }
        Handle found = require(read(project, runId), handle);
        CompositeLog.info("window", "switch " + handle);
        return found;
    }

    static Handle close(Path project, String runId, String handle) throws IOException {
        if (handle == null || handle.isBlank()) {
            throw new IllegalArgumentException("missing handle");
        }
        WebDriver driver = PageSurfaces.driver();
        if (driver != null && driver.getWindowHandles().contains(handle)) {
            String current = driver.getWindowHandle();
            driver.switchTo().window(handle);
            driver.close();
            if (!current.equals(handle) && driver.getWindowHandles().contains(current)) {
                driver.switchTo().window(current);
            } else if (!driver.getWindowHandles().isEmpty()) {
                driver.switchTo().window(driver.getWindowHandles().iterator().next());
            }
            CompositeLog.info("window", "close " + handle);
            return new Handle(handle, "window", "", "", "", false);
        }
        List<Handle> all = new ArrayList<>();
        Handle closed = null;
        for (Handle item : read(project, runId)) {
            if (item.handle().equals(handle)) {
                closed = new Handle(item.handle(), item.kind(), item.template(), item.title(), item.url(), false);
                all.add(closed);
            } else {
                all.add(item);
            }
        }
        if (closed == null) throw new IllegalArgumentException("unknown handle: " + handle);
        write(project, runId, all);
        CompositeLog.info("window", "close " + handle);
        return closed;
    }

    private static Handle require(List<Handle> all, String handle) {
        for (Handle item : all) {
            if (item.handle().equals(handle) && item.open()) return item;
        }
        throw new IllegalArgumentException("unknown handle: " + handle);
    }

    private static List<Handle> read(Path project, String runId) throws IOException {
        Path file = file(project, runId);
        if (!Files.isRegularFile(file)) return List.of();
        JsonNode root = AgentStore.JSON.readTree(file.toFile());
        List<Handle> all = new ArrayList<>();
        if (!root.isArray()) return all;
        for (JsonNode node : root) {
            all.add(new Handle(
                    node.path("handle").asText(""),
                    node.path("kind").asText("window"),
                    node.path("template").asText(""),
                    node.path("title").asText(""),
                    node.path("url").asText(""),
                    node.path("open").asBoolean(true)
            ));
        }
        return all;
    }

    private static void write(Path project, String runId, List<Handle> all) throws IOException {
        Path file = file(project, runId);
        AgentStore.ensure(file.getParent());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Handle item : all) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("handle", item.handle());
            row.put("kind", item.kind());
            row.put("template", item.template());
            row.put("title", item.title());
            row.put("url", item.url());
            row.put("open", item.open());
            rows.add(row);
        }
        AgentStore.JSON.writeValue(file.toFile(), rows);
    }

    private static Path file(Path project, String runId) {
        return AgentStore.root(project, runId).resolve("windows.json");
    }

    static Map<String, Object> toMap(Handle handle) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("handle", handle.handle());
        map.put("kind", handle.kind());
        map.put("template", handle.template());
        map.put("title", handle.title());
        map.put("url", handle.url());
        map.put("open", handle.open());
        return map;
    }

    private static String safeTitle(WebDriver driver) {
        try {
            return driver.getTitle();
        } catch (RuntimeException failure) {
            return "";
        }
    }

    private static String safeUrl(WebDriver driver) {
        try {
            return driver.getCurrentUrl();
        } catch (RuntimeException failure) {
            return "";
        }
    }
}
