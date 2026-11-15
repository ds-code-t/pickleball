package tools.dscode.workbench.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.dalton.DaltonBrowser;
import tools.dscode.workbench.dalton.DaltonCliForwarder;

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
        assertEquals(List.of("timeline", "--json"), forwarded.get(0));
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

    private static void click(Container panel, String title) throws Exception {
        JButton button = button(panel, title);
        SwingUtilities.invokeAndWait(button::doClick);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String text = labelText(panel, "Tracking");
            if (text.startsWith("exit 0") || text.startsWith("2020-") || text.startsWith("explorer ")
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
                        && !"Speed".equals(label.getText())) {
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
