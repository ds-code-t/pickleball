package tools.dscode.workbench.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.control.protocol.ControlBridgeMappingSnapshot;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.PickleballVersion;
import tools.dscode.control.protocol.ControlBridgeStepResolution;
import tools.dscode.workbench.catalog.CatalogTargetResolver;
import tools.dscode.workbench.catalog.ConsumerFeatureCatalog;
import tools.dscode.workbench.catalog.JavaGlueIndex;
import tools.dscode.workbench.diagnostics.DiagnosticEvidenceNavigator;
import tools.dscode.workbench.nav.WorkbenchGoLink;
import tools.dscode.workbench.nav.WorkbenchGoResolver;
import tools.dscode.workbench.lease.WorkbenchControlLeaseSnapshot;
import tools.dscode.workbench.lease.WorkbenchPermissionRequest;
import tools.dscode.workbench.mapping.MappingTreeModel;
import tools.dscode.workbench.mapping.MappingValueCodec;
import tools.dscode.workbench.mcp.WorkbenchAttachServer;
import tools.dscode.workbench.player.EditorTabState;
import tools.dscode.workbench.player.GherkinPlayPlan;
import tools.dscode.workbench.player.GherkinReference;
import tools.dscode.workbench.player.GherkinTextEditing;
import tools.dscode.workbench.player.ScenarioOrigin;
import tools.dscode.workbench.player.LivePlaybackCoordinator;
import tools.dscode.workbench.player.LiveScenarioPlayer;
import tools.dscode.workbench.player.WorkbenchSavePreview;
import tools.dscode.workbench.player.WorkbenchSaveResult;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.ui.web.DiagnosticExplorerHost;
import tools.dscode.workbench.ui.web.JavaFxSupport;
import tools.dscode.workbench.ui.web.MappingEditorHost;
import tools.dscode.workbench.ui.web.WebViewPanel;
import tools.dscode.workbench.ui.web.WorkbenchWebJson;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Player-style Swing presentation adapter over the shared Workbench service seam. */
final class WorkbenchFrame extends JFrame {
    private static final int MAPPING_SAVE_DELAY_MS = 650;

    private final WorkbenchUiController controller;
    private final WorkbenchAttachServer attach;
    private final LiveScenarioPlayer player;
    private final LivePlaybackCoordinator playback;
    private final ObjectMapper json = new ObjectMapper();
    private final FeaturePickerPanel picker = new FeaturePickerPanel();
    private final TerminalPanel terminal = new TerminalPanel();
    private final MappingEditorHost mappingHost = new MappingEditorHost();
    private final DiagnosticExplorerHost diagnosticHost = new DiagnosticExplorerHost();
    private WebViewPanel mappingView;
    private WebViewPanel diagnosticView;
    private JComponent pickerSplit;
    private final JToggleButton pickerToggle = new JToggleButton("Scenarios");
    private List<WorkbenchUiController.MappingCatalogEntry> mappingEntries = List.of();
    private MappingTreeModel mappingModel;
    private DiagnosticEvidenceNavigator diagnosticNavigator;
    private String currentDiagnosticRunId = "";
    private final JComboBox<String> reportPicker = new JComboBox<>();
    private final JEditorPane reportView = new JEditorPane();
    private final Map<String, Path> reportFiles = new LinkedHashMap<>();

    private static final Color PLAYHEAD_COLOR = WorkbenchTheme.PLAYHEAD;
    private final JTextArea scenarioEditor = new JTextArea();
    private final Highlighter.HighlightPainter playheadPainter =
            new DefaultHighlighter.DefaultHighlightPainter(PLAYHEAD_COLOR);
    private final JPopupMenu keywordPopup = new JPopupMenu();
    private final JList<String> keywordList = new JList<>();
    private final JTextField stepText = new JTextField();

    private final JButton playButton = WorkbenchTheme.accentButton("▶ Play", "Run the scenario from the first step in a fresh scenario context");
    private final JButton pauseButton = WorkbenchTheme.flatButton("Pause", "Pause after the current in-flight step");
    private final JButton playerStopButton = WorkbenchTheme.flatButton("Stop", "Stop automatic scenario advancement");
    private final JButton stepOnlyButton =
            smallPlayerButton("▶ Step", "Execute only the Step Editor text in the current paused scenario context");
    private final JButton fromHereButton =
            smallPlayerButton("▶ From Here", "Start a fresh scenario context and run from the selected step");

    private final JLabel projectLabel = new JLabel("Project: loading...");
    private final JLabel versionLabel = new JLabel(
            "Pickleball " + PickleballVersion.running(WorkbenchFrame.class)
    );
    private final JLabel readinessLabel = new JLabel("Loading status...");
    private final JLabel playerStatusLabel = new JLabel("Stopped");
    private final JLabel activityLabel = new JLabel("Ready");
    private final JPanel agentBanner = new JPanel(new BorderLayout(12, 0));
    private final JLabel agentBannerLabel = new JLabel();
    private final JButton takeControlButton = WorkbenchTheme.accentButton(
            "Take control",
            "Return live Workbench controls to the human and cancel in-flight agent permission waits"
    );
    private final JPanel permissionBar = new JPanel(new BorderLayout(12, 0));
    private final JLabel permissionLabel = new JLabel();
    private final JButton allowButton = WorkbenchTheme.accentButton("Allow", "Allow the agent to copy the live scenario into the original feature file");
    private final JButton denyButton = WorkbenchTheme.flatButton("Deny", "Deny the write; the original feature file is left unchanged");
    private WorkbenchControlLeaseSnapshot lastLease;
    private String pendingPermissionId;

    private final JMenuItem syncItem = new JMenuItem("Synchronize");
    private final JMenuItem refreshItem = new JMenuItem("Refresh Status");
    private final JMenuItem startItem = new JMenuItem("Start Worker");
    private final JMenuItem restartItem = new JMenuItem("Restart Worker");
    private final JMenuItem stopItem = new JMenuItem("Stop Worker");

    private final JComboBox<WorkbenchUiController.MappingCatalogEntry> nodeMapSelector =
            new JComboBox<>();
    private final JTextArea mappingEditor = new JTextArea();
    private final JLabel mappingStatus = new JLabel("Start the live worker to inspect Mapping.");
    private final Timer mappingSaveTimer = new Timer(
            MAPPING_SAVE_DELAY_MS,
            event -> saveEditedMapping()
    );

    private final JLabel webViewNote = WorkbenchTheme.muted("");
    private JTabbedPane rightTabs;

    private WorkbenchUiController.State lastState;
    private ControlBridgeMappingSnapshot loadedMapping;
    private boolean loadingMapping;
    private boolean refreshingCatalog;
    private boolean mappingSaveBusy;
    private long mappingEditGeneration;

    private boolean playbackPreparing;
    private boolean playbackBusy;
    private Long executingStepId;
    private boolean pendingFreshRun;
    private Long pendingFreshRunStepId;
    private String pendingIsolatedStep;
    private boolean syncingScenarioDocument;
    private boolean closing;
    private final JTabbedPane editorTabs = new JTabbedPane();
    private final JTextArea javaInspector = new JTextArea();
    private final JButton openTargetButton = WorkbenchTheme.flatButton("Open target", "Open the component, scenario, service call, or data file referenced by the selected step");
    private JavaGlueIndex glueIndex = new JavaGlueIndex(List.of());
    private Path pinnedCallerFile;
    private final List<EditorTabState> editorSessions = new ArrayList<>();
    private boolean rebuildingTabs;
    private int shownTabIndex;

    WorkbenchFrame(WorkbenchUiController controller) {
        this(controller, null);
    }

