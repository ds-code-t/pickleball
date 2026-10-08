package com.example.pickleball;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.java.en.Given;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.LogForwarder;
import tools.dscode.launcher.WorkbenchAgentCommands;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static tools.dscode.common.mappings.MappingProcessor.getRunMap;

public class SyntaxProofSteps {
    private static final ConcurrentHashMap<String, AtomicInteger> BRANCH_HITS = new ConcurrentHashMap<>();

    @Given("^reset branch counts$")
    public void resetBranchCounts() {
        BRANCH_HITS.clear();
    }

    @Given("^count branch ([A-Za-z0-9-]+)$")
    public static String countBranch(String name) {
        return Integer.toString(BRANCH_HITS
                .computeIfAbsent(name, key -> new AtomicInteger())
                .incrementAndGet());
    }

    @Given("^branch count \"([^\"]*)\" equals \"([^\"]*)\"$")
    public void branchCountEquals(String name, String expected) {
        String actual = Integer.toString(BRANCH_HITS.getOrDefault(name, new AtomicInteger()).get());
        if (!actual.equals(expected)) {
            throw new AssertionError("branch " + name + " count was " + actual + " not " + expected);
        }
    }

    @Given("^branch count \"([^\"]*)\" is from \"([^\"]*)\" through \"([^\"]*)\"$")
    public void branchCountInRange(String name, String low, String high) {
        int actual = BRANCH_HITS.getOrDefault(name, new AtomicInteger()).get();
        int minimum = Integer.parseInt(low);
        int maximum = Integer.parseInt(high);
        if (actual < minimum || actual > maximum) {
            throw new AssertionError(
                    "branch " + name + " count was " + actual
                            + " not from " + minimum + " through " + maximum
            );
        }
    }

    @Given("^resolving \"([^\"]*)\" is recorded as \"([^\"]*)\"$")
    public void resolvingIsRecorded(String expression, String key) {
        recordResolution(expression, key, null);
    }

    @Given("^resolving \"([^\"]*)\" is recorded as \"([^\"]*)\" and the info contains \"([^\"]*)\"$")
    public void resolvingIsRecordedWithInfo(String expression, String key, String expectedLog) {
        recordResolution(expression, key, expectedLog);
    }

    @Given("^resolving \"([^\"]*)\" fails as \"([^\"]*)\" and the info contains \"([^\"]*)\"$")
    public void resolvingFailsWithInfo(String expression, String key, String expectedLog) {
        Set<String> before = infoIds();
        RuntimeException thrown;
        try {
            ParsingMap.getRunningParsingMap().resolveWholeText("<{ " + expression + " }>");
            thrown = null;
        } catch (RuntimeException ex) {
            thrown = ex;
        }
        String log = joinedNewInfo(before);
        getRunMap().put(key + "Status", thrown == null ? "ok" : "threw");
        if (thrown == null) {
            throw new AssertionError("expected evaluation to fail for <{ " + expression + " }>");
        }
        String message = thrown.getMessage() == null ? "" : thrown.getMessage();
        if (!log.contains("evaluation failed")
                || !log.contains(expectedLog)
                || log.contains("pkbLazy")
                || message.contains("pkbLazy")
                || !message.contains("(((")) {
            throw new AssertionError("failure picture was [" + log + "] error [" + message + "]");
        }
        getRunMap().put(key, "threw");
    }

    private void recordResolution(String expression, String key, String expectedLog) {
        Set<String> before = infoIds();
        try {
            String value = ParsingMap.getRunningParsingMap().resolveWholeText("<{ " + expression + " }>");
            getRunMap().put(key, value == null ? "" : value);
            getRunMap().put(key + "Status", "ok");
        } catch (RuntimeException ex) {
            getRunMap().put(key, "");
            getRunMap().put(key + "Status", "threw");
        }
        if (expectedLog == null) {
            return;
        }
        String log = joinedNewInfo(before);
        boolean matched = log.contains(expectedLog) && !log.contains("pkbLazy");
        getRunMap().put(key + "LogOk", matched ? "yes" : "no");
        if (!matched) {
            throw new AssertionError("info log [" + log + "] did not contain [" + expectedLog + "]");
        }
    }

    private static Set<String> infoIds() {
        Set<String> ids = new HashSet<>();
        collectIds(LogForwarder.getDefaultEntry(), ids);
        try {
            collectIds(LogForwarder.closestEntryToStep(), ids);
        } catch (RuntimeException ignored) {
            // A unit-style call has no running step.
        }
        return ids;
    }

    private static void collectIds(Entry entry, Set<String> ids) {
        if (entry == null || !ids.add(entry.id)) {
            return;
        }
        for (Entry child : entry.children) {
            collectIds(child, ids);
        }
    }

