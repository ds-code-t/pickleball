package tools.dscode.common.agentsurface;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** First existing Chromium-family browser. Each probe is recorded. */
public final class PopupBrowserLocator {
    private PopupBrowserLocator() {
    }

    public record Probe(String name, String candidate, boolean hit) {
    }

    public record Hit(Optional<String> path, List<Probe> probes) {
        public Hit {
            path = path == null ? Optional.empty() : path;
            probes = probes == null ? List.of() : List.copyOf(probes);
        }
    }

    public interface Lookup {
        String property(String name);

        String env(String name);

        String osName();

        boolean exists(String path);

        /** Parsed executable from {@code reg query}, or empty. */
        String regQuery(String spec);

        List<String> programFiles();

        String home();

        /** First directory on PATH that contains the name, or empty. */
        String pathWhich(String name);
    }

    public static Hit locate() {
        return locate(system());
    }

    public static Hit locate(Lookup lookup) {
        List<Probe> probes = new ArrayList<>();
        String property = lookup.property("pkb_agent_popup_browser");
        if (take(probes, "property", property, lookup)) return hit(probes);
        String env = lookup.env("PKB_AGENT_POPUP_BROWSER");
        if (take(probes, "env", env, lookup)) return hit(probes);
        String chrome = lookup.env("DOMENGINE_CHROME");
        if (take(probes, "DOMENGINE_CHROME", chrome, lookup)) return hit(probes);
        String os = lookup.osName() == null ? "" : lookup.osName().toLowerCase();
        if (os.contains("win")) windows(lookup, probes);
        else if (os.contains("mac")) mac(lookup, probes);
        else linux(lookup, probes);
        return new Hit(lastHit(probes), probes);
    }

    private static void windows(Lookup lookup, List<Probe> probes) {
        for (String spec : List.of(
                "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\msedge.exe",
                "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\msedge.exe",
                "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\chrome.exe",
                "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\chrome.exe")) {
            String found = lookup.regQuery(spec);
            if (take(probes, "reg " + spec, found, lookup)) return;
        }
        for (String root : lookup.programFiles()) {
            if (root == null || root.isBlank()) continue;
            for (String relative : List.of(
                    "\\Microsoft\\Edge\\Application\\msedge.exe",
                    "\\Google\\Chrome\\Application\\chrome.exe",
                    "\\Chromium\\Application\\chrome.exe")) {
                if (take(probes, "path", root + relative, lookup)) return;
            }
        }
    }

    private static void mac(Lookup lookup, List<Probe> probes) {
        String home = lookup.home() == null ? "" : lookup.home();
        List<String> roots = List.of("/Applications", home.isBlank() ? "" : home + "/Applications");
        for (String root : roots) {
            if (root.isBlank()) continue;
            for (String[] app : new String[][] {
                    {"Google Chrome.app", "Google Chrome"},
                    {"Microsoft Edge.app", "Microsoft Edge"},
                    {"Chromium.app", "Chromium"},
                    {"Brave Browser.app", "Brave Browser"}
            }) {
                String path = root + "/" + app[0] + "/Contents/MacOS/" + app[1];
                if (take(probes, "app", path, lookup)) return;
            }
        }
    }

    private static void linux(Lookup lookup, List<Probe> probes) {
        for (String name : List.of(
                "google-chrome", "google-chrome-stable", "chromium", "chromium-browser",
                "microsoft-edge", "microsoft-edge-stable", "brave-browser")) {
            String found = lookup.pathWhich(name);
            probes.add(new Probe("PATH " + name, found == null ? "" : found, found != null && !found.isBlank() && lookup.exists(found)));
            if (found != null && !found.isBlank() && lookup.exists(found)) return;
        }
        if (take(probes, "snap", "/snap/bin/chromium", lookup)) return;
        take(probes, "flatpak", "/var/lib/flatpak/exports/bin/org.chromium.Chromium", lookup);
    }

    private static boolean last(List<Probe> probes) {
        return !probes.isEmpty() && probes.get(probes.size() - 1).hit();
    }

    private static boolean take(List<Probe> probes, String name, String candidate, Lookup lookup) {
        if (candidate == null || candidate.isBlank()) {
            probes.add(new Probe(name, "", false));
            return false;
        }
        boolean exists = lookup.exists(candidate.trim());
        probes.add(new Probe(name, candidate.trim(), exists));
        return exists;
    }

    private static Hit hit(List<Probe> probes) {
        return new Hit(lastHit(probes), probes);
    }

    private static Optional<String> lastHit(List<Probe> probes) {
        for (int index = probes.size() - 1; index >= 0; index--) {
            if (probes.get(index).hit()) return Optional.of(probes.get(index).candidate());
        }
        return Optional.empty();
    }

    public static Lookup system() {
        return new Lookup() {
            @Override
            public String property(String name) {
                return System.getProperty(name);
            }

            @Override
            public String env(String name) {
                return System.getenv(name);
            }

            @Override
            public String osName() {
                return System.getProperty("os.name", "");
            }

            @Override
            public boolean exists(String path) {
                return path != null && Files.isRegularFile(Path.of(path));
            }

            @Override
            public String regQuery(String spec) {
                return PopupBrowserLocator.regQuery(spec);
            }

            @Override
            public List<String> programFiles() {
                return List.of(
                        envOr("ProgramFiles", "C:\\Program Files"),
                        envOr("ProgramFiles(x86)", "C:\\Program Files (x86)"),
                        envOr("LocalAppData", ""));
            }

            @Override
            public String home() {
                return System.getProperty("user.home", "");
            }

            @Override
            public String pathWhich(String name) {
                String path = System.getenv("PATH");
                if (path == null) return "";
                for (String dir : path.split(":")) {
                    if (dir.isBlank()) continue;
                    Path candidate = Path.of(dir, name);
                    if (Files.isExecutable(candidate)) return candidate.toString();
                }
                return "";
            }

            private String envOr(String name, String fallback) {
                String value = System.getenv(name);
                return value == null || value.isBlank() ? fallback : value;
            }
        };
    }

    static String regQuery(String spec) {
        try {
            Process process = new ProcessBuilder("reg", "query", spec, "/ve").start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            process.getInputStream().transferTo(out);
            if (!finished) {
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) return "";
            for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.toLowerCase().contains("reg_sz")) continue;
                int at = trimmed.toLowerCase().indexOf("reg_sz");
                return trimmed.substring(at + "reg_sz".length()).trim();
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }
}
