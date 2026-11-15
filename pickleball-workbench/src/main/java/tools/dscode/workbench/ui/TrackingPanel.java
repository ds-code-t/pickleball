package tools.dscode.workbench.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.workbench.dalton.DaltonBrowser;
import tools.dscode.workbench.dalton.DaltonCliForwarder;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tracking and timeline. Every read shells out through {@link DaltonCliForwarder}.
 * Play, scrub, and speed walk the returned events. Opening a run jumps to Explorer.
 */
final class TrackingPanel extends JPanel {
    static final String HONESTY =
            "Rewind is view-only over recorded history. It does not undo browser or service side effects.";

    @FunctionalInterface
    interface Forwarder {
        DaltonCliForwarder.Result run(Path root, List<String> args);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Supplier<Path> project;
    private final Forwarder forwarder;
    private final Consumer<String> openExplorer;
    private final JTextField entity = new JTextField(16);
    private final JTextField statusFilter = new JTextField(8);
    private final JTextField from = new JTextField(18);
    private final JTextField to = new JTextField(18);
    private final JTextField tag = new JTextField(8);
    private final JTextField feature = new JTextField(16);
    private final JTextField run = new JTextField(12);
    private final JTextField text = new JTextField(12);
    private final DefaultListModel<String> rows = new DefaultListModel<>();
    private final JList<String> list = new JList<>(rows);
    private final JSlider scrub = new JSlider(0, 0, 0);
    private final JComboBox<String> speed = new JComboBox<>(new String[] {"0.5x", "1x", "2x", "4x"});
    private final JLabel honesty = new JLabel(HONESTY);
    private final JLabel status = new JLabel("Tracking");
    private final List<Beat> beats = new ArrayList<>();
    private final Timer playTimer;
    private int index;
    private String pageCursor = "";
    private String pagePrev = "";

