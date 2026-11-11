package tools.dscode.workbench.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.dalton.DaltonCliForwarder;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless Dalton tab. A recording forwarder stands in for the CLI process. */
class DaltonPanelTest {
    static {
        System.setProperty("java.awt.headless", "true");
    }

    @TempDir
    Path project;

    @Test
    void panelForwardsEveryActionAndRendersJsonLabels() throws Exception {
        List<List<String>> forwarded = new ArrayList<>();
        List<Path> roots = new ArrayList<>();
        DaltonPanel[] holder = new DaltonPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new DaltonPanel(() -> project, (root, args) -> {
            roots.add(root);
            forwarded.add(List.copyOf(args));
            return new DaltonCliForwarder.Result(0, body(args), "", List.of());
        }));
        DaltonPanel panel = holder[0];
        JLabel status = label(panel, "Dalton");
        JComboBox<?> view = combo(panel);
        JTextField feature = fieldAfter(panel, "Feature");
        JTextField scenario = fieldAfter(panel, "Scenario");
        JTextField entityType = fieldAfter(panel, "Entity");
        JTextField entityId = textField(panel, entityType, 1);
        JTextField version = fieldAfter(panel, "version");
        JList<?> list = list(panel);
        JTextArea editor = area(panel, true);
        JTextArea conflicts = area(panel, false);

        click(panel, "Status", status);
        assertEquals(List.of("status"), forwarded.get(0));
        assertEquals("database present  files=3  scenarios=2", row(list, 0));

        click(panel, "Hydrate", status);
        assertEquals(List.of("hydrate"), forwarded.get(1));
        assertEquals("hydrated inserted=1", row(list, 0));

        click(panel, "Extract", status);
        assertEquals(List.of("extract"), forwarded.get(2));
        assertEquals("extracted 1", row(list, 0));

        click(panel, "Run", status);
        assertEquals(List.of("run"), forwarded.get(3));
        assertEquals("dalton run exit=0", row(list, 0));

        click(panel, "Refresh", status);
        assertEquals(List.of("files", "list", "--json"), forwarded.get(4));
        assertEquals("features/pay.feature  feature  edited=yes  bytes=42", row(list, 0));

        SwingUtilities.invokeAndWait(() -> {
            view.setSelectedItem("Features");
            feature.setText("features/pay.feature");
        });
        click(panel, "Refresh", status);
        assertEquals(List.of("features", "list", "--json"), forwarded.get(5));
        assertEquals("features/pay.feature  Pay  scenarios=2", row(list, 0));

        SwingUtilities.invokeAndWait(() -> view.setSelectedItem("Scenarios"));
        click(panel, "Refresh", status);
        assertEquals(List.of("scenarios", "list", "--feature=features/pay.feature", "--json"), forwarded.get(6));
        assertEquals("features/pay.feature  1  Scenario  Pay Ada", row(list, 0));

        SwingUtilities.invokeAndWait(() -> {
            view.setSelectedItem("Steps");
            scenario.setText("Pay Ada");
        });
        click(panel, "Refresh", status);
        assertEquals(
                List.of("steps", "list", "--feature=features/pay.feature", "--scenario=Pay Ada", "--json"),
                forwarded.get(7));
        assertEquals("features/pay.feature  Pay Ada  1  *  INFO: amount is 1", row(list, 0));

        SwingUtilities.invokeAndWait(() -> view.setSelectedItem("Entities"));
        click(panel, "Refresh", status);
        assertEquals(List.of("entity", "list"), forwarded.get(8));
        assertEquals("Entities: 1", row(list, 0));

        SwingUtilities.invokeAndWait(() -> view.setSelectedItem("History"));
        click(panel, "Refresh", status);
        assertEquals(List.of("history", "list"), forwarded.get(9));
        assertEquals("Runs: 1", row(list, 0));

        click(panel, "Conflicts", status);
        assertEquals(List.of("conflicts", "--json"), forwarded.get(10));
        assertEquals("untracked DB edit on features/pay.feature", conflicts.getText().trim());

        SwingUtilities.invokeAndWait(() -> {
            entityType.setText("");
            entityId.setText("");
            feature.setText("features/pay.feature");
        });
        click(panel, "Load", status);
        assertEquals(List.of("features", "show", "features/pay.feature", "--json"), forwarded.get(11));
        assertEquals("Feature: Pay\n  Scenario: Pay Ada\n", editor.getText());
        assertEquals("features/pay.feature  Pay  scenarios=1", row(list, 0));

