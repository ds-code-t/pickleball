package tools.dscode.common.composite;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fails when a main-source file outside the classified allowlist grows a raw
 * WebDriver DOM read. Adapter files own the Selenium calls. Other allowlisted
 * files are outside the browser DOM (Swing, Gherkin, code-source, alerts,
 * window size) or are known gaps until a later phase removes them.
 */
class DomAccessGuardTest {
    static final Pattern RAW = Pattern.compile(
            "(?<!ElementOperations)\\.(?:findElement|findElements|executeAsyncScript|executeScript|getDomAttribute|getDomProperty|getAttribute|getText|isDisplayed|isEnabled|isSelected|getTagName|getRect|getLocation|getSize|getCssValue|getShadowRoot)\\s*\\("
                    + "|switchTo\\s*\\(\\s*\\)\\s*\\.\\s*frame\\s*\\("
                    + "|new\\s+Actions\\s*\\("
                    + "|new\\s+Select\\s*\\("
    );

    /** Files that may contain raw DOM calls. Everything else must stay clean. */
    static final Set<String> ALLOWED = Set.of(
            "pickleball-control-api/src/main/java/tools/dscode/control/api/DynamicControl.java",
            "pickleball-control-api/src/main/java/tools/dscode/control/bridge/ControlBridgeCoordinator.java",
            "pickleball-control-api/src/main/java/tools/dscode/control/bridge/ControlBridgeRuntime.java",
            "pickleball-control-api/src/main/java/tools/dscode/control/bridge/ControlBridgeStepResolver.java",
            "pickleball-control-protocol/src/main/java/tools/dscode/control/protocol/PickleballVersion.java",
            "pickleball-workbench/src/main/java/tools/dscode/workbench/mapping/MappingValueCodec.java",
            "pickleball-workbench/src/main/java/tools/dscode/workbench/sync/WorkbenchSynchronizer.java",
            "pickleball-workbench/src/main/java/tools/dscode/workbench/ui/FeaturePickerPanel.java",
            "pickleball-workbench/src/main/java/tools/dscode/workbench/ui/RunVarOverridePanel.java",
            "pickleball-workbench/src/main/java/tools/dscode/workbench/ui/TerminalPanel.java",
            "pickleball-workbench/src/main/java/tools/dscode/workbench/ui/WorkbenchFrame.java",
            "pickleball-workbench/src/main/java/tools/dscode/workbench/ui/web/WebViewPanel.java",
            "pickleball-workbench/src/main/java/tools/dscode/workbench/worker/WorkbenchWorkerManager.java",
            "src/main/aspectj/io/cucumber/core/runner/PickleStepDefinitionMatchArgumentPaddingAspect.aj",
            "src/main/aspectj/io/cucumber/gherkin/PickleCompilerAugment.aj",
            "src/main/aspectj/io/cucumber/messages/types/PickleStep_NestingLevel.aj",
            "src/main/aspectj/tools/dscode/common/reporting/diagnostic/Diagnostic213CompletionAspect.aj",
            "src/main/java/io/cucumber/core/gherkin/messages/CucumberDeepCloneUtil.java",
            "src/main/java/io/cucumber/core/gherkin/messages/CucumberStepTextCloneUtil.java",
            "src/main/java/io/cucumber/core/gherkin/messages/GherkinMessagesPickleStepMatcher.java",
            "src/main/java/io/cucumber/core/runner/CurrentScenarioState.java",
            "src/main/java/io/cucumber/core/runner/NPickleStepTestStepFactory.java",
            "src/main/java/io/cucumber/core/runner/StepExtension.java",
            "src/main/java/io/cucumber/core/runner/modularexecutions/CucumberScanUtil.java",
            "src/main/java/io/cucumber/core/runner/util/CucumberQueryUtil.java",
            "src/main/java/io/cucumber/core/runner/util/KeyFunctions.java",
            "src/main/java/tools/dscode/common/assertions/AssertionChain.java",
            "src/main/java/tools/dscode/common/browseroperations/BrowserAlerts.java",
            "src/main/java/tools/dscode/common/composite/CompositeWebElement.java",
            "src/main/java/tools/dscode/common/composite/DomCompositeAdapter.java",
            "src/main/java/tools/dscode/common/composite/ElementOperations.java",
            "src/main/java/tools/dscode/common/composite/ElementReads.java",
            "src/main/java/tools/dscode/common/reporting/diagnostic/ScenarioIdentity.java",
            "src/main/java/tools/dscode/common/reporting/diagnostic/SourceProvenance.java",
            "src/main/java/tools/dscode/common/seleniumextensions/CompositeSnapshotProducer.java",
            "src/main/java/tools/dscode/common/seleniumextensions/ElementSnapshotScript.java",
            "src/main/java/tools/dscode/common/seleniumextensions/SeleniumSnapshotProducer.java",
            "src/main/java/tools/dscode/common/seleniumextensions/SnapshotProducers.java",
            "src/main/java/tools/dscode/common/treeparsing/parsedComponents/Phrase.java",
            "src/main/java/tools/dscode/launcher/WorkbenchElementInspect.java",
            "src/main/java/tools/dscode/launcher/WorkbenchOneShotLaunch.java",
            "src/main/java/tools/dscode/testengine/ExampleRowFilter.java"
    );