    TrackingPanel(Supplier<Path> project, Forwarder forwarder, Consumer<String> openExplorer) {
        super(new BorderLayout(8, 8));
        this.project = project;
        this.forwarder = forwarder;
        this.openExplorer = openExplorer == null ? ignored -> { } : openExplorer;
        speed.setSelectedItem("1x");
        playTimer = new Timer(1000, event -> next());
        playTimer.setRepeats(true);
        JPanel north = new JPanel(new FlowLayout(FlowLayout.LEFT));
        north.add(button("Refresh", "dalton timeline", () -> run(timeline(true, null, null))));
        north.add(button("Latest", "Newest timeline page", () -> run(timeline(true, null, null))));
        north.add(button("Older", "Previous timeline page", this::older));
        north.add(button("Newer", "Next timeline page", this::newer));
        north.add(button("Query", "dalton query", () -> run(DaltonBrowser.query(
                entity.getText(), statusFilter.getText(), tag.getText(), feature.getText(),
                run.getText(), text.getText(), from.getText(), to.getText()))));
        north.add(new JLabel("Entity"));
        north.add(entity);
        north.add(new JLabel("Status"));
        north.add(statusFilter);
        north.add(new JLabel("From"));
        north.add(from);
        north.add(new JLabel("To"));
        north.add(to);
        north.add(new JLabel("Tag"));
        north.add(tag);
        north.add(new JLabel("Feature"));
        north.add(feature);
        north.add(new JLabel("Run"));
        north.add(run);
        north.add(new JLabel("Text"));
        north.add(text);
        north.add(button("Retain", "dalton retain", () -> run(DaltonBrowser.retain())));
        north.add(button("Previous", "Previous timeline beat", this::previous));
        north.add(button("Next", "Next timeline beat", this::next));
        north.add(button("Play", "Play recorded beats", this::play));
        north.add(button("Pause", "Pause recorded beats", this::pause));
        north.add(new JLabel("Speed"));
        north.add(speed);
        north.add(button("Open in Explorer", "Jump to the existing Explorer for this run", this::open));
        speed.addActionListener(event -> playTimer.setDelay(delayMillis()));
        scrub.addChangeListener(event -> {
            if (!scrub.getValueIsAdjusting()) select(scrub.getValue());
        });
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) select(list.getSelectedIndex());
        });
        add(north, BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(scrub, BorderLayout.NORTH);
        south.add(honesty, BorderLayout.CENTER);
        south.add(status, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);
    }

    private void older() {
        if (pagePrev == null || pagePrev.isBlank()) {
            status.setText("no older page");
            return;
        }
        run(timeline(false, null, pagePrev));
    }

    private void newer() {
        if (pageCursor == null || pageCursor.isBlank()) {
            status.setText("no newer page");
            return;
        }
        run(timeline(false, pageCursor, null));
    }

    private List<String> timeline(boolean latest, String cursor, String before) {
        return DaltonBrowser.timeline(
                from.getText(), to.getText(), tag.getText(), feature.getText(),
                run.getText(), text.getText(), cursor, before, latest);
    }

    private void play() {
        if (beats.isEmpty()) {
            status.setText("events 0");
            return;
        }
        playTimer.setDelay(delayMillis());
        playTimer.start();
        status.setText("play " + speed.getSelectedItem());
    }

    private void pause() {
        playTimer.stop();
        status.setText("paused");
    }

    private void previous() {
        if (beats.isEmpty()) return;
        select(Math.max(0, index - 1));
    }

    private void next() {
        if (beats.isEmpty()) return;
        if (index >= beats.size() - 1) {
            playTimer.stop();
            select(beats.size() - 1);
            return;
        }
        select(index + 1);
    }

    private void open() {
        if (index < 0 || index >= beats.size()) {
            status.setText("Select a run");
            return;
        }
        String runId = beats.get(index).runId();
        status.setText("explorer " + runId);
        openExplorer.accept(runId);
    }

    private void select(int next) {
        if (beats.isEmpty()) return;
        int bounded = Math.max(0, Math.min(beats.size() - 1, next));
        index = bounded;
        if (scrub.getMaximum() != Math.max(0, beats.size() - 1)) scrub.setMaximum(Math.max(0, beats.size() - 1));
        if (scrub.getValue() != bounded) scrub.setValue(bounded);
        if (list.getSelectedIndex() != bounded) list.setSelectedIndex(bounded);
        Beat beat = beats.get(bounded);
        status.setText(beat.label());
    }

    private int delayMillis() {
        String chosen = String.valueOf(speed.getSelectedItem());
        double rate = switch (chosen) {
            case "0.5x" -> 0.5;
            case "2x" -> 2.0;
            case "4x" -> 4.0;
            default -> 1.0;
        };
        return Math.max(50, (int) (1000 / rate));
    }

    private void run(List<String> args) {
        status.setText(String.join(" ", args));
        Path root = project.get();
        new SwingWorker<DaltonCliForwarder.Result, Void>() {
            @Override
            protected DaltonCliForwarder.Result doInBackground() {
                return forwarder.run(root, args);
            }

            @Override
            protected void done() {
                try {
                    show(get());
                } catch (Exception failure) {
                    status.setText("error: " + failure.getMessage());
                }
            }
        }.execute();
    }

    private void show(DaltonCliForwarder.Result result) {
        String body = result.out() == null ? "" : result.out();
        beats.clear();
        rows.clear();
        pageCursor = "";
        pagePrev = "";
        try {
            if (body.trim().startsWith("{")) {
                JsonNode root = JSON.readTree(body);
                pageCursor = root.path("cursor").asText("");
                pagePrev = root.path("prev").asText("");
                JsonNode events = root.has("events") ? root.get("events") : root.path("rows");
                if (events.isArray()) {
                    for (JsonNode event : events) {
                        Beat beat = Beat.from(event);
                        beats.add(beat);
                        rows.addElement(beat.label());
                    }
                }
            }
        } catch (Exception failure) {
            status.setText("error: " + failure.getMessage());
            return;
        }
        if (beats.isEmpty()) {
            scrub.setMaximum(0);
            index = 0;
            status.setText(result.exit() == 0 ? "exit 0 events 0" : "exit " + result.exit());
            return;
        }
        scrub.setMaximum(beats.size() - 1);
        select(0);
        if (result.exit() != 0) status.setText("exit " + result.exit());
    }

    private JButton button(String title, String tip, Runnable action) {
        JButton button = WorkbenchTheme.flatButton(title, tip);
        button.addActionListener(event -> action.run());
        return button;
    }

    private record Beat(String at, String kind, String id, String runId, String text) {
        private static Beat from(JsonNode event) {
            String at = event.path("at").asText("");
            String kind = event.path("kind").asText("");
            String id = event.path("id").asText("");
            String runId = event.path("runId").asText("");
            String text = event.path("stepText").asText("");
            if (text.isBlank()) text = event.path("message").asText("");
            if (text.isBlank()) text = event.path("path").asText("");
            return new Beat(at, kind, id, runId, text);
        }

        private String label() {
            StringBuilder line = new StringBuilder();
            line.append(at).append(' ').append(kind).append(' ').append(id);
            if (!runId.isBlank() && !runId.equals(id)) line.append(' ').append(runId);
            if (!text.isBlank()) line.append(' ').append(text);
            return line.toString();
        }
    }
}
