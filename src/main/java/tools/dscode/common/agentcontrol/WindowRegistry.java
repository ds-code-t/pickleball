package tools.dscode.common.agentcontrol;

import org.openqa.selenium.NoSuchWindowException;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.browseroperations.WindowSwitch;
import tools.dscode.common.composite.CompositeLog;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live browser instances are windows. Selenium handles inside one instance are tabs.
 * Nothing here invents {@code window-1} or {@code tab-2} when no driver is open.
 */
final class WindowRegistry {
    private WindowRegistry() {
    }

    record Handle(String handle, String kind, String template, String title, String url, boolean open) {
    }

    static List<Handle> list(Path project, String runId) {
        List<Handle> live = new ArrayList<>();
        for (PageSurfaces.Managed managed : PageSurfaces.managed()) {
            List<String> tabs = new ArrayList<>();
            try {
                if (managed.driver().getWindowHandles() != null) {
                    tabs.addAll(managed.driver().getWindowHandles());
                }
            } catch (RuntimeException failure) {
                CompositeLog.info("window", "list failed " + managed.id() + " " + failure.getMessage());
            }
            live.addAll(classify(managed.id(), managed.template(), tabs));
        }
        return live;
    }

    /**
     * One window row for the browser instance, then one tab row per Selenium handle.
     * Selenium handles are not windows.
     */
    static List<Handle> classify(String browserId, String template, List<String> seleniumHandles) {
        String id = browserId == null || browserId.isBlank() ? "browser" : browserId;
        String useTemplate = template == null ? "" : template;
        List<Handle> rows = new ArrayList<>();
        rows.add(new Handle("window:" + id, "window", useTemplate, "", "", true));
        if (seleniumHandles != null) {
            for (String handle : seleniumHandles) {
                if (handle == null || handle.isBlank()) continue;
                rows.add(new Handle(handle, "tab", useTemplate, "", "", true));
            }
        }
        return rows;
    }

    static Handle switchTo(Path project, String runId, String handle) {
        if (handle == null || handle.isBlank()) {
            throw new IllegalArgumentException("missing handle");
        }
        if (handle.startsWith("window:")) {
            PageSurfaces.Managed managed = findBrowser(handle);
            if (managed == null) throw new IllegalArgumentException("unknown handle: " + handle);
            PageSurfaces.focus(managed.driver());
            CompositeLog.info("window", "switch " + handle);
            return new Handle(handle, "window", managed.template(), safeTitle(managed.driver()), safeUrl(managed.driver()), true);
        }
        for (PageSurfaces.Managed managed : PageSurfaces.managed()) {
            try {
                if (managed.driver().getWindowHandles() != null && managed.driver().getWindowHandles().contains(handle)) {
                    WindowSwitch.switchToHandleOrThrow(managed.driver(), handle);
                    PageSurfaces.focus(managed.driver());
                    CompositeLog.info("window", "switch " + handle);
                    return new Handle(handle, "tab", managed.template(), safeTitle(managed.driver()), safeUrl(managed.driver()), true);
                }
            } catch (NoSuchWindowException failure) {
                throw new IllegalArgumentException("unknown handle: " + handle);
            } catch (RuntimeException failure) {
                CompositeLog.info("window", "switch failed " + handle + " " + failure.getMessage());
            }
        }
        throw new IllegalArgumentException("unknown handle: " + handle);
    }

    static Handle close(Path project, String runId, String handle) {
        if (handle == null || handle.isBlank()) {
            throw new IllegalArgumentException("missing handle");
        }
        if (handle.startsWith("window:")) {
            PageSurfaces.Managed managed = findBrowser(handle);
            if (managed == null) throw new IllegalArgumentException("unknown handle: " + handle);
            try {
                managed.driver().quit();
            } catch (RuntimeException failure) {
                CompositeLog.info("window", "close failed " + handle + " " + failure.getMessage());
            }
            PageSurfaces.detach(managed.driver());
            CompositeLog.info("window", "close " + handle);
            return new Handle(handle, "window", managed.template(), "", "", false);
        }
        for (PageSurfaces.Managed managed : PageSurfaces.managed()) {
            try {
                if (managed.driver().getWindowHandles() == null || !managed.driver().getWindowHandles().contains(handle)) {
                    continue;
                }
                String current = safeCurrent(managed.driver());
                managed.driver().switchTo().window(handle);
                managed.driver().close();
                java.util.Set<String> left = managed.driver().getWindowHandles();
                if (left == null || left.isEmpty()) {
                    PageSurfaces.detach(managed.driver());
                } else if (current != null && left.contains(current) && !current.equals(handle)) {
                    managed.driver().switchTo().window(current);
                } else {
                    managed.driver().switchTo().window(left.iterator().next());
                }
                CompositeLog.info("window", "close " + handle);
                return new Handle(handle, "tab", managed.template(), "", "", false);
            } catch (RuntimeException failure) {
                CompositeLog.info("window", "close failed " + handle + " " + failure.getMessage());
            }
        }
        throw new IllegalArgumentException("unknown handle: " + handle);
    }

    private static PageSurfaces.Managed findBrowser(String handle) {
        String id = handle.substring("window:".length());
        for (PageSurfaces.Managed managed : PageSurfaces.managed()) {
            if (managed.id().equals(id)) return managed;
        }
        return null;
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

    private static String safeCurrent(WebDriver driver) {
        try {
            return driver.getWindowHandle();
        } catch (RuntimeException failure) {
            return null;
        }
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
