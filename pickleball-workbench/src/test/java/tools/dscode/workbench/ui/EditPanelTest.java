package tools.dscode.workbench.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.dalton.DaltonCliForwarder;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless editor. A recording forwarder stands in for the CLI process. */
class EditPanelTest {
    static {
        System.setProperty("java.awt.headless", "true");
    }

    @TempDir
    Path project;

    @Test
    void everyActionForwardsAndDeleteRequiresConfirmation() throws Exception {
        List<List<String>> forwarded = new ArrayList<>();
        boolean[] confirmed = {true};
        EditPanel[] holder = new EditPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new EditPanel(
                () -> project,
                (root, args) -> {
                    forwarded.add(List.copyOf(args));
                    return new DaltonCliForwarder.Result(0, "{\"ok\":true,\"items\":[],\"text\":\"name: Badge\"}", "", List.of());
                },
                message -> confirmed[0]
        ));
        EditPanel panel = holder[0];
        JComboBox<?> surface = combo(panel);
        JTextField path = fieldAfter(panel, "Path");
        JTextField name = fieldAfter(panel, "Name");
        JTextField scenario = fieldAfter(panel, "Scenario");
        JTextField step = fieldAfter(panel, "Step");

        SwingUtilities.invokeAndWait(() -> {
            surface.setSelectedItem("Categories");
            name.setText("Badge");
        });
        click(panel, "Refresh");
        assertEquals(List.of("category", "list", "--json"), forwarded.get(0));

        click(panel, "Load");
        assertEquals(List.of("category", "show", "--id=Badge", "--json"), forwarded.get(1));

        click(panel, "Save");
        List<String> save = forwarded.get(2);
        assertEquals("category", save.get(0));
        assertEquals("put", save.get(1));
        assertEquals("--id=Badge", save.get(2));
        assertTrue(save.get(3).startsWith("--file="));
        assertEquals("--json", save.get(4));

        confirmed[0] = false;
        int before = forwarded.size();
        SwingUtilities.invokeAndWait(() -> button(panel, "Delete").doClick());
        assertEquals(before, forwarded.size());

        confirmed[0] = true;
        click(panel, "Delete");
        assertEquals(List.of("category", "remove", "--id=Badge", "--confirm", "--json"), forwarded.get(3));

        SwingUtilities.invokeAndWait(() -> {
            surface.setSelectedItem("Files");
            path.setText("configs/app.yaml");
        });
        click(panel, "Save");
        assertEquals("file", forwarded.get(4).get(0));
        assertEquals("put", forwarded.get(4).get(1));
        assertEquals("--path=configs/app.yaml", forwarded.get(4).get(2));

        SwingUtilities.invokeAndWait(() -> {
            surface.setSelectedItem("Scenarios");
            path.setText("features/pay.feature");
            scenario.setText("One");
        });
        click(panel, "Delete");
        assertEquals(List.of("feature", "scenario-delete", "--feature=features/pay.feature", "--scenario=One", "--confirm", "--json"),
                forwarded.get(5));

        SwingUtilities.invokeAndWait(() -> {
            surface.setSelectedItem("Steps");
            step.setText("1");
        });
        click(panel, "Save");
        assertEquals("step-modify", forwarded.get(6).get(1));
        assertTrue(forwarded.get(6).contains("--step=1"));

        SwingUtilities.invokeAndWait(() -> {
            surface.setSelectedItem("Glue");
            path.setText("glue/js/steps.js");
        });
        click(panel, "Refresh");
        assertEquals(List.of("glue", "list", "--json"), forwarded.get(7));
    }

    private static void click(EditPanel panel, String title) throws Exception {
        JLabel status = status(panel);
        SwingUtilities.invokeAndWait(() -> status.setText("waiting"));
        SwingUtilities.invokeAndWait(() -> button(panel, title).doClick());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String[] text = new String[1];
            SwingUtilities.invokeAndWait(() -> text[0] = status.getText());
            if ("exit 0".equals(text[0]) || (text[0] != null && text[0].startsWith("Enter "))) return;
            Thread.sleep(20);
        }
        throw new AssertionError(title + " status stayed " + status.getText());
    }

    private static JButton button(Container root, String title) {
        for (Component component : walk(root)) {
            if (component instanceof JButton button && title.equals(button.getText())) return button;
        }
        throw new IllegalStateException("missing button " + title);
    }

    private static JLabel status(Container root) {
        for (Component component : walk(root)) {
            if (component instanceof JLabel label && !Set.of("Path", "Name", "Scenario", "Step").contains(label.getText())) {
                return label;
            }
        }
        throw new IllegalStateException("missing status");
    }

    private static List<Component> walk(Container root) {
        List<Component> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            found.add(child);
            if (child instanceof Container nested) found.addAll(walk(nested));
        }
        return found;
    }

    private static JComboBox<?> combo(Container root) {
        for (Component child : walk(root)) {
            if (child instanceof JComboBox<?> box) return box;
        }
        throw new IllegalStateException("missing combo");
    }

    private static JTextField fieldAfter(Container root, String label) {
        List<Component> flat = walk(root);
        for (int index = 0; index < flat.size(); index++) {
            if (flat.get(index) instanceof JLabel jlabel && label.equals(jlabel.getText())) {
                return (JTextField) flat.get(index + 1);
            }
        }
        throw new IllegalStateException("missing field " + label);
    }
}
