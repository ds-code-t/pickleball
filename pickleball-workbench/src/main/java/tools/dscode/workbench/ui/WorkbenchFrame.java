package tools.dscode.workbench.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/** Small execution-oriented Swing shell for Workbench project and worker lifecycle. */
final class WorkbenchFrame extends JFrame {
    private final WorkbenchUiController controller;
    private final JButton syncButton = new JButton("Synchronize");
    private final JButton refreshButton = new JButton("Refresh");
    private final JButton startButton = new JButton("Start Worker");
    private final JButton restartButton = new JButton("Restart Worker");
    private final JButton stopButton = new JButton("Stop Worker");
    private final JTextArea statusArea = new JTextArea();
    private final JLabel activityLabel = new JLabel("Ready");
    private WorkbenchUiController.State lastState;
    private boolean closing;

    WorkbenchFrame(WorkbenchUiController controller) {
        super("Pickleball Workbench");
        this.controller = controller;

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(760, 460));
        setSize(860, 560);
        setLocationByPlatform(true);

        statusArea.setEditable(false);
        statusArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        statusArea.setMargin(new Insets(8, 8, 8, 8));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(syncButton);
        actions.add(refreshButton);
        actions.add(startButton);
        actions.add(restartButton);
        actions.add(stopButton);

        add(actions, BorderLayout.NORTH);
        add(new JScrollPane(statusArea), BorderLayout.CENTER);
        add(activityLabel, BorderLayout.SOUTH);

        syncButton.addActionListener(event -> runAction("Synchronizing project", controller::synchronize));
        refreshButton.addActionListener(event -> runAction("Refreshing status", controller::refresh));
        startButton.addActionListener(event -> runAction("Starting worker", controller::startWorker));
        restartButton.addActionListener(event -> runAction("Restarting worker", controller::restartWorker));
        stopButton.addActionListener(event -> runAction("Stopping worker", controller::stopWorker));
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeWorkbench();
            }
        });

        runAction("Loading project status", controller::refresh);
    }

    private void runAction(String label, Supplier<WorkbenchUiController.State> action) {
        if (closing) return;
        setControlsEnabled(false);
        activityLabel.setText(label + "...");

        new SwingWorker<WorkbenchUiController.State, Void>() {
            @Override
            protected WorkbenchUiController.State doInBackground() {
                return action.get();
            }

            @Override
            protected void done() {
                try {
                    WorkbenchUiController.State state = get();
                    applyState(state);
                    activityLabel.setText(label + " complete.");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    showFailure(label, failure);
                } catch (ExecutionException failure) {
                    showFailure(label, failure.getCause());
                }
            }
        }.execute();
    }

    private void applyState(WorkbenchUiController.State state) {
        lastState = state;
        statusArea.setText(state.render());
        statusArea.setCaretPosition(0);
        boolean running = state.workerRunning();
        syncButton.setEnabled(!running);
        refreshButton.setEnabled(true);
        startButton.setEnabled(state.synchronizedProject() && !running);
        restartButton.setEnabled(running);
        stopButton.setEnabled(running);
    }

    private void showFailure(String label, Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        activityLabel.setText(label + " failed.");
        statusArea.setText((message == null || message.isBlank())
                ? String.valueOf(failure)
                : message);
        if (lastState != null) {
            applyState(lastState);
        } else {
            syncButton.setEnabled(true);
            refreshButton.setEnabled(true);
            startButton.setEnabled(false);
            restartButton.setEnabled(false);
            stopButton.setEnabled(false);
        }
    }

    private void setControlsEnabled(boolean enabled) {
        syncButton.setEnabled(enabled);
        refreshButton.setEnabled(enabled);
        startButton.setEnabled(enabled);
        restartButton.setEnabled(enabled);
        stopButton.setEnabled(enabled);
    }

    private void closeWorkbench() {
        if (closing) return;
        closing = true;
        setControlsEnabled(false);
        activityLabel.setText("Stopping Workbench resources...");

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                controller.close();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    System.err.println("Workbench UI close failed: "
                            + (cause == null ? failure.getMessage() : cause.getMessage()));
                } finally {
                    dispose();
                }
            }
        }.execute();
    }
}
