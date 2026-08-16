
package tools.dscode.studio.gui;

import tools.dscode.studio.language.SourceDiagnostic;
import tools.dscode.studio.language.SourceOutline;
import tools.dscode.studio.language.SourceSymbol;
import tools.dscode.studio.process.ManagedProcessSummary;
import tools.dscode.studio.process.ProcessOutputChunk;
import tools.dscode.studio.process.ProcessState;
import tools.dscode.studio.workspace.WorkspaceEntry;
import tools.dscode.studio.workspace.WorkspaceTextFile;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class StudioFrame extends JFrame {
    private static final int TREE_DEPTH = 20;
    private static final int TREE_ENTRIES = 10_000;
    private static final int SYMBOL_RESULTS = 200;
    private static final int MAX_OUTPUT_CHARS = 2 * 1024 * 1024;

    private final StudioDesktopSession session;
    private final Map<String, EditorTab> editors = new LinkedHashMap<>();

    private final JTree workspaceTree = new JTree(new DefaultMutableTreeNode("Loading..."));
    private final JTabbedPane editorTabs = new JTabbedPane();
    private final DefaultListModel<SourceSymbol> outlineModel = new DefaultListModel<>();
    private final JList<SourceSymbol> outlineList = new JList<>(outlineModel);
    private final JTextArea diagnostics = new JTextArea();
    private final JTextField symbolQuery = new JTextField();
    private final DefaultListModel<SourceSymbol> symbolModel = new DefaultListModel<>();
    private final JList<SourceSymbol> symbolList = new JList<>(symbolModel);
    private final JTextArea output = new JTextArea();
    private final JLabel status = new JLabel("Ready");

    private final JButton saveButton = new JButton("Save");
    private final JButton reloadButton = new JButton("Reload");
    private final JButton runButton = new JButton();
    private final JButton cancelButton = new JButton("Cancel Run");

    private final Timer processTimer;

    private String activeProcessId;
    private long stdoutOffset;
    private long stderrOffset;
    private boolean stdoutTruncationReported;
    private boolean stderrTruncationReported;

    StudioFrame(StudioDesktopSession session) {
        super("Pickleball Studio — " + session.workspace().name());
        this.session = session;

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1000, 700));
        setSize(1450, 900);
        setLocationByPlatform(true);

        processTimer = new Timer(250, event -> pollProcess());

        configureTree();
        configureSymbolLists();
        configureEditorTabs();

        setJMenuBar(null);
        add(toolbar(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        configureShortcuts();
        configureClose();
        refreshTree();
        updateActions();
    }

    private JToolBar toolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(event -> refreshTree());

        saveButton.addActionListener(event -> saveSelected());
        reloadButton.addActionListener(event -> reloadSelected());

        String buildTool = session.testBuildTool();
        runButton.setText(buildTool == null ? "Run Tests" : "Run " + buildTool + " Tests");
        runButton.setEnabled(buildTool != null);
        runButton.addActionListener(event -> runTests());

        cancelButton.addActionListener(event -> cancelRun());
        cancelButton.setEnabled(false);

        bar.add(refreshButton);
        bar.addSeparator();
        bar.add(saveButton);
        bar.add(reloadButton);
        bar.addSeparator();
        bar.add(runButton);
        bar.add(cancelButton);
        return bar;
    }

    private JSplitPane content() {
        JScrollPane treeScroll = new JScrollPane(workspaceTree);
        treeScroll.setPreferredSize(new Dimension(300, 700));

        JSplitPane editorAndOutline = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                editorTabs,
                navigationPanel()
        );
        editorAndOutline.setResizeWeight(0.78);
        editorAndOutline.setDividerLocation(850);

        JSplitPane horizontal = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                treeScroll,
                editorAndOutline
        );
        horizontal.setResizeWeight(0.18);
        horizontal.setDividerLocation(300);

        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane outputScroll = new JScrollPane(output);

        JSplitPane vertical = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                horizontal,
                outputScroll
        );
        vertical.setResizeWeight(0.76);
        vertical.setDividerLocation(650);
        return vertical;
    }

    private JTabbedPane navigationPanel() {
        JTabbedPane navigation = new JTabbedPane();

        JPanel outlinePanel = new JPanel(new BorderLayout());
        outlinePanel.add(new JScrollPane(outlineList), BorderLayout.CENTER);
        diagnostics.setEditable(false);
        diagnostics.setRows(6);
        diagnostics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        outlinePanel.add(new JScrollPane(diagnostics), BorderLayout.SOUTH);

        JPanel symbolsPanel = new JPanel(new BorderLayout(4, 4));
        JPanel searchBar = new JPanel(new BorderLayout(4, 4));
        JButton searchButton = new JButton("Search");
        searchButton.addActionListener(event -> searchSymbols());
        symbolQuery.addActionListener(event -> searchSymbols());
        searchBar.add(symbolQuery, BorderLayout.CENTER);
        searchBar.add(searchButton, BorderLayout.EAST);
        symbolsPanel.add(searchBar, BorderLayout.NORTH);
        symbolsPanel.add(new JScrollPane(symbolList), BorderLayout.CENTER);

        navigation.addTab("Outline", outlinePanel);
        navigation.addTab("Symbols", symbolsPanel);
        navigation.setPreferredSize(new Dimension(300, 700));
        return navigation;
    }

    private void configureTree() {
        workspaceTree.setRootVisible(true);
        workspaceTree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() != 2) {
                    return;
                }
                TreePath selection = workspaceTree.getPathForLocation(event.getX(), event.getY());
                if (selection == null) {
                    return;
                }
                Object value = ((DefaultMutableTreeNode) selection.getLastPathComponent()).getUserObject();
                if (value instanceof WorkspaceTreeItem item && !item.directory()) {
                    openFile(item.path(), null);
                }
            }
        });
    }

    private void configureSymbolLists() {
        DefaultListCellRenderer renderer = new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean selected,
                    boolean focused
            ) {
                String text = value instanceof SourceSymbol symbol
                        ? symbol.kind() + "  " + symbol.name() + "  @" + symbol.location().line()
                        : String.valueOf(value);
                return super.getListCellRendererComponent(
                        list, text, index, selected, focused
                );
            }
        };

        outlineList.setCellRenderer(renderer);
        symbolList.setCellRenderer(renderer);
        outlineList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        symbolList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        outlineList.addMouseListener(new SymbolOpenMouseListener(outlineList));
        symbolList.addMouseListener(new SymbolOpenMouseListener(symbolList));
    }

    private void configureEditorTabs() {
        editorTabs.addChangeListener(event -> {
            updateActions();
            refreshOutline();
        });
    }

    private void configureShortcuts() {
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK),
                "save"
        );
        getRootPane().getActionMap().put("save", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                saveSelected();
            }
        });

        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK),
                "reload"
        );
        getRootPane().getActionMap().put("reload", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                reloadSelected();
            }
        });
    }

    private void configureClose() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                boolean dirty = editors.values().stream().anyMatch(EditorTab::dirty);
                if (dirty) {
                    int answer = JOptionPane.showConfirmDialog(
                            StudioFrame.this,
                            "Discard unsaved editor changes and close Pickleball Studio?",
                            "Unsaved changes",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE
                    );
                    if (answer != JOptionPane.YES_OPTION) {
                        return;
                    }
                }

                processTimer.stop();
                session.close();
                dispose();
            }
        });
    }

    private void refreshTree() {
        status.setText("Refreshing workspace...");
        async(
                () -> session.tree(TREE_DEPTH, TREE_ENTRIES),
                entries -> {
                    DefaultMutableTreeNode root = WorkspaceTreeBuilder.build(
                            session.workspace().name(),
                            entries
                    );
                    workspaceTree.setModel(new DefaultTreeModel(root));
                    workspaceTree.expandRow(0);
                    status.setText(
                            "Workspace: " + session.workspace().root()
                                    + " — " + entries.size() + " entries"
                                    + (entries.size() >= TREE_ENTRIES ? " (tree limit reached)" : "")
                    );
                }
        );
    }

    private void openFile(String path, Integer line) {
        EditorTab existing = editors.get(path);
        if (existing != null) {
            editorTabs.setSelectedComponent(existing);
            if (line != null) {
                existing.navigateToLine(line);
            }
            return;
        }

        status.setText("Opening " + path + "...");
        async(
                () -> session.read(path),
                file -> openLoadedFile(file, line)
        );
    }

    private void openLoadedFile(WorkspaceTextFile file, Integer line) {
        EditorTab existing = editors.get(file.path());
        if (existing != null) {
            editorTabs.setSelectedComponent(existing);
            return;
        }

        EditorTab tab = new EditorTab(file.path(), file.content(), () ->
                SwingUtilities.invokeLater(() -> updateTabTitle(file.path()))
        );
        editors.put(file.path(), tab);
        editorTabs.addTab(tab.title(), tab);
        editorTabs.setSelectedComponent(tab);
        if (line != null) {
            tab.navigateToLine(line);
        }
        updateActions();
        refreshOutline();
        status.setText(file.path());
    }

    private void updateTabTitle(String path) {
        EditorTab tab = editors.get(path);
        if (tab == null) {
            return;
        }
        int index = editorTabs.indexOfComponent(tab);
        if (index >= 0) {
            editorTabs.setTitleAt(index, tab.title());
        }
        updateActions();
    }

    private EditorTab selectedEditor() {
        java.awt.Component selected = editorTabs.getSelectedComponent();
        return selected instanceof EditorTab tab ? tab : null;
    }

    private void saveSelected() {
        EditorTab tab = selectedEditor();
        if (tab == null) {
            return;
        }

        String content = tab.text();
        status.setText("Saving " + tab.path() + "...");
        async(
                () -> session.save(tab.path(), content),
                ignored -> {
                    tab.markSaved(content);
                    updateTabTitle(tab.path());
                    refreshOutline();
                    status.setText("Saved " + tab.path());
                }
        );
    }

    private void reloadSelected() {
        EditorTab tab = selectedEditor();
        if (tab == null) {
            return;
        }

        if (tab.dirty()) {
            int answer = JOptionPane.showConfirmDialog(
                    this,
                    "Discard unsaved changes in " + tab.path() + "?",
                    "Reload file",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
        }

        status.setText("Reloading " + tab.path() + "...");
        async(
                () -> session.read(tab.path()),
                file -> {
                    tab.replaceSavedContent(file.content());
                    updateTabTitle(tab.path());
                    refreshOutline();
                    status.setText("Reloaded " + tab.path());
                }
        );
    }

    private void refreshOutline() {
        EditorTab tab = selectedEditor();
        outlineModel.clear();
        diagnostics.setText("");

        if (tab == null || !(tab.path().endsWith(".java") || tab.path().endsWith(".feature"))) {
            return;
        }

        String path = tab.path();
        async(
                () -> session.outline(path),
                outline -> {
                    EditorTab selected = selectedEditor();
                    if (selected == null || !outline.path().equals(selected.path())) {
                        return;
                    }
                    showOutline(outline);
                }
        );
    }

    private void showOutline(SourceOutline outline) {
        outlineModel.clear();
        outline.symbols().forEach(outlineModel::addElement);

        StringBuilder text = new StringBuilder();
        for (SourceDiagnostic diagnostic : outline.diagnostics()) {
            text.append(diagnostic.severity()).append(": ");
            if (diagnostic.line() != null) {
                text.append(diagnostic.line());
                if (diagnostic.column() != null) {
                    text.append(':').append(diagnostic.column());
                }
                text.append(' ');
            }
            text.append(diagnostic.message()).append(System.lineSeparator());
        }
        diagnostics.setText(text.toString());
        diagnostics.setCaretPosition(0);
    }

    private void searchSymbols() {
        String query = symbolQuery.getText().trim();
        if (query.isEmpty()) {
            symbolModel.clear();
            return;
        }

        status.setText("Searching symbols for " + query + "...");
        async(
                () -> session.searchSymbols(query, SYMBOL_RESULTS),
                results -> {
                    if (!query.equals(symbolQuery.getText().trim())) {
                        return;
                    }
                    symbolModel.clear();
                    results.forEach(symbolModel::addElement);
                    status.setText("Symbol matches: " + results.size());
                }
        );
    }

    private void openSymbol(SourceSymbol symbol) {
        openFile(symbol.location().path(), symbol.location().line());
    }

    private void runTests() {
        if (activeProcessId != null) {
            status.setText("A managed test run is already active.");
            return;
        }

        output.setText("");
        stdoutOffset = 0;
        stderrOffset = 0;
        stdoutTruncationReported = false;
        stderrTruncationReported = false;
        String buildTool = session.testBuildTool();
        appendOutput("Starting " + buildTool + " tests..." + System.lineSeparator());

        runButton.setEnabled(false);
        status.setText("Starting tests...");
        async(
                session::startTests,
                process -> {
                    activeProcessId = process.id();
                    cancelButton.setEnabled(true);
                    status.setText("Running tests — " + process.id());
                    processTimer.start();
                },
                () -> runButton.setEnabled(
                        activeProcessId == null && session.testBuildTool() != null
                )
        );
    }

    private void pollProcess() {
        if (activeProcessId == null) {
            processTimer.stop();
            return;
        }

        try {
            ProcessOutputChunk chunk = session.processOutput(
                    activeProcessId,
                    stdoutOffset,
                    stderrOffset
            );
            stdoutOffset = chunk.nextStdoutOffset();
            stderrOffset = chunk.nextStderrOffset();

            if (chunk.stdoutGap()) {
                appendOutput("[stdout history gap]" + System.lineSeparator());
            }
            if (!chunk.stdout().isEmpty()) {
                appendOutput(chunk.stdout());
            }
            if (chunk.stderrGap()) {
                appendOutput("[stderr history gap]" + System.lineSeparator());
            }
            if (!chunk.stderr().isEmpty()) {
                appendOutput("[stderr]" + System.lineSeparator());
                appendOutput(chunk.stderr());
            }
            if (chunk.stdoutTruncated() && !stdoutTruncationReported) {
                appendOutput("[stdout history truncated]" + System.lineSeparator());
                stdoutTruncationReported = true;
            }
            if (chunk.stderrTruncated() && !stderrTruncationReported) {
                appendOutput("[stderr history truncated]" + System.lineSeparator());
                stderrTruncationReported = true;
            }

            ManagedProcessSummary summary = session.processStatus(activeProcessId);
            status.setText("Tests: " + summary.state());
            if (summary.state() == ProcessState.RUNNING) {
                cancelButton.setEnabled(true);
            }
            if (summary.state() != ProcessState.RUNNING) {
                appendOutput(
                        System.lineSeparator()
                                + "Run finished: " + summary.state()
                                + (summary.exitCode() == null ? "" : " (exit " + summary.exitCode() + ")")
                                + System.lineSeparator()
                );
                activeProcessId = null;
                processTimer.stop();
                cancelButton.setEnabled(false);
                runButton.setEnabled(session.testBuildTool() != null);
            }
        } catch (RuntimeException failure) {
            processTimer.stop();
            activeProcessId = null;
            cancelButton.setEnabled(false);
            runButton.setEnabled(session.testBuildTool() != null);
            showError(failure);
        }
    }

    private void cancelRun() {
        if (activeProcessId == null) {
            return;
        }

        String id = activeProcessId;
        status.setText("Cancelling " + id + "...");
        cancelButton.setEnabled(false);
        async(
                () -> session.cancelProcess(id),
                summary -> status.setText("Tests: " + summary.state())
        );
    }

    private void appendOutput(String text) {
        output.append(text);
        int excess = output.getDocument().getLength() - MAX_OUTPUT_CHARS;
        if (excess > 0) {
            try {
                output.getDocument().remove(0, excess);
            } catch (javax.swing.text.BadLocationException ignored) {
                output.setText(output.getText());
            }
        }
        output.setCaretPosition(output.getDocument().getLength());
    }

    private void updateActions() {
        EditorTab tab = selectedEditor();
        saveButton.setEnabled(tab != null && tab.dirty());
        reloadButton.setEnabled(tab != null);
        if (activeProcessId == null) {
            runButton.setEnabled(session.testBuildTool() != null);
        }
    }

    private <T> void async(Supplier<T> work, Consumer<T> success) {
        async(work, success, () -> {
        });
    }

    private <T> void async(
            Supplier<T> work,
            Consumer<T> success,
            Runnable finallyAction
    ) {
        Thread.ofVirtual().name("studio-ui-work").start(() -> {
            try {
                T result = work.get();
                SwingUtilities.invokeLater(() -> {
                    try {
                        success.accept(result);
                    } finally {
                        finallyAction.run();
                    }
                });
            } catch (RuntimeException failure) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        showError(failure);
                    } finally {
                        finallyAction.run();
                    }
                });
            }
        });
    }

    private void showError(RuntimeException failure) {
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
        status.setText(message);
        JOptionPane.showMessageDialog(
                this,
                message,
                "Pickleball Studio",
                JOptionPane.ERROR_MESSAGE
        );
    }

    private final class SymbolOpenMouseListener extends java.awt.event.MouseAdapter {
        private final JList<SourceSymbol> list;

        private SymbolOpenMouseListener(JList<SourceSymbol> list) {
            this.list = list;
        }

        @Override
        public void mouseClicked(java.awt.event.MouseEvent event) {
            if (event.getClickCount() == 2) {
                SourceSymbol symbol = list.getSelectedValue();
                if (symbol != null) {
                    openSymbol(symbol);
                }
            }
        }
    }
}