    WorkbenchFrame(WorkbenchUiController controller, WorkbenchAttachServer attach) {
        super("Pickleball Workbench");
        this.controller = controller;
        this.attach = attach;
        this.player = controller.player();
        this.playback = controller.playback();
        editorSessions.add(new EditorTabState("Demo", null, player.documentText(), true));

        mappingSaveTimer.setRepeats(false);
        WorkbenchTheme.install();
        getContentPane().setBackground(WorkbenchTheme.BACKGROUND);

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1180, 740));
        setSize(1560, 920);
        setLocationByPlatform(true);
        setJMenuBar(menuBar());

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(WorkbenchTheme.BACKGROUND);
        root.setBorder(new EmptyBorder(10, 12, 10, 12));
        root.add(topChrome(), BorderLayout.NORTH);

        JSplitPane editorAndRight = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                leftWorkspace(),
                rightWorkspace()
        );
        WorkbenchTheme.styleSplit(editorAndRight);
        editorAndRight.setResizeWeight(0.56);
        editorAndRight.setDividerLocation(820);

        JSplitPane pickerAndGlue = new JSplitPane(JSplitPane.VERTICAL_SPLIT, picker, javaInspectorPanel());
        WorkbenchTheme.styleSplit(pickerAndGlue);
        pickerAndGlue.setResizeWeight(0.62);
        pickerAndGlue.setDividerLocation(420);

        JSplitPane withPicker = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                pickerAndGlue,
                editorAndRight
        );
        WorkbenchTheme.styleSplit(withPicker);
        withPicker.setResizeWeight(0.18);
        withPicker.setDividerLocation(280);
        pickerSplit = withPicker;
        root.add(withPicker, BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        setContentPane(root);

        configureScenarioEditor();
        configureStepEditor();
        configureMappingEditor();
        configurePicker();
        wirePlayerActions();
        wireSessionActions();
        syncScenarioView();
        updatePlayerView(null);
        terminal.start();

        configureAgentChrome();
        controller.addLeaseListener(snapshot -> SwingUtilities.invokeLater(() -> applyLease(snapshot)));
        controller.addPlayerListener(() -> SwingUtilities.invokeLater(() -> {
            syncScenarioView();
            updatePlayerView(null);
        }));
        applyLease(controller.controlLease());
        controller.setUiGoHandler(link -> SwingUtilities.invokeLater(() ->
                applyGoResult(new WorkbenchGoResolver(controller.projectRoot()).resolve(link), link)));

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeWorkbench();
            }
        });

        runStateAction("Loading project status", controller::refresh);
    }

    private JMenuBar menuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(event -> closeWorkbench());
        file.add(exit);
        bar.add(file);

        JMenu session = new JMenu("Session");
        session.add(syncItem);
        session.add(refreshItem);
        session.addSeparator();
        session.add(startItem);
        session.add(restartItem);
        session.add(stopItem);
        bar.add(session);

        JMenu tools = new JMenu("Tools");
        JMenuItem advanced = new JMenuItem("Advanced Controls...");
        advanced.addActionListener(event -> showAdvancedControls());
        tools.add(advanced);
        bar.add(tools);

        return bar;
    }

    private JComponent topChrome() {
        JPanel north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(agentBanner);
        north.add(Box.createVerticalStrut(6));
        north.add(permissionBar);
        north.add(Box.createVerticalStrut(6));
        north.add(playerBar());
        return north;
    }

    private void configureAgentChrome() {
        agentBanner.setBackground(new Color(0xFE, 0xF3, 0xC7));
        agentBanner.setBorder(WorkbenchTheme.cardBorder());
        agentBannerLabel.setForeground(WorkbenchTheme.TEXT);
        agentBannerLabel.setFont(agentBannerLabel.getFont().deriveFont(Font.BOLD, 13f));
        takeControlButton.addActionListener(event -> {
            controller.takeControl();
            applyLease(controller.controlLease());
            updatePlayerView("You took control of Workbench.");
        });
        agentBanner.add(agentBannerLabel, BorderLayout.CENTER);
        agentBanner.add(takeControlButton, BorderLayout.EAST);
        agentBanner.setVisible(false);

        permissionBar.setBackground(new Color(0xDB, 0xEA, 0xFE));
        permissionBar.setBorder(WorkbenchTheme.cardBorder());
        permissionLabel.setForeground(WorkbenchTheme.TEXT);
        JPanel permissionButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        permissionButtons.setOpaque(false);
        allowButton.addActionListener(event -> answerPermission(true));
        denyButton.addActionListener(event -> answerPermission(false));
        permissionButtons.add(allowButton);
        permissionButtons.add(denyButton);
        permissionBar.add(permissionLabel, BorderLayout.CENTER);
        permissionBar.add(permissionButtons, BorderLayout.EAST);
        permissionBar.setVisible(false);
    }

    private void answerPermission(boolean allow) {
        if (pendingPermissionId == null) return;
        String id = pendingPermissionId;
        pendingPermissionId = null;
        controller.answerPermission(id, allow);
        applyLease(controller.controlLease());
        updatePlayerView(allow
                ? "Allowed the agent Save request."
                : "Denied the agent Save request. The original feature file was not changed.");
    }

    private JPanel playerBar() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBackground(WorkbenchTheme.SURFACE);
        bar.setBorder(WorkbenchTheme.cardBorder());

        JPanel project = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        project.setOpaque(false);
        pickerToggle.setSelected(true);
        pickerToggle.setFocusable(false);
        pickerToggle.addActionListener(event -> togglePicker());
        project.add(pickerToggle);
        project.add(projectLabel);
        versionLabel.setToolTipText("Pickleball version for this Workbench");
        versionLabel.setForeground(WorkbenchTheme.TEXT);
        project.add(versionLabel);
        project.add(readinessLabel);
        bar.add(project, BorderLayout.WEST);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        controls.setOpaque(false);
        controls.add(playButton);
        controls.add(pauseButton);
        controls.add(playerStopButton);
        bar.add(controls, BorderLayout.CENTER);

        JPanel state = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        state.setOpaque(false);
        state.add(WorkbenchTheme.muted("Player"));
        playerStatusLabel.setFont(playerStatusLabel.getFont().deriveFont(Font.BOLD, 13f));
        state.add(playerStatusLabel);
        bar.add(state, BorderLayout.EAST);
        return bar;
    }

    private JComponent leftWorkspace() {
        JPanel left = new JPanel(new BorderLayout(0, 8));
        left.add(scenarioPanel(), BorderLayout.CENTER);
        left.add(stepPanel(), BorderLayout.SOUTH);
        return left;
    }

    private JComponent scenarioPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(WorkbenchTheme.SURFACE);
        panel.setBorder(WorkbenchTheme.cardBorder());

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.add(WorkbenchTheme.heading("Live Gherkin"), BorderLayout.WEST);
        editorTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        editorTabs.addChangeListener(event -> {
            if (rebuildingTabs) return;
            int index = editorTabs.getSelectedIndex();
            if (index < 0 || index == shownTabIndex) return;
            if (shownTabIndex >= 0 && shownTabIndex < editorSessions.size()) {
                EditorTabState current = editorSessions.get(shownTabIndex);
                if (!current.peek()) {
                    current.setDocumentText(player.documentText());
                }
            }
            showEditorTab(index);
        });
        rebuildTabStrip();
        JPanel north = new JPanel(new BorderLayout(0, 4));
        north.setOpaque(false);
        north.add(header, BorderLayout.NORTH);
        north.add(editorTabs, BorderLayout.SOUTH);
        panel.add(north, BorderLayout.NORTH);

        scenarioEditor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        scenarioEditor.setLineWrap(false);
        scenarioEditor.setTabSize(2);
        WorkbenchTheme.styleEditor(scenarioEditor);
        JScrollPane editorScroll = new JScrollPane(scenarioEditor);
        WorkbenchTheme.styleScroll(editorScroll);
        panel.add(editorScroll, BorderLayout.CENTER);

        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 2));
        legend.setOpaque(false);
        legend.add(WorkbenchTheme.muted("Tab = nested :    Shift-Tab = outdent    keyword autocomplete on Given/When/Then"));
        legend.add(WorkbenchTheme.muted("Play uses Background + selected Examples row"));
        legend.add(WorkbenchTheme.muted("Ctrl+click a RUN step to open its target"));
        panel.add(legend, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent stepPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 5));
        panel.setBackground(WorkbenchTheme.SURFACE);
        panel.setBorder(WorkbenchTheme.cardBorder());

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        header.setOpaque(false);
        header.add(WorkbenchTheme.heading("Step Editor"));
        header.add(stepOnlyButton);
        header.add(fromHereButton);
        header.add(openTargetButton);
        header.add(WorkbenchTheme.muted("Enter = append/insert    Ctrl+Enter = update selected line"));
        panel.add(header, BorderLayout.NORTH);

        stepText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        WorkbenchTheme.styleEditor(stepText);
        panel.add(stepText, BorderLayout.CENTER);
        return panel;
    }

    private JComponent rightWorkspace() {
        rightTabs = new JTabbedPane();
        rightTabs.addTab("Mapping", mappingPanel());
        rightTabs.addTab("Terminal", terminal);
        rightTabs.addTab("Explorer", diagnosticsPanel());
        rightTabs.addTab("Report", reportPanel());
        rightTabs.addChangeListener(event -> {
            String title = rightTabs.getTitleAt(rightTabs.getSelectedIndex());
            if ("Explorer".equals(title) || "Diagnostic Log Explorer".equals(title)) {
                refreshDiagnostics();
            } else if ("Report".equals(title)) {
                refreshReport();
            }
        });
        return rightTabs;
    }

    /**
     * Mapping deliberately has no get/put/resolve workflow. The selected current
     * NodeMap is represented as one editable JSON object snapshot and valid edits
     * are restored automatically after a short debounce.
     */
    private JPanel mappingPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBackground(WorkbenchTheme.SURFACE);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel selector = new JPanel(new BorderLayout(6, 0));
        selector.setOpaque(false);
        selector.add(WorkbenchTheme.muted("NodeMap"), BorderLayout.WEST);
        nodeMapSelector.setEnabled(false);
        selector.add(nodeMapSelector, BorderLayout.CENTER);
        panel.add(selector, BorderLayout.NORTH);

        mappingEditor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        mappingEditor.setTabSize(2);
        mappingEditor.setLineWrap(false);
        mappingEditor.setEnabled(false);
        WorkbenchTheme.styleEditor(mappingEditor);
        JScrollPane mappingScroll = new JScrollPane(mappingEditor);
        WorkbenchTheme.styleScroll(mappingScroll);
        panel.add(mappingScroll, BorderLayout.CENTER);

        mappingStatus.setBorder(new EmptyBorder(2, 2, 2, 2));
        mappingStatus.setForeground(WorkbenchTheme.MUTED);
        panel.add(mappingStatus, BorderLayout.SOUTH);
        if (mappingView != null) {
            JPanel wrap = new JPanel(new BorderLayout());
            wrap.add(mappingView, BorderLayout.CENTER);
            wrap.add(mappingStatus, BorderLayout.SOUTH);
            return wrap;
        }
        return panel;
    }

    private JPanel diagnosticsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(WorkbenchTheme.SURFACE);
        if (diagnosticView != null) {
            panel.add(diagnosticView, BorderLayout.CENTER);
            return panel;
        }
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));
        JTextArea message = outputArea();
        message.setText("""
                Diagnostic Log Explorer

                Replay a retained Pickleball run, one scenario step at a time.
                JavaFX WebView is unavailable in this process, so the timeline
                UI cannot open. Workbench still reads retained artifacts from
                reports/diagnostic-runs and does not invent a second store.
                """);
        message.setCaretPosition(0);
        panel.add(new JScrollPane(message), BorderLayout.CENTER);
        return panel;
    }

    private JPanel reportPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBackground(WorkbenchTheme.SURFACE);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        reportView.setContentType("text/html");
        reportView.setEditable(false);
        reportView.addHyperlinkListener(event -> {
            if (event.getEventType() != javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) return;
            String spec = event.getDescription() == null ? "" : event.getDescription();
            if (spec.startsWith("wb://")) goFromExplorer(spec);
        });
        reportPicker.addActionListener(event -> showSelectedReport());
        JButton refresh = WorkbenchTheme.flatButton("Refresh", "Reload investigation reports");
        refresh.addActionListener(event -> refreshReport());
        JPanel north = new JPanel(new BorderLayout(8, 0));
        north.setOpaque(false);
        north.add(reportPicker, BorderLayout.CENTER);
        north.add(refresh, BorderLayout.EAST);
        panel.add(north, BorderLayout.NORTH);
        panel.add(new JScrollPane(reportView), BorderLayout.CENTER);
        return panel;
    }

    private void refreshReport() {
        reportFiles.clear();
        reportPicker.removeAllItems();
        Path investigations = PickleballLocalLayout.investigationsDirectory(controller.projectRoot());
        if (Files.isDirectory(investigations)) {
            try (var directories = Files.list(investigations)) {
                directories.filter(Files::isDirectory).sorted().forEach(directory -> {
                    Path html = directory.resolve("report.html");
                    if (Files.isRegularFile(html)) {
                        String id = directory.getFileName().toString();
                        reportFiles.put(id, html);
                        reportPicker.addItem(id);
                    }
                });
            } catch (Exception ignored) {
            }
        }
        if (reportFiles.isEmpty()) {
            reportView.setText("<html><body><p>No investigation reports under .pickleball/investigations.</p></body></html>");
            return;
        }
        showSelectedReport();
    }

    private void showSelectedReport() {
        Object selected = reportPicker.getSelectedItem();
        if (selected == null) return;
        Path html = reportFiles.get(selected.toString());
        if (html == null || !Files.isRegularFile(html)) return;
        try {
            reportView.setText(Files.readString(html));
            reportView.setCaretPosition(0);
        } catch (Exception failure) {
            reportView.setText("<html><body><p>Could not read report.</p></body></html>");
        }
    }

    private JPanel footer() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        activityLabel.setForeground(WorkbenchTheme.MUTED);
        activityLabel.setBorder(new EmptyBorder(4, 4, 0, 4));
        footer.add(activityLabel, BorderLayout.CENTER);
        footer.add(webViewNote, BorderLayout.EAST);
        return footer;
    }

    private void configureScenarioEditor() {
        keywordList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        keywordList.setVisibleRowCount(8);
        keywordList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        keywordList.setFocusable(false);
        keywordList.setBackground(WorkbenchTheme.SURFACE);
        keywordList.setForeground(WorkbenchTheme.TEXT);
        keywordList.setSelectionBackground(WorkbenchTheme.ACCENT_SOFT);
        keywordList.setSelectionForeground(WorkbenchTheme.TEXT);
        keywordPopup.setFocusable(false);
        keywordPopup.setBorder(WorkbenchTheme.hairline());
        keywordPopup.add(new JScrollPane(keywordList));
        keywordList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() >= 1) {
                    acceptKeywordCompletion();
                }
            }
        });
        scenarioEditor.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, java.util.Set.of());
        scenarioEditor.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, java.util.Set.of());
        scenarioEditor.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "gherkin-tab");
        scenarioEditor.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK), "gherkin-shift-tab");
        scenarioEditor.getActionMap().put("gherkin-tab", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!humanControlsLocked()) handleEditorTab(false);
            }
        });
        scenarioEditor.getActionMap().put("gherkin-shift-tab", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!humanControlsLocked()) handleEditorTab(true);
            }
        });
        scenarioEditor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                scenarioDocumentChanged();
                SwingUtilities.invokeLater(() -> refreshKeywordCompletions());
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                scenarioDocumentChanged();
                SwingUtilities.invokeLater(() -> refreshKeywordCompletions());
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                scenarioDocumentChanged();
            }
        });
        scenarioEditor.addCaretListener(event -> {
            seekPlayheadToCaret();
            resolveSelectedStep();
        });
        scenarioEditor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (humanControlsLocked() || !keywordPopup.isVisible()) return;
                if (event.getKeyCode() == KeyEvent.VK_ENTER) {
                    acceptKeywordCompletion();
                    event.consume();
                } else if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    hideKeywordCompletions();
                    event.consume();
                } else if (event.getKeyCode() == KeyEvent.VK_DOWN) {
                    moveKeywordSelection(1);
                    event.consume();
                } else if (event.getKeyCode() == KeyEvent.VK_UP) {
                    moveKeywordSelection(-1);
                    event.consume();
                }
            }
        });
        scenarioEditor.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                seekPlayheadToCaret();
                if (event.isControlDown() || event.isMetaDown()) {
                    openSelectedTarget();
                }
                resolveSelectedStep();
            }
        });
    }

    private void configureStepEditor() {
        openTargetButton.addActionListener(event -> openSelectedTarget());
        stepText.addActionListener(event -> insertStep());
        stepText.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK),
                "update-selected-step"
        );
        stepText.getActionMap().put("update-selected-step", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                updateSelectedStep();
            }
        });
    }

    void installInteractiveViews() {
        configureWebViews();
        if (mappingView != null && rightTabs != null) {
            int mapping = rightTabs.indexOfTab("Mapping");
            if (mapping >= 0) {
                JPanel wrap = new JPanel(new BorderLayout());
                wrap.add(mappingView, BorderLayout.CENTER);
                wrap.add(mappingStatus, BorderLayout.SOUTH);
                rightTabs.setComponentAt(mapping, wrap);
            }
        }
        if (diagnosticView != null && rightTabs != null) {
            int diagnostic = rightTabs.indexOfTab("Explorer");
            if (diagnostic < 0) diagnostic = rightTabs.indexOfTab("Diagnostic Log Explorer");
            if (diagnostic >= 0) {
                JPanel wrap = new JPanel(new BorderLayout());
                wrap.add(diagnosticView, BorderLayout.CENTER);
                rightTabs.setComponentAt(diagnostic, wrap);
            }
        }
    }

    private void configureWebViews() {
        if (mappingView != null || diagnosticView != null) {
            return;
        }
        if (!JavaFxSupport.available()) {
            webViewNote.setText("JavaFX WebView unavailable: " + JavaFxSupport.failure()
                    + ". Mapping and Diagnostic explorer use the text fallback. OpenJFX is Workbench-only.");
            return;
        }
        try {
            mappingHost.onSelect(reference -> {
                for (WorkbenchUiController.MappingCatalogEntry entry : mappingEntries) {
                    if (entry.reference().equals(reference)) {
                        nodeMapSelector.setSelectedItem(entry);
                        loadMapping(entry);
                        return;
                    }
                }
            });
            mappingHost.onEdit(this::applyMappingPropertyEdit);
            mappingHost.onReady(this::pushMappingView);
            mappingView = new WebViewPanel(
                    "/tools/dscode/workbench/ui/web/mapping-editor.html",
                    "mappingHost",
                    mappingHost
            );

            diagnosticHost.onSelectRun(this::showDiagnosticRun);
            diagnosticHost.onGo(this::goFromExplorer);
            diagnosticHost.onReady(this::refreshDiagnostics);
            diagnosticView = new WebViewPanel(
                    "/tools/dscode/workbench/ui/web/diagnostic-explorer.html",
                    "diagnosticHost",
                    diagnosticHost
            );
        } catch (RuntimeException failure) {
            mappingView = null;
            diagnosticView = null;
            webViewNote.setText("JavaFX WebView failed to start: " + failure.getMessage()
                    + ". Mapping and Diagnostic explorer use the text fallback.");
        }
    }

    private void configurePicker() {
        picker.onScenarioSelected(scenario -> {
            flushActiveEditorTab();
            List<String> fileLines = readFeatureFile(scenario.file(), scenario.lines());
            controller.loadPickerScenario(
                    fileLines,
                    scenario.file(),
                    scenario.name(),
                    scenario.startLine(),
                    scenario.endLine(),
                    scenario.exampleRow(),
                    scenario.exampleLabel()
            );
            picker.setSaveEnabled(true);
            openEditorTab(
                    scenario.file(),
                    scenario.displayLabel(),
                    scenario.name(),
                    scenario.startLine(),
                    scenario.endLine(),
                    scenario.exampleRow(),
                    scenario.exampleLabel(),
                    player.documentText(),
                    true
            );
            syncScenarioView();
            updatePlayerView("Loaded " + scenario.displayLabel() + ".");
            resolveSelectedStep();
        });
        picker.onSave(this::saveLoadedFeature);
    }

    private void refreshFeatureCatalog() {
        WorkbenchManifest manifest = null;
        try {
            manifest = WorkbenchManifest.read(controller.projectRoot());
        } catch (RuntimeException ignored) {
            // The picker can still scan conventional project feature folders.
        }
        picker.setCatalog(ConsumerFeatureCatalog.scan(controller.projectRoot(), manifest));
        diagnosticNavigator = new DiagnosticEvidenceNavigator(controller.projectRoot());
        glueIndex = JavaGlueIndex.scan(controller.projectRoot());
    }

    private void togglePicker() {
        if (!(pickerSplit instanceof JSplitPane split)) return;
        if (pickerToggle.isSelected()) {
            split.setDividerLocation(280);
            picker.setVisible(true);
        } else {
            split.setDividerLocation(0);
            picker.setVisible(false);
        }
        split.revalidate();
    }


    private void handleEditorTab(boolean shift) {
        CurrentLine current = currentEditorLine();
        if (current == null) return;
        boolean completionOpen = keywordPopup.isVisible() && keywordList.getSelectedValue() != null;
        String selected = keywordList.getSelectedValue();
        GherkinTextEditing.LineCaret edited;
        if (shift) {
            edited = GherkinTextEditing.outdent(current.text(), current.column());
            hideKeywordCompletions();
        } else {
            edited = GherkinTextEditing.tab(current.text(), current.column(), completionOpen, selected);
            if (completionOpen) {
                hideKeywordCompletions();
            }
        }
        applyLineCaret(current.lineIndex(), edited);
    }

    private void refreshKeywordCompletions() {
        if (syncingScenarioDocument || humanControlsLocked() || !scenarioEditor.isEditable()) {
            hideKeywordCompletions();
            return;
        }
        CurrentLine current = currentEditorLine();
        if (current == null) {
            hideKeywordCompletions();
            return;
        }
        java.util.List<String> matches = GherkinTextEditing.completions(current.text(), current.column());
        if (matches.isEmpty()) {
            hideKeywordCompletions();
            return;
        }
        keywordList.setListData(matches.toArray(String[]::new));
        keywordList.setSelectedIndex(0);
        try {
            var view = scenarioEditor.modelToView2D(scenarioEditor.getCaretPosition());
            if (view == null) {
                hideKeywordCompletions();
                return;
            }
            java.awt.Rectangle caret = view.getBounds();
            keywordPopup.show(scenarioEditor, caret.x, caret.y + caret.height);
        } catch (RuntimeException | BadLocationException ignored) {
            hideKeywordCompletions();
        }
    }

    private void acceptKeywordCompletion() {
        String selected = keywordList.getSelectedValue();
        CurrentLine current = currentEditorLine();
        if (selected == null || current == null) {
            hideKeywordCompletions();
            return;
        }
        applyLineCaret(current.lineIndex(), GherkinTextEditing.acceptCompletion(
                current.text(), current.column(), selected));
        hideKeywordCompletions();
    }

    private void hideKeywordCompletions() {
        keywordPopup.setVisible(false);
    }

    private void moveKeywordSelection(int delta) {
        int size = keywordList.getModel().getSize();
        if (size <= 0) return;
        int next = Math.max(0, Math.min(size - 1, keywordList.getSelectedIndex() + delta));
        keywordList.setSelectedIndex(next);
        keywordList.ensureIndexIsVisible(next);
    }

    private void applyLineCaret(int lineIndex, GherkinTextEditing.LineCaret edited) {
        try {
            int start = scenarioEditor.getLineStartOffset(lineIndex);
            int end = scenarioEditor.getLineEndOffset(lineIndex);
            if (end > start && "\n".equals(scenarioEditor.getText(end - 1, 1))) {
                end--;
            }
            scenarioEditor.replaceRange(edited.line(), start, end);
            scenarioEditor.setCaretPosition(start + edited.caretColumn());
        } catch (BadLocationException ignored) {
            // Document can briefly lag the caret during a rebuild.
        }
    }

    private CurrentLine currentEditorLine() {
        try {
            int caret = scenarioEditor.getCaretPosition();
            int lineIndex = scenarioEditor.getLineOfOffset(caret);
            int start = scenarioEditor.getLineStartOffset(lineIndex);
            int end = scenarioEditor.getLineEndOffset(lineIndex);
            String text = scenarioEditor.getText(start, end - start);
            if (text.endsWith("\n")) {
                text = text.substring(0, text.length() - 1);
            }
            return new CurrentLine(lineIndex, text, caret - start);
        } catch (BadLocationException ignored) {
            return null;
        }
    }

    private record CurrentLine(int lineIndex, String text, int column) { }

    private void pushMappingView() {
        if (mappingView == null) return;
        WorkbenchUiController.MappingCatalogEntry selected =
                (WorkbenchUiController.MappingCatalogEntry) nodeMapSelector.getSelectedItem();
        mappingView.evalJsonCall(
                "window.setMappingState",
                WorkbenchWebJson.mappingState(
                        mappingEntries.stream()
                                .map(entry -> new WorkbenchWebJson.MapChoice(
                                        entry.reference(), entry.label(), entry.restorable(), entry.pending()))
                                .toList(),
                        selected == null ? null : new WorkbenchWebJson.MapChoice(
                                selected.reference(), selected.label(), selected.restorable(), selected.pending()),
                        mappingModel,
                        mappingStatus.getText(),
                        humanControlsLocked()
                )
        );
    }

    private void applyMappingPropertyEdit(MappingEditorHost.PropertyEdit edit) {
        if (humanControlsLocked()) return;
        if (lastState == null || !lastState.liveReady() || loadedMapping == null) return;
        try {
            if (edit.key() == null || edit.key().isBlank()) {
                if (mappingModel == null || edit.oldKey() == null || edit.oldKey().isBlank()) return;
                MappingTreeModel updated = mappingModel.remove(edit.oldKey());
                runTask(
                        () -> controller.restoreMapping(loadedMapping, updated.values()),
                        output -> {
                            mappingModel = updated;
                            mappingStatus.setText("Removed " + edit.oldKey() + ".");
                            pushMappingView();
                        },
                        failure -> showFailure("Mapping remove failed", failure)
                );
                return;
            }
            if (edit.oldKey() == null || edit.oldKey().isBlank() || edit.oldKey().equals(edit.key())) {
                runTask(
                        () -> controller.mappingPutTyped(edit.mapReference(), edit.key(), edit.type(), edit.text()),
                        result -> {
                            terminal.appendExecution("[Mapping] " + edit.key(), result.output(), result.events());
                            mappingStatus.setText("Saved " + edit.key() + " through mappingPut.");
                            if (mappingModel != null) {
                                mappingModel = mappingModel.upsert(
                                        edit.key(),
                                        MappingValueCodec.parseType(edit.type()),
                                        edit.text()
                                );
                            }
                            pushMappingView();
                        },
                        failure -> showFailure("Mapping property edit failed", failure)
                );
                return;
            }
            MappingTreeModel updated = (mappingModel == null
                    ? new MappingTreeModel(edit.mapReference(), loadedMapping.mapType(), true, Map.of())
                    : mappingModel)
                    .rename(edit.oldKey(), edit.key())
                    .upsert(edit.key(), MappingValueCodec.parseType(edit.type()), edit.text());
            runTask(
                    () -> controller.restoreMapping(loadedMapping, updated.values()),
                    output -> {
                        mappingModel = updated;
                        terminal.appendExecution("[Mapping] " + loadedMapping.mapType(), output, "");
                        mappingStatus.setText("Renamed property saved through mappingRestore.");
                        pushMappingView();
                    },
                    failure -> showFailure("Mapping restore failed", failure)
            );
        } catch (RuntimeException failure) {
            mappingStatus.setText(failure.getMessage());
        }
    }

    private void saveLoadedFeature() {
        if (humanControlsLocked()) return;
        WorkbenchSavePreview preview = controller.savePreview();
        if (!preview.savable()) {
            showFailure("Could not save", new IllegalStateException(preview.summary()));
            return;
        }
        int choice = JOptionPane.showConfirmDialog(
                this,
                preview.summary() + "\n\nWorkbench will not write the original feature file unless you confirm.",
                "Save live scenario",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        if (choice != JOptionPane.OK_OPTION) {
            updatePlayerView("Save cancelled. The original feature file was not changed.");
            return;
        }
        try {
            WorkbenchSaveResult result = controller.commitSave();
            if (result.written()) {
                updatePlayerView(result.message());
            } else {
                showFailure("Could not save", new IllegalStateException(result.message()));
            }
        } catch (RuntimeException failure) {
            showFailure("Could not save feature file", failure);
        }
    }

    private void refreshDiagnostics() {
        if (diagnosticView == null) return;
        if (diagnosticNavigator == null) {
            diagnosticNavigator = new DiagnosticEvidenceNavigator(controller.projectRoot());
        }
        List<DiagnosticEvidenceNavigator.CatalogRun> runs = diagnosticNavigator.catalogRuns();
        if (runs.isEmpty()) {
            diagnosticView.evalJsonCall("window.setDiagnosticState", WorkbenchWebJson.write(Map.of(
                    "runs", List.of(),
                    "frames", List.of(),
                    "layers", List.of(),
                    "gap", diagnosticNavigator.available()
                            ? "The catalog has no retained runs."
                            : "No reports/diagnostic-runs/run-catalog.json in this consumer project."
            )));
            return;
        }
        showDiagnosticRun(runs.getFirst().runId());
    }

    private void showDiagnosticRun(String runId) {
        if (diagnosticView == null || diagnosticNavigator == null) return;
        DiagnosticEvidenceNavigator.CatalogRun selected = diagnosticNavigator.catalogRuns().stream()
                .filter(run -> run.runId().equals(runId))
                .findFirst()
                .orElse(null);
        if (selected == null) return;
        currentDiagnosticRunId = runId;
        DiagnosticEvidenceNavigator.ReplayModel replay = diagnosticNavigator.replayModel(selected.runRoot());
        List<Map<String, Object>> beats = new ArrayList<>();
        for (DiagnosticEvidenceNavigator.ReplayBeat beat : replay.beats()) {
            Map<String, Object> item = diagnosticNavigator.beatMap(beat);
            if (beat.screenshot() != null && Files.isRegularFile(beat.screenshot())) {
                item.put("hasScreenshot", true);
                try {
                    byte[] bytes = Files.readAllBytes(beat.screenshot());
                    item.put("dataUri", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes));
                } catch (Exception ignored) {
                    // Screenshot bytes are optional; the step/log still replay.
                }
            } else {
                item.put("hasScreenshot", false);
            }
            beats.add(item);
        }
        List<Map<String, Object>> tree = diagnosticNavigator.treeMaps(replay.roots(), replay.beats());
        String scenarioId = beats.isEmpty() ? "" : String.valueOf(beats.getFirst().get("scenarioId"));
        List<Map<String, Object>> layers = new ArrayList<>();
        for (var layer : diagnosticNavigator.layers(selected.runRoot(), scenarioId)) {
            layers.add(Map.of(
                    "layer", layer.layer().name(),
                    "present", layer.present(),
                    "excerpt", layer.excerpt() == null ? "" : layer.excerpt()
            ));
        }
        List<Map<String, Object>> runs = new ArrayList<>();
        for (var run : diagnosticNavigator.catalogRuns()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("runId", run.runId());
            row.put("label", run.displayLabel());
            row.put("outcome", run.outcome());
            row.put("selected", run.runId().equals(runId));
            runs.add(row);
        }
        diagnosticView.evalJsonCall("window.setDiagnosticState", WorkbenchWebJson.write(Map.of(
                "runs", runs,
                "beats", beats,
                "tree", tree,
                "frames", beats,
                "index", 0,
                "gap", beats.isEmpty()
                        ? "This retained run has no events.jsonl steps or PNG frames."
                        : ""
        )));
    }

    private void goFromExplorer(String rawLink) {
        WorkbenchGoLink link;
        try {
            if (rawLink != null && rawLink.trim().startsWith("{")) {
                link = WorkbenchGoLink.fromMap(json.readValue(rawLink, new TypeReference<Map<String, Object>>() { }));
            } else {
                link = WorkbenchGoLink.parse(rawLink);
            }
        } catch (Exception failure) {
            updatePlayerView("Could not parse explorer Open target.");
            return;
        }
        if (link.runId().isBlank() && !currentDiagnosticRunId.isBlank()) {
            link = WorkbenchGoLink.fromMap(mergeRun(link.toMap(), currentDiagnosticRunId));
        }
        WorkbenchGoResolver.WorkbenchGoResult target =
                new WorkbenchGoResolver(controller.projectRoot()).resolve(link);
        applyGoResult(target, link);
    }

    private static Map<String, Object> mergeRun(Map<String, Object> link, String runId) {
        Map<String, Object> next = new LinkedHashMap<>(link);
        next.put("runId", runId);
        return next;
    }

    private void applyGoResult(WorkbenchGoResolver.WorkbenchGoResult target, WorkbenchGoLink link) {
        if (target.outsideProject() || target.missing()) {
            updatePlayerView(target.message());
            if ("java".equalsIgnoreCase(link.kind())) {
                javaInspector.setText((link.kind() + "\n" + link.path() + "\n\n" + target.message()).strip());
            }
            return;
        }
        if (!"editor".equals(target.to())) {
            updatePlayerView(target.message());
            return;
        }
        if ("java".equalsIgnoreCase(link.kind()) || "java".equalsIgnoreCase(target.kind())) {
            javaInspector.setText(
                    "CONSUMER_GLUE\n"
                            + (target.relativePath() == null ? link.path() : target.relativePath())
                            + (target.file() == null ? "" : ("\n" + target.file()))
            );
        }
        if (target.file() == null || !Files.isRegularFile(target.file())) {
            updatePlayerView(target.message());
            return;
        }
        String live = player.documentText();
        try {
            String text = Files.readString(target.file());
            openPeekTab(target.file(), target.label(), text, true);
            updatePlayerView(target.message());
            if (!Objects.equals(live, player.documentText())) {
                player.replaceDocument(List.of(live.split("\n", -1)));
            }
        } catch (Exception failure) {
            updatePlayerView("Could not peek " + target.relativePath() + ".");
        }
    }

    private void openPeekTab(Path file, String title, String documentText, boolean pinCaller) {
        if (shownTabIndex >= 0 && shownTabIndex < editorSessions.size()) {
            EditorTabState current = editorSessions.get(shownTabIndex);
            if (!current.peek()) current.setDocumentText(player.documentText());
        }
        if (pinCaller && pinnedCallerFile == null) {
            for (EditorTabState open : editorSessions) {
                if (!open.peek()) {
                    open.setPinned(true);
                    pinnedCallerFile = open.file();
                    break;
                }
            }
        }
        for (int i = 0; i < editorSessions.size(); i++) {
            EditorTabState existing = editorSessions.get(i);
            if (existing.peek() && existing.file() != null && file != null
                    && existing.file().toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) {
                existing.setDocumentText(documentText);
                existing.markClean();
                rebuildTabStrip();
                showEditorTab(i);
                return;
            }
        }
        EditorTabState tab = new EditorTabState(title == null || title.isBlank() ? "Peek" : title, file, documentText, false);
        tab.setPeek(true);
        tab.markClean();
        editorSessions.add(tab);
        rebuildTabStrip();
        showEditorTab(editorSessions.size() - 1);
    }

    private void configureMappingEditor() {
        nodeMapSelector.addActionListener(event -> {
            if (refreshingCatalog) return;
            WorkbenchUiController.MappingCatalogEntry selected =
                    (WorkbenchUiController.MappingCatalogEntry) nodeMapSelector.getSelectedItem();
            if (selected != null) loadMapping(selected);
        });

        mappingEditor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                mappingChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                mappingChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                mappingChanged();
            }
        });
    }

    private void wirePlayerActions() {
        playButton.addActionListener(event -> runScenarioFromBeginning());
        pauseButton.addActionListener(event -> {
            if (humanControlsLocked()) return;
            player.pause();
            updatePlayerView(playbackBusy
                    ? "Pause requested; the current step will finish first."
                    : "Scenario playback paused.");
        });
        playerStopButton.addActionListener(event -> {
            if (humanControlsLocked()) return;
            player.stop();
            pendingFreshRun = false;
            pendingFreshRunStepId = null;
            pendingIsolatedStep = null;
            updatePlayerView(playbackBusy
                    ? "Scenario playback stopped; the current step will finish but no next step will start."
                    : "Scenario playback stopped.");
        });
        stepOnlyButton.addActionListener(event -> executeStepOnly());
        fromHereButton.addActionListener(event -> runScenarioFromSelectedStep());
    }

    private void wireSessionActions() {
        syncItem.addActionListener(event ->
                runStateAction("Synchronizing project", controller::synchronize));
        refreshItem.addActionListener(event ->
                runStateAction("Refreshing status", controller::refresh));
        startItem.addActionListener(event ->
                runStateAction("Starting worker", controller::startWorker));
        restartItem.addActionListener(event ->
                runStateAction("Restarting worker", controller::restartWorker));
        stopItem.addActionListener(event -> {
            player.stop();
            runStateAction("Stopping worker", controller::stopWorker);
        });
    }

    private void runScenarioFromBeginning() {
        requestFreshRun(null);
    }

    private void runScenarioFromSelectedStep() {
        LiveScenarioPlayer.Line selected = player.selectedLine()
                .or(player::playheadLine)
                .orElse(null);
        if (selected == null || !selected.executable()) {
            showFailure("Could not run from selected step",
                    new IllegalStateException("Select an executable scenario step first."));
            return;
        }
        requestFreshRun(selected.id());
    }

    private void requestFreshRun(Long startStepId) {
        if (humanControlsLocked()) return;
        pendingIsolatedStep = null;
        if (playbackBusy || playbackPreparing) {
            pendingFreshRun = true;
            pendingFreshRunStepId = startStepId;
            player.stop();
            updatePlayerView(startStepId == null
                    ? "Run from start queued after the current operation."
                    : "Run from selected step queued after the current operation.");
            return;
        }
        startFreshRunNow(startStepId);
    }

    private void startFreshRunNow(Long startStepId) {
        try {
            if (startStepId == null) {
                player.startFromBeginning();
                updatePlayerView("Starting a fresh scenario run from the first step...");
            } else {
                player.clickLine(startStepId);
                player.startFromSelectedStep();
                showLine(startStepId);
                updatePlayerView("Starting a fresh scenario run from the selected step...");
            }
        } catch (RuntimeException failure) {
            showFailure("Could not start scenario playback", failure);
            return;
        }
        prepareFreshLiveSession(this::schedulePlaybackStep);
    }

    private void prepareFreshLiveSession(Runnable readyAction) {
        if (playbackPreparing) return;
        playbackPreparing = true;
        activityLabel.setText("Preparing fresh scenario context...");
        runTask(
                controller::prepareFreshLiveSession,
                state -> {
                    playbackPreparing = false;
                    applyState(state);
                    if (runPendingFreshRun()) return;
                    if (readyAction != null) readyAction.run();
                    runPendingIsolatedStep();
                },
                failure -> {
                    playbackPreparing = false;
                    player.pause();
                    updatePlayerView("Fresh scenario preparation failed.");
                    showFailure("Could not prepare fresh live session", failure);
                }
        );
    }

    private void prepareLiveSession(Runnable readyAction) {
        if (playbackPreparing) return;
        playbackPreparing = true;
        activityLabel.setText("Preparing live session...");
        runTask(
                controller::prepareLiveSession,
                state -> {
                    playbackPreparing = false;
                    applyState(state);
                    if (runPendingFreshRun()) return;
                    if (readyAction != null) {
                        readyAction.run();
                    } else {
                        refreshMappingCatalog();
                    }
                    runPendingIsolatedStep();
                },
                failure -> {
                    playbackPreparing = false;
                    player.pause();
                    updatePlayerView("Live session preparation failed.");
                    showFailure("Could not prepare live session", failure);
                }
        );
    }

    /** Executes one scenario line per background task so pause/stop remain responsive. */
    private void schedulePlaybackStep() {
        if (playbackPreparing || playbackBusy || mappingSaveBusy || refreshingCatalog) return;
        if (player.state() != LiveScenarioPlayer.State.RUNNING) {
            updatePlayerView(null);
            return;
        }

        GherkinPlayPlan.Step planned = playback.nextPlanStep().orElse(null);
        if (planned == null) {
            updatePlayerView("Scenario is waiting for another step.");
            return;
        }

        playbackBusy = true;
        executingStepId = planned.sourceLineId();
        updatePlayerView("Executing: " + planned.executeText());
        runTask(
                () -> controller.executePlayerStep(planned.executeText()),
                result -> {
                    playbackBusy = false;
                    executingStepId = null;
                    appendTerminal(planned.executeText(), result.output(), result.events());

                    // executeStep already advanced or paused the playhead while RUNNING.
                    // Do not remake that mark here; a leftover mark of the captured id
                    // used to abort automatic playback after the first successful step.
                    if (!result.successful()) {
                        player.clickLine(planned.sourceLineId());
                        showLine(planned.sourceLineId());
                    }
                    syncScenarioView();
                    updatePlayerView(
                            result.successful()
                                    ? null
                                    : "Step failed. Scenario playback paused on the failed step."
                    );
                    if (runPendingFreshRun()) return;
                    if (player.state() != LiveScenarioPlayer.State.RUNNING) {
                        refreshMappingCatalog();
                    }
                    if (!runPendingIsolatedStep()) {
                        schedulePlaybackStep();
                    }
                },
                failure -> {
                    playbackBusy = false;
                    executingStepId = null;
                    player.markCurrentStepFailed(planned.sourceLineId());
                    player.clickLine(planned.sourceLineId());
                    syncScenarioView();
                    showLine(planned.sourceLineId());
                    updatePlayerView("Step execution failed. Scenario playback paused.");
                    showFailure("Could not execute live step", failure);
                    if (!runPendingFreshRun()) runPendingIsolatedStep();
                }
        );
    }

    private void insertStep() {
        if (humanControlsLocked()) return;
        try {
            LiveScenarioPlayer.Line inserted = player.insertStep(stepText.getText());
            stepText.setText("");
            player.clickLine(inserted.id());
            syncScenarioView();
            showLine(inserted.id());
            updatePlayerView(
                    player.state() == LiveScenarioPlayer.State.RUNNING
                            ? "Appended step and continued live playback."
                            : "Inserted step into the live scenario."
            );
            if (player.state() == LiveScenarioPlayer.State.RUNNING) {
                if (lastState == null || !lastState.liveReady()) {
                    prepareLiveSession(this::schedulePlaybackStep);
                } else {
                    schedulePlaybackStep();
                }
            }
        } catch (RuntimeException failure) {
            showFailure("Could not insert step", failure);
        }
    }

    private void updateSelectedStep() {
        if (humanControlsLocked()) return;
        try {
            LiveScenarioPlayer.Line updated = player.updateSelectedStep(stepText.getText());
            syncScenarioView();
            showLine(updated.id());
            updatePlayerView("Updated selected line in place.");
        } catch (RuntimeException failure) {
            showFailure("Could not update selected step", failure);
        }
    }

    private void executeStepOnly() {
        if (humanControlsLocked()) return;
        String text = stepText.getText();
        if (text == null || text.isBlank()) {
            showFailure("Could not execute step",
                    new IllegalArgumentException("Step Editor text must not be blank."));
            return;
        }

        player.pauseForIsolatedExecution();
        updatePlayerView("Scenario playback paused for Step Only execution.");

        if (playbackBusy || playbackPreparing) {
            pendingIsolatedStep = text;
            activityLabel.setText("Step Only execution queued after the current operation.");
            return;
        }
        executeStepOnlyNow(text);
    }

    private void executeStepOnlyNow(String text) {
        Runnable execute = () -> {
            playbackBusy = true;
            runTask(
                    () -> controller.executePlayerStep(text),
                    result -> {
                        playbackBusy = false;
                        appendTerminal("[step only] " + text, result.output(), result.events());
                        updatePlayerView("Step Only finished; automatic scenario playback remains paused.");
                        if (!runPendingFreshRun()) refreshMappingCatalog();
                    },
                    failure -> {
                        playbackBusy = false;
                        showFailure("Could not execute step", failure);
                        runPendingFreshRun();
                    }
            );
        };

        if (lastState != null && lastState.liveReady()) {
            execute.run();
        } else {
            prepareLiveSession(execute);
        }
    }

    private boolean runPendingFreshRun() {
        if (!pendingFreshRun || playbackBusy || playbackPreparing) return false;
        Long startStepId = pendingFreshRunStepId;
        pendingFreshRun = false;
        pendingFreshRunStepId = null;
        startFreshRunNow(startStepId);
        return true;
    }

    private boolean runPendingIsolatedStep() {
        if (pendingIsolatedStep == null || playbackBusy || playbackPreparing || pendingFreshRun) return false;
        String text = pendingIsolatedStep;
        pendingIsolatedStep = null;
        executeStepOnlyNow(text);
        return true;
    }

    private void mappingChanged() {
        if (humanControlsLocked()) return;
        if (loadingMapping || loadedMapping == null || !loadedMapping.restorable()) return;
        if (player.state() == LiveScenarioPlayer.State.RUNNING
                || player.state() == LiveScenarioPlayer.State.WAITING_FOR_STEP) {
            player.pause();
            updatePlayerView("Player paused for live Mapping edit.");
        }
        mappingEditGeneration++;
        mappingStatus.setText("Editing " + loadedMapping.mapType() + "...");
        mappingSaveTimer.restart();
    }

    private void refreshMappingCatalog() {
        if (refreshingCatalog || lastState == null || !lastState.liveReady()) {
            if (lastState == null || !lastState.liveReady()) {
                nodeMapSelector.setEnabled(false);
                mappingEditor.setEnabled(false);
            }
            return;
        }

        refreshingCatalog = true;
        WorkbenchUiController.MappingCatalogEntry previous =
                (WorkbenchUiController.MappingCatalogEntry) nodeMapSelector.getSelectedItem();
        String previousReference = previous == null ? null : previous.reference();

        runTask(
                controller::mappingCatalog,
                entries -> {
                    refreshingCatalog = false;
                    mappingEntries = mergePendingStepMaps(entries);
                    nodeMapSelector.removeAllItems();
                    WorkbenchUiController.MappingCatalogEntry selected = null;
                    for (WorkbenchUiController.MappingCatalogEntry entry : mappingEntries) {
                        nodeMapSelector.addItem(entry);
                        if (Objects.equals(previousReference, entry.reference())) selected = entry;
                    }
                    nodeMapSelector.setEnabled(!mappingEntries.isEmpty());
                    if (selected == null && !mappingEntries.isEmpty()) selected = mappingEntries.getFirst();
                    if (selected != null) {
                        nodeMapSelector.setSelectedItem(selected);
                        loadMapping(selected);
                    } else {
                        loadedMapping = null;
                        setMappingEditor("", false);
                        mappingStatus.setText("No NodeMaps are available in the current ParsingMap.");
                    }
                    schedulePlaybackStep();
                },
                failure -> {
                    refreshingCatalog = false;
                    nodeMapSelector.setEnabled(false);
                    mappingEditor.setEnabled(false);
                    mappingStatus.setText("Could not read current ParsingMap: " + failure.getMessage());
                    schedulePlaybackStep();
                }
        );
    }

    private List<WorkbenchUiController.MappingCatalogEntry> mergePendingStepMaps(
            List<WorkbenchUiController.MappingCatalogEntry> live
    ) {
        List<WorkbenchUiController.MappingCatalogEntry> merged = new ArrayList<>(live == null ? List.of() : live);
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (WorkbenchUiController.MappingCatalogEntry entry : merged) {
            seen.add(entry.reference());
        }
        for (GherkinPlayPlan.Step step : playback.playPlan().steps()) {
            String text = step.executeText();
            if (text == null || text.isBlank()) continue;
            String reference = tools.dscode.control.protocol.ControlProtocol.stepSeedReference(text);
            if (!seen.add(reference)) continue;
            String label = "STEP_MAP · " + abbreviateStep(text);
            merged.add(new WorkbenchUiController.MappingCatalogEntry(reference, label, true, true));
        }
        return List.copyOf(merged);
    }

    private static String abbreviateStep(String text) {
        String trimmed = text == null ? "" : text.strip();
        return trimmed.length() <= 48 ? trimmed : trimmed.substring(0, 45) + "...";
    }

    private void loadMapping(WorkbenchUiController.MappingCatalogEntry entry) {
        if (entry == null || lastState == null || !lastState.liveReady()) return;
        mappingStatus.setText("Loading " + entry.label() + "...");
        runTask(
                () -> controller.mappingSnapshot(entry.reference()),
                snapshot -> {
                    loadedMapping = snapshot;
                    mappingModel = new MappingTreeModel(
                            snapshot.mapReference(),
                            snapshot.mapType(),
                            snapshot.restorable(),
                            snapshot.values()
                    );
                    try {
                        String formatted = json.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(snapshot.values());
                        setMappingEditor(formatted, snapshot.restorable());
                        mappingStatus.setText(
                                snapshot.restorable()
                                        ? "Live NodeMap from the worker ParsingMap. Edits use mappingPut / mappingRestore."
                                        : "Inspection only: this NodeMap implementation is not safely restorable."
                        );
                        pushMappingView();
                    } catch (Exception failure) {
                        showFailure("Could not render NodeMap JSON", failure);
                    }
                },
                failure -> {
                    loadedMapping = null;
                    setMappingEditor("", false);
                    mappingStatus.setText("Could not load NodeMap: " + failure.getMessage());
                }
        );
    }

    private void saveEditedMapping() {
        if (loadedMapping == null || !loadedMapping.restorable() || mappingSaveBusy) return;
        if (playbackBusy || playbackPreparing) {
            mappingStatus.setText("Waiting for the current player operation before applying Mapping edit...");
            mappingSaveTimer.restart();
            return;
        }

        Map<String, Object> values;
        try {
            values = json.readValue(
                    mappingEditor.getText(),
                    new TypeReference<Map<String, Object>>() { }
            );
        } catch (Exception invalidJson) {
            mappingStatus.setText("Invalid JSON — edit has not been applied.");
            return;
        }

        long generation = mappingEditGeneration;
        ControlBridgeMappingSnapshot snapshot = loadedMapping;
        mappingSaveBusy = true;
        mappingStatus.setText("Applying live Mapping edit...");
        runTask(
                () -> controller.restoreMapping(snapshot, values),
                output -> {
                    mappingSaveBusy = false;
                    appendTerminal("[Mapping] " + snapshot.mapType(), output, "");
                    if (generation == mappingEditGeneration) {
                        mappingStatus.setText("Saved to live " + snapshot.mapType() + ".");
                    } else {
                        mappingSaveTimer.restart();
                    }
                    schedulePlaybackStep();
                },
                failure -> {
                    mappingSaveBusy = false;
                    mappingStatus.setText("Mapping edit was not applied: " + failure.getMessage());
                }
        );
    }

    private void setMappingEditor(String text, boolean editable) {
        loadingMapping = true;
        try {
            mappingEditor.setText(text);
            mappingEditor.setCaretPosition(0);
            mappingEditor.setEnabled(true);
            mappingEditor.setEditable(editable);
        } finally {
            loadingMapping = false;
        }
    }

    private void scenarioDocumentChanged() {
        if (syncingScenarioDocument || humanControlsLocked()) return;
        player.replaceDocument(List.of(scenarioEditor.getText().split("\n", -1)));
        playback.rebuildPlan();
        EditorTabState tab = activeEditorTab();
        if (tab != null) {
            boolean wasDirty = tab.dirty();
            tab.setDocumentText(player.documentText());
            if (tab.dirty() != wasDirty) rebuildTabStrip();
        }
        seekPlayheadToCaret();
        updateFromHereAvailability();
        refreshPlayheadHighlight();
        if (player.state() == LiveScenarioPlayer.State.RUNNING) {
            if (lastState == null || !lastState.liveReady()) {
                prepareLiveSession(this::schedulePlaybackStep);
            } else {
                schedulePlaybackStep();
            }
        }
    }

    private void seekPlayheadToCaret() {
        if (syncingScenarioDocument || humanControlsLocked()) return;
        int lineIndex = lineIndexAtCaret();
        List<LiveScenarioPlayer.Line> lines = player.lines();
        if (lineIndex < 0 || lineIndex >= lines.size()) {
            updateFromHereAvailability();
            refreshPlayheadHighlight();
            return;
        }
        LiveScenarioPlayer.Line line = lines.get(lineIndex);
        player.clickLine(line.id());
        if (line.executable()) {
            stepText.setText(line.text());
        }
        updateFromHereAvailability();
        refreshPlayheadHighlight();
    }

    private void syncScenarioView() {
        String document = player.documentText();
        if (!Objects.equals(scenarioEditor.getText(), document)) {
            syncingScenarioDocument = true;
            try {
                int caret = Math.min(scenarioEditor.getCaretPosition(), document.length());
                scenarioEditor.setText(document);
                scenarioEditor.setCaretPosition(Math.max(0, caret));
            } finally {
                syncingScenarioDocument = false;
            }
        }
        refreshPlayheadHighlight();
        updateFromHereAvailability();
    }

    private void updateFromHereAvailability() {
        fromHereButton.setEnabled(
                !humanControlsLocked()
                        && player.selectedLine()
                        .or(player::playheadLine)
                        .map(LiveScenarioPlayer.Line::executable)
                        .orElse(false)
        );
    }

    private void showLine(long id) {
        List<LiveScenarioPlayer.Line> lines = player.lines();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).id() == id) {
                try {
                    int start = scenarioEditor.getLineStartOffset(i);
                    syncingScenarioDocument = true;
                    try {
                        scenarioEditor.setCaretPosition(start);
                    } finally {
                        syncingScenarioDocument = false;
                    }
                    scenarioEditor.getCaret().setVisible(true);
                } catch (BadLocationException ignored) {
                    // The document can briefly lag the model during a rebuild.
                }
                refreshPlayheadHighlight();
                return;
            }
        }
    }

    private int lineIndexAtCaret() {
        try {
            return scenarioEditor.getLineOfOffset(scenarioEditor.getCaretPosition());
        } catch (BadLocationException ignored) {
            return -1;
        }
    }

    private void refreshPlayheadHighlight() {
        Highlighter highlighter = scenarioEditor.getHighlighter();
        highlighter.removeAllHighlights();
        Long playhead = player.playheadId().isPresent() ? player.playheadId().getAsLong() : null;
        if (playhead == null && executingStepId != null) playhead = executingStepId;
        if (playhead == null && player.state() == LiveScenarioPlayer.State.RUNNING) {
            playhead = player.nextStep().map(LiveScenarioPlayer.Line::id).orElse(null);
        }
        if (playhead == null) return;

        List<LiveScenarioPlayer.Line> lines = player.lines();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).id() != playhead) continue;
            try {
                int start = scenarioEditor.getLineStartOffset(i);
                int end = scenarioEditor.getLineEndOffset(i);
                highlighter.addHighlight(start, end, playheadPainter);
            } catch (BadLocationException ignored) {
                return;
            }
            return;
        }
    }

    private void updatePlayerView(String activity) {
        playerStatusLabel.setText(switch (player.state()) {
            case STOPPED -> "Stopped";
            case PAUSED -> "Paused";
            case RUNNING -> playbackBusy ? "Running" : "Playing";
            case WAITING_FOR_STEP -> "Waiting for step";
        });
        playerStatusLabel.setForeground(switch (player.state()) {
            case STOPPED -> WorkbenchTheme.MUTED;
            case PAUSED -> WorkbenchTheme.WARNING;
            case RUNNING -> WorkbenchTheme.SUCCESS;
            case WAITING_FOR_STEP -> WorkbenchTheme.ACCENT;
        });
        if (activity != null && !activity.isBlank()) activityLabel.setText(activity);
        syncScenarioView();
    }

    private void appendTerminal(String heading, String output, String events) {
        terminal.appendExecution(heading, output, events);
        controller.workerLogFiles().ifPresent(terminal::setFiles);
    }

    private void runStateAction(
            String label,
            Supplier<WorkbenchUiController.State> action
    ) {
        activityLabel.setText(label + "...");
        runTask(
                action,
                state -> {
                    applyState(state);
                    activityLabel.setText(label + " complete.");
                    if (state.liveReady()) {
                        refreshMappingCatalog();
                        controller.workerLogFiles().ifPresent(terminal::setFiles);
                    }
                    refreshFeatureCatalog();
                },
                failure -> showFailure(label + " failed", failure)
        );
    }

    private void applyState(WorkbenchUiController.State state) {
        lastState = state;
        projectLabel.setText("Project: " + state.projectRoot().getFileName());
        if (state.liveReady()) {
            readinessLabel.setText("Live worker ready");
            readinessLabel.setForeground(WorkbenchTheme.SUCCESS);
        } else if (state.synchronizedProject()) {
            readinessLabel.setText("Synchronized");
            readinessLabel.setForeground(WorkbenchTheme.MUTED);
        } else {
            readinessLabel.setText("Not synchronized");
            readinessLabel.setForeground(WorkbenchTheme.WARNING);
        }
        syncItem.setEnabled(!state.workerRunning());
        startItem.setEnabled(state.synchronizedProject() && !state.workerRunning());
        restartItem.setEnabled(state.workerRunning());
        stopItem.setEnabled(state.workerRunning());

        if (!state.liveReady()) {
            nodeMapSelector.setEnabled(false);
            loadedMapping = null;
            setMappingEditor("", false);
            mappingStatus.setText("Start the live worker to inspect Mapping.");
        }
        if (humanControlsLocked()) {
            applyLease(lastLease);
        }
    }

    private boolean humanControlsLocked() {
        return lastLease != null && lastLease.agentHolds();
    }

    private void applyLease(WorkbenchControlLeaseSnapshot snapshot) {
        lastLease = snapshot;
        boolean locked = snapshot != null && snapshot.agentHolds();
        agentBanner.setVisible(locked);
        if (locked) {
            agentBannerLabel.setText(snapshot.bannerText());
        }
        WorkbenchPermissionRequest pending = snapshot == null ? null : snapshot.pendingPermission();
        permissionBar.setVisible(pending != null);
        if (pending != null) {
            pendingPermissionId = pending.id();
            permissionLabel.setText(pending.summary());
        } else {
            pendingPermissionId = null;
        }

        picker.setLocked(locked);
        pickerToggle.setEnabled(!locked);
        scenarioEditor.setEditable(!locked);
        if (locked) hideKeywordCompletions();
        stepText.setEditable(!locked);
        playButton.setEnabled(!locked);
        pauseButton.setEnabled(!locked);
        playerStopButton.setEnabled(!locked);
        stepOnlyButton.setEnabled(!locked);
        takeControlButton.setEnabled(locked);
        if (locked) {
            fromHereButton.setEnabled(false);
            syncItem.setEnabled(false);
            startItem.setEnabled(false);
            restartItem.setEnabled(false);
            stopItem.setEnabled(false);
            nodeMapSelector.setEnabled(false);
            mappingEditor.setEnabled(false);
        } else if (lastState != null) {
            syncItem.setEnabled(!lastState.workerRunning());
            startItem.setEnabled(lastState.synchronizedProject() && !lastState.workerRunning());
            restartItem.setEnabled(lastState.workerRunning());
            stopItem.setEnabled(lastState.workerRunning());
            if (lastState.liveReady()) {
                nodeMapSelector.setEnabled(nodeMapSelector.getItemCount() > 0);
            }
        }
        updateFromHereAvailability();
        pushMappingView();
    }

    private <T> void runTask(
            Supplier<T> action,
            Consumer<T> success,
            Consumer<Throwable> failure
    ) {
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() {
                return action.get();
            }

            @Override
            protected void done() {
                try {
                    success.accept(get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.accept(interrupted);
                } catch (ExecutionException execution) {
                    failure.accept(execution.getCause() == null ? execution : execution.getCause());
                } catch (RuntimeException runtime) {
                    failure.accept(runtime);
                }
            }
        }.execute();
    }

    private JComponent javaInspectorPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(WorkbenchTheme.SURFACE);
        panel.setBorder(WorkbenchTheme.cardBorder());
        panel.add(WorkbenchTheme.heading("Step definition"), BorderLayout.NORTH);
        javaInspector.setEditable(false);
        javaInspector.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        javaInspector.setLineWrap(false);
        WorkbenchTheme.styleEditor(javaInspector);
        javaInspector.setText("Select a Gherkin step to show its Java glue or Pickleball dynamic match.");
        JScrollPane inspectorScroll = new JScrollPane(javaInspector);
        WorkbenchTheme.styleScroll(inspectorScroll);
        panel.add(inspectorScroll, BorderLayout.CENTER);
        return panel;
    }

    private List<String> readFeatureFile(Path file, List<String> fallback) {
        if (file != null && Files.isRegularFile(file)) {
            try {
                return Files.readAllLines(file);
            } catch (java.io.IOException ignored) {
                // Fall back to the catalog excerpt.
            }
        }
        return fallback == null ? List.of() : fallback;
    }

    private EditorTabState activeEditorTab() {
        int index = editorTabs.getSelectedIndex();
        if (index < 0 || index >= editorSessions.size()) {
            return editorSessions.isEmpty() ? null : editorSessions.getFirst();
        }
        return editorSessions.get(index);
    }

    private void flushActiveEditorTab() {
        EditorTabState tab = activeEditorTab();
        if (tab == null) return;
        tab.setDocumentText(player.documentText());
        ScenarioOrigin origin = playback.origin();
        tab.setOrigin(
                origin.file(),
                origin.scenarioName(),
                origin.startLine(),
                origin.endLine(),
                origin.exampleRow(),
                origin.exampleLabel()
        );
    }

    private void openEditorTab(
            Path file,
            String title,
            String scenarioName,
            int startLine,
            int endLine,
            int exampleRow,
            String exampleLabel,
            String documentText,
            boolean pinIfFirst
    ) {
        for (int i = 0; i < editorSessions.size(); i++) {
            EditorTabState existing = editorSessions.get(i);
            if (existing.sameTarget(file, scenarioName, exampleRow)) {
                if (!existing.dirty()) {
                    existing.setDocumentText(documentText);
                    existing.markClean();
                }
                showEditorTab(i);
                return;
            }
        }
        EditorTabState tab = new EditorTabState(title, file, documentText, false);
        tab.setOrigin(file, scenarioName, startLine, endLine, exampleRow, exampleLabel);
        tab.markClean();
        if (pinIfFirst && pinnedCallerFile == null && file != null) {
            for (EditorTabState open : editorSessions) open.setPinned(false);
            pinnedCallerFile = file;
            tab.setPinned(true);
        }
        editorSessions.add(tab);
        rebuildTabStrip();
        showEditorTab(editorSessions.size() - 1);
    }

    private void showEditorTab(int index) {
        if (index < 0 || index >= editorSessions.size()) return;
        EditorTabState tab = editorSessions.get(index);
        shownTabIndex = index;
        if (tab.peek()) {
            syncingScenarioDocument = true;
            try {
                scenarioEditor.setText(tab.documentText());
                scenarioEditor.setEditable(false);
                scenarioEditor.setCaretPosition(0);
            } finally {
                syncingScenarioDocument = false;
            }
            picker.setSaveEnabled(false);
            if (editorTabs.getSelectedIndex() != index) {
                rebuildingTabs = true;
                try {
                    editorTabs.setSelectedIndex(index);
                } finally {
                    rebuildingTabs = false;
                }
            }
            return;
        }
        scenarioEditor.setEditable(!humanControlsLocked());
        controller.loadPickerScenario(
                tab.lines(),
                tab.file(),
                tab.scenarioName(),
                tab.startLine(),
                tab.endLine(),
                tab.exampleRow(),
                tab.exampleLabel()
        );
        if (editorTabs.getSelectedIndex() != index) {
            rebuildingTabs = true;
            try {
                editorTabs.setSelectedIndex(index);
            } finally {
                rebuildingTabs = false;
            }
        }
        picker.setSaveEnabled(tab.file() != null);
        syncScenarioView();
    }

    private void closeEditorTab(int index) {
        if (index < 0 || index >= editorSessions.size()) return;
        EditorTabState tab = editorSessions.get(index);
        if (tab.pinned() && editorSessions.size() > 1) {
            updatePlayerView("The original caller tab stays open so you can return to it.");
            return;
        }
        int next = Math.max(0, index - 1);
        editorSessions.remove(index);
        if (editorSessions.isEmpty()) {
            EditorTabState demo = new EditorTabState("Demo", null, String.join("\n", LiveScenarioPlayer.DEFAULT_DEMO_SCENARIO), true);
            editorSessions.add(demo);
            controller.loadDefaultDemo();
            next = 0;
        }
        rebuildTabStrip();
        showEditorTab(Math.min(next, editorSessions.size() - 1));
    }

    private void moveEditorTab(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= editorSessions.size() || to >= editorSessions.size()) {
            return;
        }
        EditorTabState tab = editorSessions.remove(from);
        editorSessions.add(to, tab);
        rebuildTabStrip();
        showEditorTab(to);
    }

    private void rebuildTabStrip() {
        rebuildingTabs = true;
        int selected = editorTabs.getSelectedIndex();
        editorTabs.removeAll();
        for (int i = 0; i < editorSessions.size(); i++) {
            EditorTabState tab = editorSessions.get(i);
            editorTabs.addTab(tab.tabTitle(), null);
            editorTabs.setTabComponentAt(i, tabHeader(tab, i));
        }
        if (!editorSessions.isEmpty()) {
            editorTabs.setSelectedIndex(Math.max(0, Math.min(selected, editorSessions.size() - 1)));
        }
        rebuildingTabs = false;
    }

    private JComponent tabHeader(EditorTabState tab, int index) {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        header.setOpaque(false);
        JLabel title = new JLabel(tab.tabTitle());
        title.setToolTipText(tab.file() == null ? "Session buffer" : tab.file().toString());
        header.add(title);
        JButton close = new JButton("×");
        close.setMargin(new Insets(0, 4, 0, 4));
        close.setBorder(BorderFactory.createEmptyBorder());
        close.setContentAreaFilled(false);
        close.setFocusable(false);
        close.setToolTipText(tab.pinned() ? "Keep the original caller tab" : "Close tab");
        close.setEnabled(!tab.pinned() || editorSessions.size() == 1);
        close.addActionListener(event -> closeEditorTab(index));
        header.add(close);
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                editorTabs.setSelectedIndex(index);
            }
        });
        header.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent event) {
                Point onStrip = SwingUtilities.convertPoint(header, event.getPoint(), editorTabs);
                int target = editorTabs.indexAtLocation(onStrip.x, onStrip.y);
                if (target >= 0 && target != index) moveEditorTab(index, target);
            }
        });
        return header;
    }

    private void resolveSelectedStep() {
        LiveScenarioPlayer.Line selected = player.selectedLine().orElse(player.playheadLine().orElse(null));
        if (selected == null) {
            javaInspector.setText("Select a Gherkin step to show its Java glue or Pickleball dynamic match.");
            return;
        }
        String text = selected.text();
        JavaGlueIndex.Match glue = glueIndex.find(text).orElse(null);
        if (glue != null) {
            javaInspector.setText(
                    "CONSUMER_GLUE\n"
                            + glue.className() + "#" + glue.methodName() + "\n"
                            + glue.file() + "\n\n"
                            + glue.snippet()
            );
            return;
        }
        List<GherkinReference> refs = GherkinReference.parse(text, linesAfter(selected));
        if (!refs.isEmpty()) {
            StringBuilder out = new StringBuilder("REFERENCE\n");
            for (GherkinReference ref : refs) {
                out.append(ref.kind()).append("  ").append(ref.selector());
                if (!ref.invocationValues().isEmpty()) {
                    out.append("  ").append(ref.invocationValues());
                }
                out.append('\n');
            }
            out.append("\nCtrl+click or Open target to navigate. Dynamic Pickleball steps have no project method.");
            javaInspector.setText(out.toString());
            return;
        }
        javaInspector.setText(
                "UNMATCHED or DYNAMIC\n"
                        + text.strip()
                        + "\n\nNo consumer @Given/@When/@Then matched this line. "
                        + "Pickleball dynamic steps are interpreted by the worker, not a project method."
        );
        if (lastState != null && lastState.liveReady()) {
            String requested = text;
            runTask(
                    () -> controller.resolveStep(requested, ""),
                    resolution -> {
                        if (!requested.equals(player.selectedLine().orElse(player.playheadLine().orElse(null)) == null
                                ? ""
                                : player.selectedLine().orElse(player.playheadLine().orElseThrow()).text())) {
                            return;
                        }
                        showResolution(resolution, requested);
                    },
                    failure -> {
                        // Keep the catalog/index text; worker resolve is optional.
                    }
            );
        }
    }

    private void showResolution(ControlBridgeStepResolution resolution, String stepText) {
        if (resolution == null) return;
        StringBuilder out = new StringBuilder();
        out.append(resolution.kind().isBlank() ? "UNMATCHED" : resolution.kind()).append('\n');
        if (!resolution.className().isBlank()) {
            out.append(resolution.className());
            if (!resolution.methodName().isBlank()) out.append('#').append(resolution.methodName());
            out.append('\n');
        }
        if (!resolution.sourcePath().isBlank()) out.append(resolution.sourcePath()).append('\n');
        if (!resolution.pattern().isBlank()) out.append(resolution.pattern()).append('\n');
        out.append('\n');
        if (!resolution.snippet().isBlank()) {
            out.append(resolution.snippet());
        } else if (!resolution.detail().isBlank()) {
            out.append(resolution.detail());
        } else {
            out.append(stepText.strip());
        }
        javaInspector.setText(out.toString());
        if (!resolution.sourcePath().isBlank()) {
            Path source = Path.of(resolution.sourcePath());
            if (Files.isRegularFile(source)) {
                try {
                    javaInspector.setText(out + "\n\n" + Files.readString(source));
                } catch (java.io.IOException ignored) {
                    // Snippet is enough when the file cannot be read.
                }
            }
        }
    }

    private List<String> linesAfter(LiveScenarioPlayer.Line selected) {
        List<LiveScenarioPlayer.Line> lines = player.lines();
        int index = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).id() == selected.id()) {
                index = i;
                break;
            }
        }
        if (index < 0) return List.of();
        List<String> following = new ArrayList<>();
        for (int i = index + 1; i < lines.size(); i++) {
            following.add(lines.get(i).text());
        }
        return following;
    }

    private void openSelectedTarget() {
        if (humanControlsLocked()) return;
        LiveScenarioPlayer.Line selected = player.selectedLine().orElse(player.playheadLine().orElse(null));
        if (selected == null) return;
        List<GherkinReference> refs = GherkinReference.parse(selected.text(), linesAfter(selected));
        if (refs.isEmpty()) {
            updatePlayerView("Selected line has no component, scenario, service-call, or data-file target.");
            return;
        }
        GherkinReference ref = refs.getFirst();
        Optional<CatalogTargetResolver.Target> target = CatalogTargetResolver.resolve(
                controller.projectRoot(),
                picker.catalog(),
                ref
        );
        if (target.isEmpty()) {
            updatePlayerView("Could not resolve " + ref.kind() + " " + ref.selector() + ".");
            return;
        }
        CatalogTargetResolver.Target found = target.get();
        flushActiveEditorTab();
        if (found.scenario() != null) {
            List<String> fileLines = readFeatureFile(found.file(), found.scenario().lines());
            controller.loadPickerScenario(
                    fileLines,
                    found.file(),
                    found.scenario().name(),
                    found.scenario().startLine(),
                    found.scenario().endLine(),
                    found.scenario().exampleRow(),
                    found.scenario().exampleLabel()
            );
            openEditorTab(
                    found.file(),
                    found.scenario().displayLabel(),
                    found.scenario().name(),
                    found.scenario().startLine(),
                    found.scenario().endLine(),
                    found.scenario().exampleRow(),
                    found.scenario().exampleLabel(),
                    player.documentText(),
                    true
            );
        } else {
            List<String> fileLines = readFeatureFile(found.file(), List.of());
            controller.loadPickerScenario(fileLines, found.file(), found.file().getFileName().toString(), 1, fileLines.size());
            openEditorTab(
                    found.file(),
                    found.file().getFileName().toString(),
                    found.file().getFileName().toString(),
                    1,
                    fileLines.size(),
                    0,
                    "",
                    player.documentText(),
                    true
            );
        }
        picker.setSaveEnabled(true);
        syncScenarioView();
        updatePlayerView("Opened " + found.detail() + ".");
        resolveSelectedStep();
    }

    private void showFailure(String label, Throwable failure) {
        String message = failure == null
                ? label
                : label + ": " + Objects.toString(failure.getMessage(), failure.getClass().getSimpleName());
        activityLabel.setText(message);
        JOptionPane.showMessageDialog(
                this,
                message,
                "Pickleball Workbench",
                JOptionPane.ERROR_MESSAGE
        );
    }

    /**
     * Existing non-Mapping investigation features remain available without
     * competing with the primary player workspace.
     */
    private void showAdvancedControls() {
        JDialog dialog = new JDialog(this, "Advanced Controls", false);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(900, 700);
        dialog.setLocationRelativeTo(this);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Status / Events", advancedStatusPanel());
        tabs.addTab("Step Overrides", advancedOverridesPanel());
        tabs.addTab("Browser / Service", advancedBrowserServicePanel());
        tabs.addTab("Breakpoints", advancedBreakpointsPanel());
        dialog.setContentPane(tabs);
        dialog.setVisible(true);
    }

    private JComponent advancedStatusPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        JTextArea output = outputArea();
        JButton refresh = new JButton("Refresh status and events");
        refresh.addActionListener(event -> runTask(
                () -> {
                    WorkbenchUiController.State state = controller.refresh();
                    String events = state.liveReady() ? controller.refreshEvents() : "";
                    return state.render() + (events.isBlank() ? "" : "\nEvents\n" + events);
                },
                output::setText,
                failure -> showFailure("Advanced status refresh failed", failure)
        ));
        panel.add(refresh, BorderLayout.NORTH);
        panel.add(new JScrollPane(output), BorderLayout.CENTER);
        return panel;
    }

    private JComponent advancedOverridesPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        JTextField id = new JTextField("workbench-ui-generated");
        JTextField regex = new JTextField("^WORKBENCH UI OVERRIDE ([A-Za-z]+)$");
        JTextArea source = new JTextArea(defaultOverrideSource(), 14, 60);
        JTextArea output = outputArea();

        JPanel fields = new JPanel(new GridLayout(2, 2, 6, 6));
        fields.add(new JLabel("ID"));
        fields.add(id);
        fields.add(new JLabel("Regex"));
        fields.add(regex);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton compile = new JButton("Compile / Replace");
        JButton list = new JButton("Refresh List");
        JButton remove = new JButton("Remove ID");
        JButton clear = new JButton("Clear All");
        buttons.add(compile);
        buttons.add(list);
        buttons.add(remove);
        buttons.add(clear);

        compile.addActionListener(event -> runTask(
                () -> controller.compileStepOverride(id.getText(), regex.getText(), source.getText()),
                result -> output.setText(result.output() + "\n\n" + result.listing()),
                failure -> showFailure("Step Override compile failed", failure)
        ));
        list.addActionListener(event -> runTask(
                controller::stepOverrides,
                output::setText,
                failure -> showFailure("Step Override list failed", failure)
        ));
        remove.addActionListener(event -> runTask(
                () -> controller.removeStepOverride(id.getText()),
                result -> output.setText(result.output() + "\n\n" + result.listing()),
                failure -> showFailure("Step Override remove failed", failure)
        ));
        clear.addActionListener(event -> runTask(
                controller::clearStepOverrides,
                result -> output.setText(result.output() + "\n\n" + result.listing()),
                failure -> showFailure("Step Override clear failed", failure)
        ));

        JPanel north = new JPanel(new BorderLayout(6, 6));
        north.add(fields, BorderLayout.NORTH);
        north.add(buttons, BorderLayout.SOUTH);
        panel.add(north, BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(source),
                new JScrollPane(output)
        );
        split.setResizeWeight(0.55);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JComponent advancedBrowserServicePanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        JTextArea output = outputArea();
        JTextField selector = new JTextField("%health-full-url");
        JButton page = new JButton("Read Page");
        JButton screenshot = new JButton("Capture Screenshot");
        JButton service = new JButton("Execute Service Call");

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(page);
        buttons.add(screenshot);
        buttons.add(new JLabel("Service:"));
        selector.setPreferredSize(new Dimension(220, selector.getPreferredSize().height));
        buttons.add(selector);
        buttons.add(service);

        page.addActionListener(event -> runTask(
                controller::browserPage,
                result -> output.setText(result.output()),
                failure -> showFailure("Browser page read failed", failure)
        ));
        screenshot.addActionListener(event -> runTask(
                controller::browserScreenshot,
                result -> output.setText(result.output()),
                failure -> showFailure("Browser screenshot failed", failure)
        ));
        service.addActionListener(event -> runTask(
                () -> controller.serviceCall(selector.getText()),
                result -> output.setText(result.output()),
                failure -> showFailure("Service call failed", failure)
        ));

        panel.add(buttons, BorderLayout.NORTH);
        panel.add(new JScrollPane(output), BorderLayout.CENTER);
        return panel;
    }

    private JComponent advancedBreakpointsPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JTextField id = new JTextField();
        JTextField hook = new JTextField("BEFORE_STEP");
        JTextField signature = new JTextField();
        JTextField step = new JTextField("CONTROL API TEST STEP");
        JTextField phrase = new JTextField();
        JTextField lease = new JTextField("120");
        JCheckBox oneShot = new JCheckBox("One shot", true);
        JTextArea output = outputArea();

        JPanel fields = new JPanel(new GridLayout(6, 2, 6, 6));
        fields.add(new JLabel("Breakpoint ID"));
        fields.add(id);
        fields.add(new JLabel("Hook"));
        fields.add(hook);
        fields.add(new JLabel("Signature contains"));
        fields.add(signature);
        fields.add(new JLabel("Step contains"));
        fields.add(step);
        fields.add(new JLabel("Phrase contains"));
        fields.add(phrase);
        fields.add(new JLabel("Lease seconds"));
        fields.add(lease);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton add = new JButton("Add");
        JButton list = new JButton("Refresh List");
        JButton remove = new JButton("Remove ID");
        JButton clear = new JButton("Clear All");
        buttons.add(oneShot);
        buttons.add(add);
        buttons.add(list);
        buttons.add(remove);
        buttons.add(clear);

        add.addActionListener(event -> runTask(
                () -> controller.addBreakpoint(
                        hook.getText(),
                        signature.getText(),
                        step.getText(),
                        phrase.getText(),
                        oneShot.isSelected(),
                        lease.getText()
                ),
                result -> output.setText(result.output() + "\n\n" + result.listing()),
                failure -> showFailure("Breakpoint add failed", failure)
        ));
        list.addActionListener(event -> runTask(
                controller::breakpoints,
                output::setText,
                failure -> showFailure("Breakpoint list failed", failure)
        ));
        remove.addActionListener(event -> runTask(
                () -> controller.removeBreakpoint(id.getText()),
                result -> output.setText(result.output() + "\n\n" + result.listing()),
                failure -> showFailure("Breakpoint remove failed", failure)
        ));
        clear.addActionListener(event -> runTask(
                controller::clearBreakpoints,
                result -> output.setText(result.output() + "\n\n" + result.listing()),
                failure -> showFailure("Breakpoint clear failed", failure)
        ));

        JPanel north = new JPanel(new BorderLayout(6, 6));
        north.add(fields, BorderLayout.CENTER);
        north.add(buttons, BorderLayout.SOUTH);
        panel.add(north, BorderLayout.NORTH);
        panel.add(new JScrollPane(output), BorderLayout.CENTER);
        return panel;
    }

    private void closeWorkbench() {
        if (closing) return;
        closing = true;
        player.stop();
        mappingSaveTimer.stop();
        terminal.stop();
        activityLabel.setText("Closing Workbench...");
        runTask(
                () -> {
                    if (attach != null) attach.close();
                    controller.close();
                    return Boolean.TRUE;
                },
                ignored -> {
                    dispose();
                },
                failure -> {
                    dispose();
                }
        );
    }

    private static JTextArea outputArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setLineWrap(false);
        return area;
    }

    private static JButton playerButton(String text, String tooltip) {
        return WorkbenchTheme.flatButton(text, tooltip);
    }

    private static JButton smallPlayerButton(String text, String tooltip) {
        JButton button = playerButton(text, tooltip);
        button.setMargin(new Insets(1, 7, 1, 7));
        return button;
    }

    private static String defaultOverrideSource() {
        return """
                package tools.dscode.workbench.generated;

                import tools.dscode.control.api.MappingControl;
                import tools.dscode.control.override.StepOverrideContext;
                import tools.dscode.control.override.StepOverrideHandler;

                public final class {{CLASS_NAME}} implements StepOverrideHandler {
                    public Object execute(StepOverrideContext context) {
                        MappingControl.put(
                            "OVERRIDE",
                            "workbenchUiOverrideValue",
                            context.captures().isEmpty() ? "matched" : context.captures().getFirst()
                        );
                        return null;
                    }
                }
                """;
    }

}
