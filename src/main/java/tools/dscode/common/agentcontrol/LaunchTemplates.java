package tools.dscode.common.agentcontrol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Named Chromium launch presets. They select {@code pkb_browser} and args for
 * the existing {@code DriverConstruction} path. They are not a second launcher.
 */
public final class LaunchTemplates {
    public record Template(
            String name,
            String browser,
            String pkbBrowser,
            boolean headless,
            boolean verbose,
            boolean logs
    ) {
    }

    public record Resolved(Template template, List<String> args, String sentence) {
    }

    private static final List<String> QUIET = List.of(
            "--disable-dev-shm-usage",
            "--window-size=1920,1080",
            "--disable-notifications",
            "--disable-popup-blocking",
            "--remote-allow-origins=*"
    );

    private static final Map<String, Template> TEMPLATES = new LinkedHashMap<>();

    static {
        add("chrome-headed-verbose", "chrome", "CHROME", false, true, false);
        add("chrome-headed-quiet", "chrome", "CHROME", false, false, false);
        add("chrome-headless-verbose", "chrome", "CHROME_HEADLESS", true, true, false);
        add("chrome-headless-quiet", "chrome", "CHROME_HEADLESS", true, false, false);
        add("edge-headed-quiet", "edge", "EDGE", false, false, false);
        add("edge-headless-quiet", "edge", "EDGE", true, false, false);
        add("chrome-headed-logs", "chrome", "CHROME", false, true, true);
        add("chromium-headed-quiet", "chrome", "CHROME", false, false, false);
    }

    private LaunchTemplates() {
    }

    private static void add(String name, String browser, String pkbBrowser, boolean headless, boolean verbose, boolean logs) {
        TEMPLATES.put(name, new Template(name, browser, pkbBrowser, headless, verbose, logs));
    }

    public static List<String> names() {
        return List.copyOf(TEMPLATES.keySet());
    }

    public static Template get(String name) {
        if (name == null) return null;
        return TEMPLATES.get(name.trim().toLowerCase(Locale.ROOT));
    }

    public static Resolved resolve(String name, List<String> extraArgs) {
        Template template = get(name);
        if (template == null) {
            throw new IllegalArgumentException("unknown launch template: " + name);
        }
        List<String> args = new ArrayList<>(QUIET);
        if (template.headless()) args.add("--headless=new");
        if (template.verbose() || template.logs()) {
            args.add("--enable-logging");
            args.add("--v=1");
        }
        if (extraArgs != null) {
            for (String extra : extraArgs) {
                if (extra != null && !extra.isBlank() && !args.contains(extra)) args.add(extra);
            }
        }
        String sentence = "chromium-headed-quiet".equals(template.name())
                ? "Chromium via the existing Chrome launcher. DriverDownloadFallback already searches chromium and chromium-browser. Not a second launcher."
                : template.browser() + " via pkb_browser=" + template.pkbBrowser()
                + (template.headless() ? " headless" : " headed")
                + (template.logs() ? " with logs" : template.verbose() ? " verbose" : " quiet")
                + ". Existing Selenium Manager and fallback drivers start it.";
        return new Resolved(template, List.copyOf(args), sentence);
    }
}
