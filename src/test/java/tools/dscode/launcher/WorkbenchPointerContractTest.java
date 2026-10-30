package tools.dscode.launcher;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchPointerContractTest {
    @Test
    void consumerPointersAreShortIdenticalAndNameTheCliSession() throws Exception {
        Path agents = Path.of("maven-consumer-project/AGENTS.md");
        Path copilot = Path.of("maven-consumer-project/.github/copilot-instructions.md");
        String left = Files.readString(agents).strip();
        String right = Files.readString(copilot).strip();
        assertEquals(left, right);

        List<String> nonblank = left.lines().filter(line -> !line.isBlank()).toList();
        assertTrue(nonblank.size() >= 3 && nonblank.size() <= 8, "pointer must be 3-8 nonblank lines");
        String first = nonblank.getFirst();
        assertTrue(first.contains("PickleballWorkbenchLauncher"));
        assertTrue(first.contains("export-guidance"));
        assertTrue(first.contains(".pickleball/AGENT-GUIDE.md"));
        assertTrue(first.contains("classpathScope=test"));

        String lowered = left.toLowerCase(Locale.ROOT);
        assertTrue(lowered.contains("hint"));
        assertTrue(lowered.contains("discover"));
        assertTrue(lowered.contains("confirm"));
        assertTrue(lowered.contains("isolate"));
        assertTrue(lowered.contains("execute-step"));
        assertTrue(lowered.contains("do not open the gui for your own testing"));
        assertTrue(lowered.contains("while testing for yourself, stay headless"));
        assertFalse(lowered.contains("do not start the gui"));
        assertFalse(lowered.contains("must never open the gui"));
        assertFalse(lowered.contains("must not start the gui"));
        assertTrue(lowered.contains("exec.args"));

        assertFalse(lowered.contains("register mcp"));
        assertFalse(lowered.contains("ide mcp"));
        assertFalse(lowered.contains("stdio"));
        assertFalse(lowered.contains("intellij"));
        assertFalse(lowered.contains("copilot plugin"));
        assertFalse(lowered.contains("do not isolate"));
        assertFalse(lowered.contains("do not maven-exec isolate"));
        assertFalse(lowered.contains("must") && lowered.contains("chrome_headless"));
        assertFalse(lowered.contains("only controls"));
        assertTrue(lowered.contains("open-scenario"));
        assertTrue(lowered.contains("mvnw"));
        assertTrue(lowered.contains("machine-wide"));
    }

    @Test
    void agentGuidesDriveAnOpenWindowAndUseTheProjectWrapper() throws Exception {
        List<Path> guides = List.of(
                Path.of("docs/consumer-agent-guide.md"),
                Path.of("docs/ai-run-configuration.md"),
                Path.of("docs/pickleball-workbench.md"),
                Path.of("docs/consumer-project.md"),
                Path.of("AGENTS.md"),
                Path.of("pickleball-workbench/AGENTS.md")
        );
        for (Path guide : guides) {
            String text = Files.readString(guide);
            String lowered = text.toLowerCase(Locale.ROOT);
            assertTrue(lowered.contains("open-scenario"), guide.toString());
            assertTrue(lowered.contains("do not open the gui for your own testing"), guide.toString());
            assertTrue(lowered.contains("while testing for yourself, stay headless"), guide.toString());
            assertFalse(lowered.contains("do not start the gui"), guide.toString());
            assertFalse(lowered.contains("must never open the gui"), guide.toString());
            assertTrue(lowered.contains("mvnw"), guide.toString());
            assertTrue(lowered.contains("machine-wide"), guide.toString());
            assertTrue(lowered.contains("already open"), guide.toString());
            assertTrue(lowered.contains("only one agent"), guide.toString());
            assertTrue(lowered.contains("own run id"), guide.toString());
            assertTrue(lowered.contains("agent-log"), guide.toString());
            assertTrue(lowered.contains("short log"), guide.toString());
            assertFalse(lowered.contains("only controls"), guide.toString());
        }
        String chooserSource = Files.readString(Path.of("docs/consumer-agent-guide.md"));
        int chooserStart = chooserSource.indexOf("## Tool chooser");
        int liveStart = chooserSource.indexOf("### Live isolation loop");
        String chooser = chooserSource.substring(chooserStart, liveStart);
        assertTrue(chooser.contains("-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher"));
        assertTrue(chooser.contains("-Dexec.args=discover"));
        assertTrue(chooser.contains("-Dexec.args=isolate"));
        assertTrue(chooser.contains("execute-step"));
        assertFalse(chooser.contains("PickleballWorkbenchLauncher discover"));
        assertFalse(chooser.toLowerCase(Locale.ROOT).contains("do not isolate"));
        assertFalse(chooser.contains("attach.json"));
        assertFalse(chooser.contains("ui ."));
    }

    @Test
    void firstReadGuideStaysSmallAndShowsTheDiscoverEditConfirmLoop() throws Exception {
        Path guide = Path.of("docs/consumer-agent-guide.md");
        long bytes = Files.size(guide);
        assertTrue(bytes <= 5 * 1024, "first-read guide is " + bytes + " bytes");
        String text = Files.readString(guide);
        assertTrue(text.contains("--text-file=step.txt"), text);
        assertTrue(text.contains("base64 -w0"), text);
        assertTrue(text.contains("[Convert]::ToBase64String"), text);
        assertTrue(text.contains("./mvnw"), text);
        assertTrue(text.contains(">step.txt echo Then the \"Save\" button is visible"), text);
        assertTrue(text.contains("--text-b64=$b64"), text);
        assertFalse(text.contains("--text-b64=<base64>"), text);
        assertFalse(text.contains("<base64>"), text);
        int certutil = text.indexOf("certutil -encodehex");
        if (certutil >= 0) {
            assertTrue(text.substring(certutil).contains("--text-b64=%B64%"), text);
        }
        assertTrue(text.contains("summary status=FAILED"), text);
        assertTrue(text.contains("workspace-edit --run-id=row-1"), text);
        assertTrue(text.contains("summary status=PASSED"), text);
        assertTrue(text.contains("confirm --run-id=row-1"), text);
        assertTrue(text.contains("or `unset`"), text);
        assertTrue(text.contains("Several live sessions must pass `--run-id`"), text);
        assertTrue(text.contains("docs/workbench-agent-detail.md"), text);
        assertTrue(text.contains("workspace="), text);
        assertTrue(text.contains("fingerprint="), text);
        assertTrue(text.contains("The fingerprint comes from `workspace-show`"), text);
        assertTrue(text.contains("-Dexec.args=execute-step --text-file=step.txt"), text);
        assertTrue(text.contains("-Dexec.args=execute-step --text-b64"), text);
        assertFalse(text.contains("Maintainer-only: pointer-eval harness"), text);
        assertTrue(Files.size(Path.of("docs/workbench-agent-detail.md")) > bytes);
    }

    @Test
    void packagedGuidanceLeavesMaintainerAndRoadmapDocsInTheRepo() throws Exception {
        Path packaged = Path.of("src/main/resources/META-INF/pickleball/guidance");
        try (var walk = Files.walk(packaged)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                String relative = packaged.relativize(path).toString().replace('\\', '/');
                assertFalse(relative.startsWith("docs/maintainer/"), relative);
                assertFalse(relative.startsWith("docs/roadmaps/"), relative);
                assertFalse(relative.endsWith("ai-diagnostic-reporting-plan.md"), relative);
                assertFalse(relative.endsWith("workbench-thin-packaging-roadmap.md"), relative);
            });
        }
        String index = Files.readString(packaged.resolve("index.txt"));
        assertFalse(index.contains("docs/maintainer/"));
        assertFalse(index.contains("docs/roadmaps/"));
        assertFalse(index.contains("docs/ai-diagnostic-reporting-plan.md"));
        assertFalse(index.contains("docs/workbench-thin-packaging-roadmap.md"));
        String packagedIndex = Files.readString(packaged.resolve("docs/agent/repository-index.md"));
        assertFalse(packagedIndex.contains("docs/maintainer/"));
        assertFalse(packagedIndex.contains("docs/roadmaps/"));
        assertFalse(packagedIndex.contains("docs/ai-diagnostic-reporting-plan.md"));
        assertFalse(packagedIndex.contains("docs/workbench-thin-packaging-roadmap.md"));
        String packagedReadme = Files.readString(packaged.resolve("docs/README.md"));
        assertFalse(packagedReadme.contains("ai-diagnostic-reporting-plan.md"));
        assertFalse(packagedReadme.contains("workbench-thin-packaging-roadmap.md"));
        assertTrue(Files.isRegularFile(Path.of("docs/maintainer/2.1.18-consumer-report-fixes.md")));
        assertTrue(Files.isRegularFile(Path.of("docs/roadmaps/workbench-cli-inventory.json")));
        assertTrue(Files.isRegularFile(Path.of("docs/tech-debt.md")));
        assertTrue(Files.isRegularFile(Path.of("docs/ai-diagnostic-reporting-plan.md")));
        assertTrue(Files.isRegularFile(Path.of("docs/workbench-thin-packaging-roadmap.md")));
        String sourceIndex = Files.readString(Path.of("docs/agent/repository-index.md"));
        assertTrue(sourceIndex.contains("docs/ai-diagnostic-reporting-plan.md"));
        assertTrue(sourceIndex.contains("docs/workbench-thin-packaging-roadmap.md"));
        String sourceMap = Files.readString(Path.of("docs/agent/feature-map.md"));
        String packagedMap = Files.readString(packaged.resolve("docs/agent/feature-map.md"));
        assertFalse(sourceMap.contains("docs/ai-diagnostic-reporting-plan.md"), sourceMap);
        assertFalse(packagedMap.contains("docs/ai-diagnostic-reporting-plan.md"), packagedMap);
        assertFalse(packagedMap.contains("docs/workbench-thin-packaging-roadmap.md"), packagedMap);
        assertExportedDocsDoNotLinkOutsideTheExport(packaged);
    }

    private static void assertExportedDocsDoNotLinkOutsideTheExport(Path packaged) throws Exception {
        java.util.Set<String> exported = new java.util.HashSet<>();
        try (var walk = Files.walk(packaged)) {
            walk.filter(Files::isRegularFile).forEach(path ->
                    exported.add(packaged.relativize(path).toString().replace('\\', '/')));
        }
        java.util.regex.Pattern link = java.util.regex.Pattern.compile("\\[[^\\]]*\\]\\(([^)\\s]+)(?:\\s+[^)]*)?\\)");
        java.util.regex.Pattern backtick = java.util.regex.Pattern.compile("`(docs/[A-Za-z0-9_./-]+\\.md)`");
        try (var walk = Files.walk(packaged)) {
            for (Path path : walk.filter(file -> Files.isRegularFile(file) && file.toString().endsWith(".md")).toList()) {
                String relative = packaged.relativize(path).toString().replace('\\', '/');
                String text = Files.readString(path).replaceAll("(?s)```.*?```", "");
                java.util.List<String> targets = new java.util.ArrayList<>();
                java.util.regex.Matcher links = link.matcher(text);
                while (links.find()) targets.add(links.group(1));
                java.util.regex.Matcher ticks = backtick.matcher(text);
                while (ticks.find()) targets.add(ticks.group(1));
                for (String target : targets) {
                    String resolved = resolveExportedDoc(relative, target);
                    if (resolved == null || exported.contains(resolved)) continue;
                    throw new AssertionError("Exported " + relative + " links to " + resolved + ", which is not exported");
                }
            }
        }
    }

    private static String resolveExportedDoc(String fromRelative, String target) {
        String raw = target == null ? "" : target.trim();
        if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("mailto:")) {
            return null;
        }
        int hash = raw.indexOf('#');
        if (hash >= 0) raw = raw.substring(0, hash);
        int query = raw.indexOf('?');
        if (query >= 0) raw = raw.substring(0, query);
        raw = raw.trim();
        if (raw.isEmpty() || raw.endsWith("/")) return null;
        String resolved;
        if (raw.startsWith("docs/") || raw.startsWith("maven-consumer-project/") || "AGENT-GUIDE.md".equals(raw)) {
            resolved = java.nio.file.Path.of(raw).normalize().toString().replace('\\', '/');
        } else {
            String base = fromRelative.contains("/") ? fromRelative.substring(0, fromRelative.lastIndexOf('/')) : "";
            resolved = java.nio.file.Path.of(base, raw).normalize().toString().replace('\\', '/');
        }
        if (resolved.startsWith("../") || !resolved.endsWith(".md")) return null;
        return resolved;
    }
}
