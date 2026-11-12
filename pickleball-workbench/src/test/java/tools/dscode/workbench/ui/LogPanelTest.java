package tools.dscode.workbench.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.log.LogCliForwarder;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless Logs tab. A recording forwarder stands in for the CLI process. */
class LogPanelTest {
    static {
        System.setProperty("java.awt.headless", "true");
    }

    @TempDir
    Path project;

    @Test
    void panelForwardsListTailAndShow() throws Exception {
        List<List<String>> forwarded = new ArrayList<>();
        LogPanel[] holder = new LogPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new LogPanel(() -> project, (root, args) -> {
            forwarded.add(List.copyOf(args));
            return new LogCliForwarder.Result(0, String.join(" ", args), "", List.of());
        }));
        LogPanel panel = holder[0];
        JLabel status = label(panel);
        JTextField file = fieldAfter(panel, "File");
        JTextField lines = fieldAfter(panel, "Lines");
        JTextArea output = area(panel);

        click(panel, "List", status);
        assertEquals(List.of("list"), forwarded.get(0));
        assertEquals("list", output.getText());

        SwingUtilities.invokeAndWait(() -> {
            file.setText("history.log");
            lines.setText("15");
        });
        click(panel, "Tail", status);
        assertEquals(List.of("tail", "--file=history.log", "--lines=15"), forwarded.get(1));

        click(panel, "Show", status);
        assertEquals(List.of("show", "--file=history.log"), forwarded.get(2));
        assertTrue(output.getText().contains("show"));
    }

    private static void click(Container panel, String title, JLabel status) throws Exception {
        JButton button = button(panel, title);
        SwingUtilities.invokeAndWait(button::doClick);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String[] text = new String[1];
            SwingUtilities.invokeAndWait(() -> text[0] = status.getText());
            if ("exit 0".equals(text[0])) return;
            Thread.sleep(20);
        }
        throw new AssertionError(title + " status stayed " + status.getText());
    }

    private static JButton button(Container root, String title) {
        for (Component component : walk(root)) {
            if (component instanceof JButton button && title.equals(button.getText())) return button;
        }
        throw new AssertionError("missing button " + title);
    }

    private static JLabel label(Container root) {
        for (Component component : walk(root)) {
            if (component instanceof JLabel label && "Logs".equals(label.getText())) return label;
        }
        throw new AssertionError("missing Logs label");
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

    private static JTextArea area(Container root) {
        for (Component component : walk(root)) {
            if (component instanceof JTextArea area) return area;
        }
        throw new AssertionError("missing text area");
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