        SwingUtilities.invokeAndWait(() -> editor.setText("Feature: From the tab\n"));
        click(panel, "Save feature", status);
        List<String> put = forwarded.get(12);
        assertEquals(List.of("feature", "put", "features/pay.feature"), put.subList(0, 3));
        assertTrue(put.get(3).startsWith("--file="), put.toString());
        Path saved = Path.of(put.get(3).substring("--file=".length()));
        assertEquals("Feature: From the tab\n", Files.readString(saved, StandardCharsets.UTF_8));

        SwingUtilities.invokeAndWait(() -> {
            entityType.setText("Customer");
            entityId.setText("7");
            version.setText("3");
            editor.setText("{\"name\":\"Ada\"}\n");
        });
        click(panel, "Load", status);
        assertEquals(List.of("entity", "get", "Customer", "7"), forwarded.get(13));
        assertEquals("Entity Customer 7 version 3", row(list, 0));

        click(panel, "Save entity", status);
        List<String> entityPut = forwarded.get(14);
        assertEquals(List.of("entity", "put", "Customer", "7", "--version=3"), entityPut.subList(0, 5));
        assertTrue(entityPut.get(5).startsWith("--payload-file="), entityPut.toString());
        Path payload = Path.of(entityPut.get(5).substring("--payload-file=".length()));
        assertEquals("{\"name\":\"Ada\"}\n", Files.readString(payload, StandardCharsets.UTF_8));

        assertEquals(15, forwarded.size());
        assertTrue(roots.stream().allMatch(project::equals));
    }

    private static String body(List<String> args) {
        String key = String.join(" ", args);
        if (key.equals("status")) {
            return "{\"database\":\"present\",\"files\":3,\"scenarios\":2}";
        }
        if (key.equals("hydrate")) return "hydrated inserted=1";
        if (key.equals("extract")) return "extracted 1";
        if (key.equals("run")) return "dalton run exit=0";
        if (key.equals("files list --json")) {
            return "{\"files\":[{\"path\":\"features/pay.feature\",\"sourceType\":\"feature\",\"edited\":true,\"bytes\":42}]}";
        }
        if (key.equals("features list --json")) {
            return "{\"features\":[{\"path\":\"features/pay.feature\",\"name\":\"Pay\",\"scenarioCount\":2}]}";
        }
        if (key.startsWith("scenarios list ")) {
            return "{\"scenarios\":[{\"path\":\"features/pay.feature\",\"ord\":1,\"keyword\":\"Scenario\",\"name\":\"Pay Ada\"}]}";
        }
        if (key.startsWith("steps list ")) {
            return "{\"steps\":[{\"path\":\"features/pay.feature\",\"scenario\":\"Pay Ada\",\"ord\":1,\"keyword\":\"*\",\"text\":\"INFO: amount is 1\"}]}";
        }
        if (key.equals("entity list")) return "entities 1\n";
        if (key.equals("history list")) return "runs 1\n";
        if (key.equals("conflicts --json")) {
            return "{\"conflicts\":[{\"path\":\"features/pay.feature\",\"kind\":\"untracked\",\"message\":\"untracked DB edit on features/pay.feature\"}]}";
        }
        if (key.startsWith("features show ")) {
            return "{\"path\":\"features/pay.feature\",\"name\":\"Pay\",\"scenarioCount\":1,\"text\":\"Feature: Pay\\n  Scenario: Pay Ada\\n\"}";
        }
        if (key.equals("entity get Customer 7")) return "Customer 7 3\n";
        if (key.startsWith("feature put ") || key.startsWith("entity put ")) return "saved";
        return "none";
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

    private static String row(JList<?> list, int index) throws Exception {
        String[] value = new String[1];
        SwingUtilities.invokeAndWait(() -> value[0] = String.valueOf(list.getModel().getElementAt(index)));
        return value[0];
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
        throw new AssertionError("missing view");
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

    private static JTextField textField(Container root, JTextField after, int offset) {
        List<Component> fields = new ArrayList<>();
        for (Component component : walk(root)) {
            if (component instanceof JTextField field) fields.add(field);
        }
        int index = fields.indexOf(after);
        if (index < 0 || index + offset >= fields.size()) throw new AssertionError("missing text field");
        return (JTextField) fields.get(index + offset);
    }

    private static JList<?> list(Container root) {
        for (Component component : walk(root)) {
            if (component instanceof JList<?> list && list.getModel() instanceof DefaultListModel<?>) return list;
        }
        throw new AssertionError("missing list");
    }

    private static JTextArea area(Container root, boolean editable) {
        for (Component component : walk(root)) {
            if (component instanceof JTextArea area && area.isEditable() == editable) return area;
        }
        throw new AssertionError("missing text area editable=" + editable);
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
