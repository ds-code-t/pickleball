package tools.dscode.studio.gui;

import tools.dscode.studio.runtime.RuntimeBreakpoint;
import tools.dscode.studio.runtime.RuntimeElementInspectionResult;
import tools.dscode.studio.runtime.RuntimeServiceCallResult;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class RuntimeInvestigationPanel extends JPanel {
    private final StudioDesktopSession session;
    private final Supplier<String> sessionId;
    private final Supplier<String> runtimeId;
    private final Supplier<String> scenarioId;
    private final Consumer<String> output;

    private final JTextField category = new JTextField("Button");
    private final JTextField elementText = new JTextField();
    private final JTextField operation = new JTextField("DEFAULT");
    private final JTextField maxElements = new JTextField("20");
    private final JTextField serviceSelector = new JTextField("%health-full-url");
    private final JTextField hook = new JTextField("BEFORE_STEP");
    private final JTextField signature = new JTextField();
    private final JTextField step = new JTextField();
    private final JTextField phrase = new JTextField();
    private final JCheckBox oneShot = new JCheckBox("One shot", true);
    private final JTextField leaseSeconds = new JTextField("120");
    private final JComboBox<RuntimeBreakpoint> breakpointBox = new JComboBox<>();

    RuntimeInvestigationPanel(
            StudioDesktopSession session,
            Supplier<String> sessionId,
            Supplier<String> runtimeId,
            Supplier<String> scenarioId,
            Consumer<String> output
    ) {
        super(new BorderLayout(6, 6));
        this.session = session;
        this.sessionId = sessionId;
        this.runtimeId = runtimeId;
        this.scenarioId = scenarioId;
        this.output = output;

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Elements", elementPanel());
        tabs.addTab("Service Calls", servicePanel());
        tabs.addTab("Breakpoints", breakpointPanel());
        add(tabs, BorderLayout.CENTER);

        breakpointBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean selected, boolean focused
            ) {
                String text = value instanceof RuntimeBreakpoint bp
                        ? bp.breakpointId() + "  " + Objects.toString(bp.hook(), "ANY") + "  hits=" + bp.hitCount()
                        : String.valueOf(value);
                return super.getListCellRendererComponent(list, text, index, selected, focused);
            }
        });
    }

    void refreshBreakpoints() {
        if (!hasTarget()) {
            breakpointBox.removeAllItems();
            return;
        }
        async(() -> session.runtimeBreakpoints(sessionId.get(), runtimeId.get()), values -> {
            RuntimeBreakpoint selected = selectedBreakpoint();
            String selectedId = selected == null ? null : selected.breakpointId();
            breakpointBox.setModel(new DefaultComboBoxModel<>(values.toArray(RuntimeBreakpoint[]::new)));
            if (selectedId != null) {
                for (int i = 0; i < breakpointBox.getItemCount(); i++) {
                    if (selectedId.equals(breakpointBox.getItemAt(i).breakpointId())) {
                        breakpointBox.setSelectedIndex(i);
                        break;
                    }
                }
            }
        });
    }

    private JPanel elementPanel() {
        JPanel fields = new JPanel(new GridLayout(4, 2, 6, 4));
        fields.add(new JLabel("Category")); fields.add(category);
        fields.add(new JLabel("Text/value")); fields.add(elementText);
        fields.add(new JLabel("Operation")); fields.add(operation);
        fields.add(new JLabel("Max evidence")); fields.add(maxElements);
        JButton inspect = new JButton("Inspect Pickleball Elements");
        inspect.addActionListener(e -> runElementInspect());
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.add(fields, BorderLayout.CENTER);
        panel.add(inspect, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel servicePanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.add(serviceSelector, BorderLayout.CENTER);
        JButton execute = new JButton("Execute Service Call");
        execute.addActionListener(e -> runServiceCall());
        panel.add(execute, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel breakpointPanel() {
        JPanel fields = new JPanel(new GridLayout(7, 2, 6, 4));
        fields.add(new JLabel("Hook")); fields.add(hook);
        fields.add(new JLabel("Signature contains")); fields.add(signature);
        fields.add(new JLabel("Step contains")); fields.add(step);
        fields.add(new JLabel("Phrase contains")); fields.add(phrase);
        fields.add(new JLabel("Lease seconds")); fields.add(leaseSeconds);
        fields.add(new JLabel("Behavior")); fields.add(oneShot);
        fields.add(new JLabel("Existing")); fields.add(breakpointBox);

        JPanel actions = new JPanel();
        JButton add = new JButton("Add");
        JButton refresh = new JButton("Refresh");
        JButton remove = new JButton("Remove");
        JButton clear = new JButton("Clear All");
        add.addActionListener(e -> addBreakpoint());
        refresh.addActionListener(e -> refreshBreakpoints());
        remove.addActionListener(e -> removeBreakpoint());
        clear.addActionListener(e -> clearBreakpoints());
        actions.add(add); actions.add(refresh); actions.add(remove); actions.add(clear);

        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.add(fields, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private void runElementInspect() {
        if (!hasTarget()) return;
        Integer max = parseInteger(maxElements.getText(), "max evidence");
        if (max == null) return;
        async(
                () -> session.runtimeElementInspect(
                        sessionId.get(), runtimeId.get(), scenarioId.get(),
                        category.getText(), blankToNull(elementText.getText()),
                        blankToNull(operation.getText()), max
                ),
                this::showElementResult
        );
    }

    private void runServiceCall() {
        if (!hasTarget()) return;
        async(
                () -> session.runtimeServiceCall(
                        sessionId.get(), runtimeId.get(), scenarioId.get(), serviceSelector.getText()
                ),
                this::showServiceResult
        );
    }

    private void addBreakpoint() {
        if (!hasTarget()) return;
        Integer lease = parseInteger(leaseSeconds.getText(), "lease seconds");
        if (lease == null) return;
        async(
                () -> session.runtimeBreakpointAdd(
                        sessionId.get(), runtimeId.get(), scenarioId.get(),
                        blankToNull(hook.getText()), blankToNull(signature.getText()),
                        blankToNull(step.getText()), blankToNull(phrase.getText()),
                        oneShot.isSelected(), lease
                ),
                value -> {
                    output.accept("Breakpoint added: " + value);
                    refreshBreakpoints();
                }
        );
    }

    private void removeBreakpoint() {
        if (!hasTarget()) return;
        RuntimeBreakpoint selected = selectedBreakpoint();
        if (selected == null) {
            output.accept("No breakpoint is selected.");
            return;
        }
        async(
                () -> session.runtimeBreakpointRemove(sessionId.get(), runtimeId.get(), selected.breakpointId()),
                removed -> {
                    output.accept("Breakpoint removed: " + removed);
                    refreshBreakpoints();
                }
        );
    }

    private void clearBreakpoints() {
        if (!hasTarget()) return;
        async(
                () -> session.runtimeBreakpointsClear(sessionId.get(), runtimeId.get()),
                removed -> {
                    output.accept("Breakpoints removed: " + removed);
                    refreshBreakpoints();
                }
        );
    }

    private boolean hasTarget() {
        if (sessionId.get() == null || runtimeId.get() == null) {
            output.accept("No live runtime is selected.");
            return false;
        }
        return true;
    }

    private RuntimeBreakpoint selectedBreakpoint() {
        Object selected = breakpointBox.getSelectedItem();
        return selected instanceof RuntimeBreakpoint bp ? bp : null;
    }

    private Integer parseInteger(String text, String label) {
        try {
            return Integer.valueOf(text.trim());
        } catch (RuntimeException failure) {
            output.accept("Invalid " + label + ": " + text);
            return null;
        }
    }

    private void showElementResult(RuntimeElementInspectionResult value) {
        StringBuilder text = new StringBuilder(value.status());
        if (value.inspection() != null) {
            text.append("\nMatches: ").append(value.inspection().matchCount());
            text.append("\nResolved XPath: ").append(value.inspection().resolvedXPath());
            value.inspection().elements().forEach(element -> text.append("\n\n#")
                    .append(element.index()).append(' ').append(element.tagName())
                    .append(" displayed=").append(element.displayed())
                    .append(" enabled=").append(element.enabled())
                    .append(" selected=").append(element.selected())
                    .append("\ntext=").append(element.text())
                    .append("\nvalue=").append(element.value())
                    .append("\nrect=").append(element.x()).append(',').append(element.y())
                    .append(' ').append(element.width()).append('x').append(element.height())
                    .append("\nattributes=").append(element.attributes())
                    .append("\nouterHTML=").append(element.outerHtml()));
        }
        if (value.error() != null) text.append("\nError: ").append(value.error().message());
        output.accept(text.toString());
    }

    private void showServiceResult(RuntimeServiceCallResult value) {
        StringBuilder text = new StringBuilder(value.status());
        if (value.evidence() != null) {
            text.append("\nSelector: ").append(value.evidence().selector());
            text.append("\nStatus code: ").append(value.evidence().statusCode());
            text.append("\nREQUEST: ").append(value.evidence().request());
            text.append("\nCONFIGURATION: ").append(value.evidence().configuration());
            text.append("\nRESPONSE: ").append(value.evidence().response());
        }
        if (value.error() != null) text.append("\nError: ").append(value.error().message());
        output.accept(text.toString());
    }

    private <T> void async(Supplier<T> work, Consumer<T> success) {
        Thread.ofVirtual().name("studio-investigation-ui-work").start(() -> {
            try {
                T value = work.get();
                SwingUtilities.invokeLater(() -> success.accept(value));
            } catch (RuntimeException failure) {
                SwingUtilities.invokeLater(() -> output.accept(
                        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()
                ));
            }
        });
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
