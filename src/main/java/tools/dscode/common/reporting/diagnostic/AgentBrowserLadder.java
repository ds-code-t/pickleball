package tools.dscode.common.reporting.diagnostic;

import tools.dscode.testengine.PKB_props;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Names a remote farm browser. It does not choose a browser for a launch.
 * The project browser is kept. {@code CHROME_HEADLESS} is used only when the
 * project names none. Unused SAUCE/GRID/REMOTE yaml files are never selected.
 */
public final class AgentBrowserLadder {
    public static final String CHROME_HEADLESS = "CHROME_HEADLESS";

    public record Decision(
            String browser,
            String projectBrowser,
            String reason,
            boolean remoteKept
    ) {
    }

    private AgentBrowserLadder() {
    }

    public static Decision select(Path projectRoot) {
        String projectBrowser = readProjectBrowser(projectRoot);
        if (projectBrowser == null || projectBrowser.isBlank()) {
            return new Decision(
                    CHROME_HEADLESS,
                    "",
                    "project names no browser; CHROME_HEADLESS is the only fallback",
                    false
            );
        }
        boolean remote = isRemoteBrowser(projectBrowser);
        return new Decision(
                projectBrowser,
                projectBrowser,
                remote
                        ? "project pkb_browser is a remote farm name; keep it"
                        : "project pkb_browser is kept",
                remote
        );
    }

    /**
     * Retry browser only when local headless cannot start and the project's
     * configured {@code pkb_browser} is already a remote farm name.
     */
    public static String fallbackIfHeadlessCannotStart(Decision decision) {
        if (decision == null || decision.remoteKept()) return null;
        return isRemoteBrowser(decision.projectBrowser()) ? decision.projectBrowser() : null;
    }

    public static boolean isRemoteBrowser(String browser) {
        if (browser == null || browser.isBlank()) return false;
        String name = browser.trim().toUpperCase(Locale.ROOT);
        return name.startsWith("SAUCE")
                || name.startsWith("GRID")
                || name.startsWith("REMOTE");
    }

    public static boolean looksLikeLocalBrowserFailure(String output) {
        if (output == null || output.isBlank()) return false;
        String text = output.toLowerCase(Locale.ROOT);
        return text.contains("chromedriver")
                || text.contains("cannot find chrome")
                || text.contains("chrome binary")
                || text.contains("session not created")
                || text.contains("devtoolsactiveport")
                || text.contains("chrome not reachable")
                || text.contains("unable to establish")
                || text.contains("webdriver");
    }

    static String readProjectBrowser(Path projectRoot) {
        if (projectRoot == null) return "";
        Path project = projectRoot.toAbsolutePath().normalize();
        for (Path file : propertyFiles(project)) {
            if (!Files.isRegularFile(file)) continue;
            String browser = readBrowserProperty(file);
            if (browser != null && !browser.isBlank()) return browser.trim();
        }
        return "";
    }

    private static List<Path> propertyFiles(Path project) {
        return List.of(
                project.resolve("src/test/resources/pickleball_local.properties"),
                project.resolve("src/test/resources/pickleball.properties"),
                project.resolve("src/main/resources/pickleball_local.properties"),
                project.resolve("src/main/resources/pickleball.properties"),
                project.resolve("pickleball_local.properties"),
                project.resolve("pickleball.properties")
        );
    }

    private static String readBrowserProperty(Path file) {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException ignored) {
            return "";
        }
        String value = properties.getProperty(PKB_props.PKB_BROWSER);
        return value == null ? "" : value.trim();
    }
}
