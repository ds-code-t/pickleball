package tools.dscode.workbench.ui;

import tools.dscode.workbench.terminal.WorkerLogBuffer;
import tools.dscode.workbench.terminal.WorkerLogFiles;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Font;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scenario-run log presentation over the existing worker stdout/stderr files.
 * It never writes to MCP stdout and never fabricates log lines.
 */
final class TerminalPanel extends JPanel {
    private final WorkerLogBuffer buffer = new WorkerLogBuffer();
    private final JTextArea area = new JTextArea();
    private final JComboBox<WorkerLogBuffer.Level> filter = new JComboBox<>(WorkerLogBuffer.Level.values());
    private final Map<Path, Long> positions = new LinkedHashMap<>();
    private WorkerLogFiles files;
    private final Timer poller = new Timer(750, event -> poll());

    TerminalPanel() {
        super(new BorderLayout(8, 8));
        setBackground(WorkbenchTheme.SURFACE);
        setBorder(new EmptyBorder(10, 12, 12, 12));

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        top.add(WorkbenchTheme.heading("Worker log"), BorderLayout.WEST);
        filter.setSelectedItem(WorkerLogBuffer.Level.INFO);
        filter.addActionListener(event -> {
            buffer.setMinimum((WorkerLogBuffer.Level) filter.getSelectedItem());
            render();
        });
        top.add(filter, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setBackground(WorkbenchTheme.SURFACE);
        area.setForeground(WorkbenchTheme.TEXT);
        add(new JScrollPane(area), BorderLayout.CENTER);
        poller.setRepeats(true);
    }

    void start() {
        if (!poller.isRunning()) poller.start();
    }

    void stop() {
        poller.stop();
    }

    void setFiles(WorkerLogFiles files) {
        this.files = files;
        positions.clear();
        buffer.clear();
        render();
        poll();
    }

    void appendExecution(String heading, String output, String events) {
        StringBuilder text = new StringBuilder();
        if (heading != null && !heading.isBlank()) text.append("[INFO] ").append(heading).append('\n');
        if (output != null && !output.isBlank()) text.append(output).append('\n');
        if (events != null && !events.isBlank()) text.append(events).append('\n');
        buffer.appendRaw(text.toString());
        render();
    }

    void noteGap(String message) {
        area.setToolTipText(message);
    }

    private void poll() {
        if (files == null) return;
        for (Path file : files.existing()) {
            if (file == null || !Files.isRegularFile(file)) continue;
            try {
                String next = readSince(file);
                if (!next.isEmpty()) buffer.appendRaw(next);
            } catch (IOException ignored) {
                // A rotating or briefly locked worker log must not crash the UI.
            }
        }
        render();
    }

    private String readSince(Path file) throws IOException {
        long previous = positions.getOrDefault(file, 0L);
        long size = Files.size(file);
        if (size < previous) previous = 0L;
        if (size == previous) return "";
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(previous);
            byte[] bytes = new byte[(int) Math.min(Integer.MAX_VALUE, size - previous)];
            raf.readFully(bytes);
            positions.put(file, size);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private void render() {
        StringBuilder text = new StringBuilder();
        for (WorkerLogBuffer.Entry entry : buffer.visible()) {
            if (!text.isEmpty()) text.append('\n');
            text.append(entry.raw());
        }
        if (!text.toString().equals(area.getText())) {
            area.setText(text.toString());
            area.setCaretPosition(area.getDocument().getLength());
        }
    }
}
