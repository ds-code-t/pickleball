package dev.domengine.desktop;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AppleScript the macOS backend runs must compile. System Events terms only parse inside a
 * {@code tell application "System Events"} block; outside one, {@code value of attribute "AX..."}
 * fails with "Expected end of line", which hid the live macOS snapshot behind a skip.
 */
class MacAccessibilityScriptTest {
    private static final Pattern SYSTEM_EVENTS_TERM = Pattern.compile(
            "UI elements|value of attribute|role of|perform action|keystroke|focused of|enabled of|position of|size of|of processes|in processes|\\(processes ");
    private static final Pattern TELL = Pattern.compile("^\\s*tell application \"System Events\"\\s*$");
    private static final Pattern END_TELL = Pattern.compile("^\\s*end tell\\s*$");
    private static final Pattern RESERVED_ASSIGNMENT = Pattern.compile("\\bset (rest|more|result|it|me) to\\b");

    private static Map<String, String> scripts() {
        return Map.of(
                "capture", MacAccessibility.captureScript(),
                "click", MacAccessibility.clickScript(),
                "type", MacAccessibility.typeScript());
    }

    @Test
    void systemEventsTermsOnlyAppearInsideASystemEventsTell() {
        List<String> outside = new ArrayList<>();
        scripts().forEach((name, source) -> {
            int depth = 0;
            int lineNumber = 0;
            for (String line : source.split("\n")) {
                lineNumber++;
                if (TELL.matcher(line).matches()) {
                    depth++;
                    continue;
                }
                if (END_TELL.matcher(line).matches()) {
                    depth--;
                    continue;
                }
                if (depth == 0 && SYSTEM_EVENTS_TERM.matcher(line.replaceAll("\"[^\"]*\"", "\"\"")).find()) outside.add(name + ":" + lineNumber + ": " + line.trim());
            }
            assertEquals(0, depth, name + " has unbalanced tell blocks");
        });
        assertTrue(outside.isEmpty(), "System Events terms outside a System Events tell: " + outside);
    }

    @Test
    void scriptsDoNotAssignAppleScriptReservedWords() {
        scripts().forEach((name, source) ->
                assertFalse(RESERVED_ASSIGNMENT.matcher(source).find(), name + " assigns a reserved AppleScript word"));
    }

    @Test
    void captureAndPathLookupUseTheSameProcessList() {
        String filtered = "processes whose background only is false";
        assertTrue(MacAccessibility.captureScript().contains(filtered));
        assertTrue(MacAccessibility.clickScript().contains(filtered));
        assertTrue(MacAccessibility.typeScript().contains(filtered));
        assertTrue(MacAccessibility.CAPTURE_TIMEOUT_SECONDS > NativeDesktop.DEFAULT_TIMEOUT_SECONDS);
    }

    @Test
    void everyScriptCompilesWithOsacompileOnMacOs(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue("macos".equals(NativeDesktop.family()), "osacompile exists only on macOS");
        for (Map.Entry<String, String> script : scripts().entrySet()) {
            Path source = dir.resolve(script.getKey() + ".applescript");
            Files.writeString(source, script.getValue());
            Path compiled = dir.resolve(script.getKey() + ".scpt");
            NativeDesktop.CommandResult result = NativeDesktop.run(
                    List.of("osacompile", "-o", compiled.toString(), source.toString()), Map.of(), 30);
            assertEquals(0, result.exit(), script.getKey() + " does not compile: " + result.output());
        }
    }
}
