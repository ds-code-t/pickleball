package tools.dscode.common.driver;

import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.ParsingMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ChromeHeadlessConfigChecks {
    @Test
    void bundledChromeHeadlessIsUsedWhenConsumerConfigOmitsIt() throws Exception {
        Path configRoot = Files.createTempDirectory("pkb-bundled-chrome-headless-");
        Files.writeString(configRoot.resolve("URL.yaml"), "home: http://example.test\n");
        try {
            ParsingMap.initializeConfigs(configRoot.toString());
            ParsingMap globals = ParsingMap.getGlobalsParsingmap();

            assertEquals("CREATE_LOCAL_DRIVER", String.valueOf(globals.get("configs.CHROME_HEADLESS.constructor")));
            assertEquals("chrome", String.valueOf(globals.get("configs.CHROME_HEADLESS.browser")));
            Object args = globals.get("configs.CHROME_HEADLESS.driver.options.args");
            assertTrue(containsText(args, "--headless=new"));
            assertTrue(containsText(args, "--window-size=1920,1080"));
            assertFalse(containsText(globals.get("configs.CHROME_HEADLESS.postActions"), "MAXIMIZE"));
            assertTrue(containsText(globals.get("configs.CHROME_HEADLESS.cleanup"), "QUIT_LOCAL_DRIVER"));
            assertEquals("http://example.test", String.valueOf(globals.get("configs.URL.home")));
        } finally {
            ParsingMap.initializeConfigs("configs");
            Files.deleteIfExists(configRoot.resolve("URL.yaml"));
            Files.deleteIfExists(configRoot);
        }
    }

    @Test
    void consumerChromeHeadlessYamlOverridesTheBundledDefault() throws Exception {
        Path configRoot = Files.createTempDirectory("pkb-override-chrome-headless-");
        Files.writeString(configRoot.resolve("CHROME_HEADLESS.yaml"), """
                constructor: CREATE_LOCAL_DRIVER
                browser: chrome
                marker: consumer-override
                """);
        try {
            ParsingMap.initializeConfigs(configRoot.toString());
            ParsingMap globals = ParsingMap.getGlobalsParsingmap();
            assertEquals("consumer-override", String.valueOf(globals.get("configs.CHROME_HEADLESS.marker")));
            assertEquals("null", String.valueOf(globals.get("configs.CHROME_HEADLESS.driver")));
        } finally {
            ParsingMap.initializeConfigs("configs");
            Files.deleteIfExists(configRoot.resolve("CHROME_HEADLESS.yaml"));
            Files.deleteIfExists(configRoot);
        }
    }

    @Test
    void namedConsumerBrowserYamlStillLoadsAlongsideBundledHeadless() throws Exception {
        Path configRoot = Files.createTempDirectory("pkb-chrome-and-headless-");
        Files.writeString(configRoot.resolve("CHROME.yaml"), """
                constructor: CREATE_LOCAL_DRIVER
                browser: chrome
                postActions:
                  - "MAXIMIZE"
                """);
        try {
            ParsingMap.initializeConfigs(configRoot.toString());
            ParsingMap globals = ParsingMap.getGlobalsParsingmap();
            assertTrue(containsText(globals.get("configs.CHROME.postActions"), "MAXIMIZE"));
            assertTrue(containsText(globals.get("configs.CHROME_HEADLESS.driver.options.args"), "--headless=new"));
            assertFalse(containsText(globals.get("configs.CHROME_HEADLESS.postActions"), "MAXIMIZE"));
        } finally {
            ParsingMap.initializeConfigs("configs");
            Files.deleteIfExists(configRoot.resolve("CHROME.yaml"));
            Files.deleteIfExists(configRoot);
        }
    }

    private static boolean containsText(Object value, String expected) {
        if (value == null) {
            return false;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).anyMatch(item -> item.contains(expected));
        }
        return String.valueOf(value).contains(expected);
    }
}