    @Test
    void mainSourcesStayInsideTheClassifiedAllowlist() throws IOException {
        TreeMap<String, List<String>> hits = scan(Path.of(""));
        TreeSet<String> unknown = new TreeSet<>(hits.keySet());
        unknown.removeAll(ALLOWED);
        assertTrue(unknown.isEmpty(), String.join("\n", unknown));
        int count = hits.values().stream().mapToInt(List::size).sum();
        assertTrue(count > 0, "guard scan found nothing; the patterns no longer match the tree");
        for (String cleaned : List.of(
                "pickleball-control-api/src/main/java/tools/dscode/control/api/PageElementQuery.java",
                "pickleball-control-api/src/main/java/tools/dscode/control/api/ElementControl.java",
                "src/main/java/tools/dscode/common/domoperations/HumanInteractions.java",
                "src/main/java/tools/dscode/common/domoperations/KeyParser.java",
                "src/main/java/tools/dscode/common/domoperations/LeanWaits.java",
                "src/main/java/tools/dscode/common/domoperations/NestedByLocator.java",
                "src/main/java/tools/dscode/common/domoperations/SeleniumUtils.java",
                "src/main/java/tools/dscode/common/domoperations/elementstates/BinaryStateConditions.java",
                "src/main/java/tools/dscode/common/seleniumextensions/AllContextsScan.java",
                "src/main/java/tools/dscode/common/seleniumextensions/ContextWrapper.java",
                "src/main/java/tools/dscode/common/seleniumextensions/ElementWrapper.java",
                "src/main/java/tools/dscode/common/seleniumextensions/SnapshotLookup.java",
                "src/main/java/tools/dscode/common/treeparsing/parsedComponents/phraseoperations/ActionOperations.java"
        )) {
            assertFalse(hits.containsKey(cleaned), cleaned + " still has raw DOM reads: " + hits.get(cleaned));
        }
    }

    @Test
    void deliberateRawReadOutsideTheAllowlistFails() {
        String bad = "src/main/java/evil/BadDom.java";
        String source = "class Bad { void x(org.openqa.selenium.WebDriver d){ d.findElement(null); } }\n";
        List<String> hits = hitLines(source);
        assertEquals(1, hits.size(), hits.toString());
        assertFalse(ALLOWED.contains(bad));
        TreeSet<String> unknown = new TreeSet<>();
        unknown.add(bad);
        unknown.removeAll(ALLOWED);
        assertFalse(unknown.isEmpty());
    }

    static TreeMap<String, List<String>> scan(Path root) throws IOException {
        List<Path> roots = List.of(
                root.resolve("src/main"),
                root.resolve("pickleball-control-api/src/main"),
                root.resolve("pickleball-control-protocol/src/main"),
                root.resolve("pickleball-workbench/src/main")
        );
        TreeMap<String, List<String>> hits = new TreeMap<>();
        for (Path base : roots) {
            if (!Files.isDirectory(base)) continue;
            try (Stream<Path> walk = Files.walk(base)) {
                for (Path path : walk.filter(DomAccessGuardTest::source).toList()) {
                    List<String> lines = hitLines(Files.readString(path));
                    if (!lines.isEmpty()) {
                        hits.put(root.relativize(path).toString().replace('\\', '/'), lines);
                    }
                }
            }
        }
        return hits;
    }

    static List<String> hitLines(String source) {
        String[] lines = strip(source).split("\n", -1);
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (RAW.matcher(lines[i]).find()) hits.add((i + 1) + ":" + lines[i].strip());
        }
        return hits;
    }

    private static boolean source(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".aj");
    }

    static String strip(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int j = source.indexOf('\n', i);
                if (j < 0) j = n;
                pad(out, source, i, j);
                i = j;
                continue;
            }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int j = source.indexOf("*/", i + 2);
                j = j < 0 ? n : j + 2;
                pad(out, source, i, j);
                i = j;
                continue;
            }
            if (c == '"' && i + 2 < n && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                int j = source.indexOf("\"\"\"", i + 3);
                j = j < 0 ? n : j + 3;
                pad(out, source, i, j);
                i = j;
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                int j = i + 1;
                while (j < n) {
                    if (source.charAt(j) == '\\') {
                        j += 2;
                        continue;
                    }
                    if (source.charAt(j) == quote) {
                        j++;
                        break;
                    }
                    j++;
                }
                if (j > n) j = n;
                pad(out, source, i, j);
                i = j;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static void pad(StringBuilder out, String source, int from, int to) {
        for (int k = from; k < to; k++) out.append(source.charAt(k) == '\n' ? '\n' : ' ');
    }
}