    private static String joinedNewInfo(Set<String> before) {
        StringBuilder joined = new StringBuilder();
        appendNew(LogForwarder.getDefaultEntry(), before, joined, new HashSet<>());
        try {
            appendNew(LogForwarder.closestEntryToStep(), before, joined, new HashSet<>());
        } catch (RuntimeException ignored) {
            // A unit-style call has no running step.
        }
        return joined.toString();
    }

    private static void appendNew(Entry entry, Set<String> before, StringBuilder joined, Set<String> seen) {
        if (entry == null || !seen.add(entry.id)) {
            return;
        }
        if (!before.contains(entry.id) && entry.text != null && entry.text.contains("->")) {
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(entry.text);
        }
        for (Entry child : entry.children) {
            appendNew(child, before, joined, seen);
        }
    }

    @Given("^the number of saves to \"([^\"]*)\" is saved as \"([^\"]*)\"$")
    public void numberOfSaves(String key, String dest) {
        String needle = "to key: '" + key + "'";
        getRunMap().put(dest, Integer.toString(countLog(needle)));
    }

    private static int countLog(String needle) {
        CurrentScenarioState state = getCurrentScenarioState();
        if (state == null || state.scenarioLog == null) {
            return 0;
        }
        return countEntry(state.scenarioLog, needle);
    }

    private static int countEntry(Entry entry, String needle) {
        if (entry == null) {
            return 0;
        }
        int count = entry.text != null && entry.text.contains(needle) ? 1 : 0;
        for (Entry child : entry.children) {
            count += countEntry(child, needle);
        }
        return count;
    }

    @Given("^the recorded city is saved as \"([^\"]*)\"$")
    public void recordCity(String key, DataTable table) {
        getRunMap().put(key, cityCell(table));
        getRunMap().put(key + "Marker", inlineMarker());
    }

    @Given("^the recorded note is saved as \"([^\"]*)\"$")
    public void recordNote(String key, DocString docString) {
        String content = docString == null || docString.getContent() == null
                ? ""
                : docString.getContent().trim();
        getRunMap().put(key, content);
        getRunMap().put(key + "Marker", inlineMarker());
    }

    @Given("^the called row \"([^\"]*)\" records its gates$")
    public void recordGates(String token) {
        ParsingMap map = ParsingMap.getRunningParsingMap();
        getRunMap().put(token + "RunIf", presence(map.get("RunIf")));
        getRunMap().put(token + "RunBackground", presence(map.get("RunBackground")));
        Object mark = map.get(token);
        getRunMap().put(
                token + "Mark",
                "B".equals(String.valueOf(mark)) ? "with-background" : "without-background"
        );
    }

    @Given("^resolve-runvars reports the launcher JVM$")
    public void resolveRunVarsReportsLauncherJvm() {
        Path project = consumerProject();
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"resolve-runvars", project.toString()},
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8)
        );
        String out = outBytes.toString(StandardCharsets.UTF_8);
        if (exit != 0) {
            throw new AssertionError("resolve-runvars exited " + exit + ": " + errBytes.toString(StandardCharsets.UTF_8));
        }
        require(out, "launcher JVM");
        require(out, "not the Discover worker");
        require(out, "Do not treat resolve-runvars as the environment Discover will use");
        require(out, "does not start tests");
        if (out.contains("NEXT: run discover")) {
            throw new AssertionError("resolve-runvars followed the Discover hint path");
        }
    }

    private static String cityCell(DataTable table) {
        List<List<String>> cells = table.cells();
        if (cells.isEmpty() || cells.getFirst().isEmpty()) {
            return "";
        }
        if (cells.size() == 1) {
            List<String> row = cells.getFirst();
            return row.size() > 1 ? row.get(1) : row.getFirst();
        }
        List<String> row = cells.get(1);
        return row.isEmpty() ? "" : row.getFirst();
    }

    private static String inlineMarker() {
        StepExtension step = GlobalState.getRunningStep();
        if (step == null || step.getInlineArgumentType() == null) {
            return "";
        }
        return step.getInlineArgumentType();
    }

    private static String presence(Object value) {
        return value == null ? "hidden" : "leaked:" + value;
    }

    private static void require(String text, String expected) {
        if (!text.contains(expected)) {
            throw new AssertionError("resolve-runvars output missing '" + expected + "': " + text);
        }
    }

    private static Path consumerProject() {
        Path start = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path dir = start;
        for (int i = 0; i < 8 && dir != null; i++) {
            Path direct = candidate(dir);
            if (direct != null) {
                return direct;
            }
            Path nested = candidate(dir.resolve("maven-consumer-project"));
            if (nested != null) {
                return nested;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not find the consumer project from " + start);
    }

    private static Path candidate(Path dir) {
        if (Files.isRegularFile(dir.resolve("pom.xml"))
                && Files.isDirectory(dir.resolve("src/test/resources/features"))) {
            return dir;
        }
        return null;
    }
}
