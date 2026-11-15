package tools.dscode.workbench.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.dalton.DaltonBrowser;
import tools.dscode.workbench.dalton.DaltonCliForwarder;
import tools.dscode.workbench.diagnostics.DiagnosticEvidenceNavigator;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless Tracking tab. The forwarder stands in for the dalton CLI process. */
class WorkbenchTrackingForwarderTest {
    static {
        System.setProperty("java.awt.headless", "true");
    }

    @TempDir
    Path project;

    @Test
    void commandsAreCliArgumentsOnly() {
        assertEquals(List.of("timeline", "--json"), DaltonBrowser.timeline());
        assertEquals(List.of("query", "--entity=Account#ada", "--status=FAILED", "--json"),
                DaltonBrowser.query("Account#ada", "FAILED"));
        assertEquals(List.of("retain"), DaltonBrowser.retain());
    }

    @Test
    void panelForwardsTimelineAndJumpsToExplorer() throws Exception {
        List<List<String>> forwarded = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        String body = """
                {"command":"timeline","count":2,"total":2,"limit":50,"offset":0,"cursor":"c","events":[
                  {"at":"2020-01-01T00:00:00Z","kind":"run","id":"sample-run","runId":"sample-run","status":"PASSED","scenarioKey":"Pay","stepExecId":"","stepText":"","definitionId":"","entityType":"","entityId":"","field":"","operation":"","valueRef":"","message":"","path":""},
                  {"at":"2020-01-02T00:00:00Z","kind":"step","id":"step-1","runId":"sample-run","status":"PASSED","scenarioKey":"features/pay.feature|2","stepExecId":"step-1","stepText":"the total is 10","definitionId":"","entityType":"","entityId":"","field":"","operation":"","valueRef":"","message":"","path":""}
                ]}
                """;
        TrackingPanel[] holder = new TrackingPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new TrackingPanel(() -> project, (root, args) -> {
            forwarded.add(List.copyOf(args));
            return new DaltonCliForwarder.Result(0, body, "", List.of());
        }, opened::add));
        TrackingPanel panel = holder[0];
        JLabel honesty = label(panel, TrackingPanel.HONESTY);
        assertEquals("Rewind is view-only over recorded history. It does not undo browser or service side effects.",
                honesty.getText());
        click(panel, "Refresh");
        assertEquals(List.of("timeline", "--latest", "--json"), forwarded.get(0));
        assertEquals("2020-01-01T00:00:00Z run sample-run", row(panel, 0));
        JSlider scrub = slider(panel);
        SwingUtilities.invokeAndWait(() -> scrub.setValue(1));
        assertTrue(labelText(panel, "Tracking").contains("step-1"), labelText(panel, "Tracking"));
        SwingUtilities.invokeAndWait(() -> {
            combo(panel).setSelectedItem("2x");
            button(panel, "Play").doClick();
            button(panel, "Pause").doClick();
        });
        assertEquals("paused", labelText(panel, "Tracking"));
        click(panel, "Open in Explorer");
        assertEquals(List.of("sample-run"), opened);
        SwingUtilities.invokeAndWait(() -> {
            fieldAfter(panel, "Entity").setText("Account#ada");
            fieldAfter(panel, "Status").setText("FAILED");
        });
        click(panel, "Query");
        assertEquals(List.of("query", "--entity=Account#ada", "--status=FAILED", "--json"), forwarded.get(1));
        assertFalse(Files.exists(project.resolve(".pickleball/dalton/dalton.db")));
    }

    @Test
    void pagingRangeRetainAndFiltersAreForwarded() throws Exception {
        List<List<String>> forwarded = new ArrayList<>();
        String body = """
                {"command":"timeline","count":1,"total":2,"limit":50,"offset":0,"cursor":"2020-01-02T00:00:00Z\\u001fstep\\u001fstep-1","prev":"2020-01-01T00:00:00Z\\u001frun\\u001fsample-run","events":[
                  {"at":"2020-01-02T00:00:00Z","kind":"step","id":"step-1","runId":"sample-run","status":"PASSED","scenarioKey":"features/pay.feature|2","stepExecId":"step-1","stepText":"the total is 10","definitionId":"","entityType":"","entityId":"","field":"","operation":"","valueRef":"","message":"","path":""}
                ]}
                """;
        TrackingPanel[] holder = new TrackingPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new TrackingPanel(() -> project, (root, args) -> {
            forwarded.add(List.copyOf(args));
            return new DaltonCliForwarder.Result(0, body, "", List.of());
        }, ignored -> { }));
        TrackingPanel panel = holder[0];
        SwingUtilities.invokeAndWait(() -> {
            fieldAfter(panel, "From").setText("2020-01-01T00:00:00Z");
            fieldAfter(panel, "To").setText("2020-01-03T00:00:00Z");
            fieldAfter(panel, "Tag").setText("@pay");
            fieldAfter(panel, "Feature").setText("features/pay.feature");
            fieldAfter(panel, "Run").setText("sample-run");
            fieldAfter(panel, "Text").setText("total");
        });
        click(panel, "Refresh");
        assertEquals(List.of(
                "timeline",
                "--from=2020-01-01T00:00:00Z",
                "--to=2020-01-03T00:00:00Z",
                "--tag=@pay",
                "--feature=features/pay.feature",
                "--run=sample-run",
                "--text=total",
                "--latest",
                "--json"), forwarded.get(0));
        click(panel, "Older");
        assertTrue(forwarded.get(1).contains("--before=2020-01-01T00:00:00Z\u001frun\u001fsample-run"), forwarded.get(1).toString());
        assertFalse(forwarded.get(1).contains("--latest"), forwarded.get(1).toString());
        click(panel, "Newer");
        assertTrue(forwarded.get(2).contains("--cursor=2020-01-02T00:00:00Z\u001fstep\u001fstep-1"), forwarded.get(2).toString());
        click(panel, "Latest");
        assertTrue(forwarded.get(3).contains("--latest"), forwarded.get(3).toString());
        click(panel, "Retain");
        assertEquals(List.of("retain"), forwarded.get(4));
        click(panel, "Query");
        assertEquals(List.of(
                "query",
                "--tag=@pay",
                "--feature=features/pay.feature",
                "--run=sample-run",
                "--text=total",
                "--from=2020-01-01T00:00:00Z",
                "--to=2020-01-03T00:00:00Z",
                "--json"), forwarded.get(5));
        assertEquals("No retained diagnostic run matches sample-run", WorkbenchFrame.unmatchedDiagnosticRun("sample-run"));
        assertFalse(Files.exists(project.resolve(".pickleball/dalton/dalton.db")));
    }

    @Test
    void openInExplorerReportsBlankUnknownAndUnready() throws Exception {
        String body = """
                {"command":"timeline","count":1,"total":1,"limit":50,"offset":0,"cursor":"c","events":[
                  {"at":"2020-01-01T00:00:00Z","kind":"run","id":"blank","runId":"","status":"PASSED","scenarioKey":"","stepExecId":"","stepText":"","definitionId":"","entityType":"","entityId":"","field":"","operation":"","valueRef":"","message":"","path":""}
                ]}
                """;
        List<String> opened = new ArrayList<>();
        TrackingPanel[] holder = new TrackingPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new TrackingPanel(() -> project, (root, args) ->
                new DaltonCliForwarder.Result(0, body, "", List.of()), opened::add));
        TrackingPanel panel = holder[0];
        click(panel, "Refresh");
        click(panel, "Open in Explorer");
        assertEquals("Select a run with a run id first", labelText(panel, ""));
        assertTrue(opened.isEmpty(), opened.toString());

        List<String> status = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        DiagnosticEvidenceNavigator navigator = new DiagnosticEvidenceNavigator(project);
        assertEquals("", WorkbenchFrame.routeRecordedRun("", status::add, shown::add, navigator));
        assertEquals(List.of("Select a run with a run id first"), status);
        assertTrue(shown.isEmpty(), shown.toString());

        status.clear();
        assertEquals("", WorkbenchFrame.routeRecordedRun("sample-run", status::add, null, null));
        assertEquals(List.of("Explorer is not ready"), status);

        status.clear();
        String gap = WorkbenchFrame.routeRecordedRun("missing-run", status::add, shown::add, navigator);
        assertTrue(status.isEmpty(), status.toString());
        assertEquals(gap, shown.get(0));
        assertTrue(gap.contains("\"gap\":\"No retained diagnostic run matches missing-run\""), gap);
    }

    @Test
    void knownCatalogRunReplaysWithoutAPushAndStatusReachesTheLabel() throws Exception {
        Path root = project.resolve("reports/diagnostic-runs");
        Files.createDirectories(root);
        Files.writeString(root.resolve("run-catalog.json"), """
                {"runs":[{"runId":"kept-run","outcome":"PASSED"}]}
                """);
        DiagnosticEvidenceNavigator navigator = new DiagnosticEvidenceNavigator(project);
        List<String> status = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        assertNull(WorkbenchFrame.routeRecordedRun("kept-run", status::add, shown::add, navigator));
        assertTrue(status.isEmpty(), status.toString());
        assertTrue(shown.isEmpty(), shown.toString());

        String other = WorkbenchFrame.routeRecordedRun("other-run", status::add, shown::add, navigator);
        assertTrue(status.isEmpty(), status.toString());
        assertEquals(other, shown.get(0));
        assertTrue(other.contains("\"gap\":\"No retained diagnostic run matches other-run\""), other);

        JLabel[] label = new JLabel[1];
        List<String> notice = new ArrayList<>();
        List<String> pushed = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> label[0] = new JLabel("Ready"));
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("", WorkbenchFrame.deliverRecordedRun(
                    "kept-run", label[0], null, navigator, notice::add, pushed::add));
            assertEquals("Explorer is not ready", label[0].getText());
        });
        assertEquals(List.of("Explorer is not ready"), notice);
        assertTrue(pushed.isEmpty(), pushed.toString());

        notice.clear();
        SwingUtilities.invokeAndWait(() -> {
            label[0].setText("Ready");
            assertEquals("", WorkbenchFrame.deliverRecordedRun(
                    " ", label[0], null, navigator, notice::add, pushed::add));
            assertEquals("Select a run with a run id first", label[0].getText());
        });
        assertEquals(List.of("Select a run with a run id first"), notice);
        assertTrue(pushed.isEmpty(), pushed.toString());
    }

    private static void click(Container panel, String title) throws Exception {
        JButton button = button(panel, title);
        SwingUtilities.invokeAndWait(button::doClick);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String text = labelText(panel, "Tracking");
            if (text.startsWith("exit 0") || text.startsWith("2020-") || text.startsWith("explorer ")
                    || text.startsWith("Select a run") || text.startsWith("Explorer is not ready")
                    || "paused".equals(text) || text.startsWith("play ")) return;
            Thread.sleep(20);
        }
    }

    private static String row(Container panel, int index) throws Exception {
        String[] value = new String[1];
        SwingUtilities.invokeAndWait(() -> value[0] = String.valueOf(list(panel).getModel().getElementAt(index)));
        return value[0];
    }

    private static String labelText(Container root, String fallback) throws Exception {
        String[] text = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            for (Component component : walk(root)) {
                if (component instanceof JLabel label && !TrackingPanel.HONESTY.equals(label.getText())
                        && !"Entity".equals(label.getText()) && !"Status".equals(label.getText())
                        && !"Speed".equals(label.getText()) && !"From".equals(label.getText())
                        && !"To".equals(label.getText()) && !"Tag".equals(label.getText())
                        && !"Feature".equals(label.getText()) && !"Run".equals(label.getText())
                        && !"Text".equals(label.getText())) {
                    text[0] = label.getText();
                }
            }
            if (text[0] == null) text[0] = fallback;
        });
        return text[0];
    }

    private static JButton button(Container root, String title) {
        for (Component component : walk(root)) {
            if (component instanceof JButton button && title.equals(button.getText())) return button;
        }
        throw new AssertionError("missing button " + title);
    }

    private static JLabel label(Container root, String title) {
        for (Component component : walk(root)) {
            if (component instanceof JLabel label && title.equals(label.getText())) return label;
        }
        throw new AssertionError("missing label " + title);
    }

    @SuppressWarnings("rawtypes")
    private static JComboBox<?> combo(Container root) {
        for (Component component : walk(root)) {
            if (component instanceof JComboBox combo) return combo;
        }
        throw new AssertionError("missing speed");
    }

    private static JSlider slider(Container root) {
        for (Component component : walk(root)) {
            if (component instanceof JSlider slider) return slider;
        }
        throw new AssertionError("missing scrubber");
    }

    private static JTextField fieldAfter(Container root, String title) {
        List<Component> components = walk(root);
        for (int index = 0; index < components.size() - 1; index++) {
            if (components.get(index) instanceof JLabel label && title.equals(label.getText())
                    && components.get(index + 1) instanceof JTextField field) {
                return field;
            }
        }
        throw new AssertionError("missing field after " + title);
    }

    private static JList<?> list(Container root) {
        for (Component component : walk(root)) {
            if (component instanceof JList<?> list) return list;
        }
        throw new AssertionError("missing list");
    }

    private static List<Component> walk(Container root) {
        List<Component> found = new ArrayList<>();
        for (Component component : root.getComponents()) {
            found.add(component);
            if (component instanceof Container container) found.addAll(walk(container));
        }
        return found;
    }
}
