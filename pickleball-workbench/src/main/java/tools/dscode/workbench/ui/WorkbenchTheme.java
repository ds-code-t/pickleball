package tools.dscode.workbench.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;

/** Flat, readable Swing chrome for the Workbench player. */
final class WorkbenchTheme {
    static final Color BACKGROUND = new Color(0xF4, 0xF6, 0xF8);
    static final Color SURFACE = Color.WHITE;
    static final Color SURFACE_ALT = new Color(0xEE, 0xF2, 0xF7);
    static final Color BORDER = new Color(0xD7, 0xDE, 0xE7);
    static final Color TEXT = new Color(0x1F, 0x29, 0x37);
    static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    static final Color ACCENT = new Color(0x25, 0x63, 0xEB);
    static final Color ACCENT_SOFT = new Color(0xDB, 0xEA, 0xFE);
    static final Color PLAYHEAD = new Color(0xFE, 0xF3, 0xC7);
    static final Color DANGER = new Color(0xB9, 0x1C, 0x1C);
    static final Color SUCCESS = new Color(0x04, 0x78, 0x57);
    static final Color WARNING = new Color(0xB4, 0x53, 0x09);

    private WorkbenchTheme() {
    }

    static void install() {
        Font base = new Font("SansSerif", Font.PLAIN, 13);
        Font bold = base.deriveFont(Font.BOLD, 12f);
        UIManager.put("Panel.background", new ColorUIResource(BACKGROUND));
        UIManager.put("OptionPane.background", new ColorUIResource(BACKGROUND));
        UIManager.put("OptionPane.messageForeground", new ColorUIResource(TEXT));
        UIManager.put("Label.foreground", new ColorUIResource(TEXT));
        UIManager.put("Label.font", new FontUIResource(base));
        UIManager.put("Button.background", new ColorUIResource(SURFACE));
        UIManager.put("Button.foreground", new ColorUIResource(TEXT));
        UIManager.put("Button.focus", new ColorUIResource(ACCENT_SOFT));
        UIManager.put("Button.font", new FontUIResource(base));
        UIManager.put("ToggleButton.background", new ColorUIResource(SURFACE));
        UIManager.put("ToggleButton.foreground", new ColorUIResource(TEXT));
        UIManager.put("ToggleButton.font", new FontUIResource(base));
        UIManager.put("ToggleButton.select", new ColorUIResource(ACCENT_SOFT));
        UIManager.put("TextField.background", new ColorUIResource(SURFACE));
        UIManager.put("TextField.foreground", new ColorUIResource(TEXT));
        UIManager.put("TextField.caretForeground", new ColorUIResource(ACCENT));
        UIManager.put("TextField.selectionBackground", new ColorUIResource(ACCENT_SOFT));
        UIManager.put("TextField.font", new FontUIResource(base));
        UIManager.put("TextArea.background", new ColorUIResource(SURFACE));
        UIManager.put("TextArea.foreground", new ColorUIResource(TEXT));
        UIManager.put("TextArea.caretForeground", new ColorUIResource(ACCENT));
        UIManager.put("TextArea.selectionBackground", new ColorUIResource(ACCENT_SOFT));
        UIManager.put("ComboBox.background", new ColorUIResource(SURFACE));
        UIManager.put("ComboBox.foreground", new ColorUIResource(TEXT));
        UIManager.put("ComboBox.font", new FontUIResource(base));
        UIManager.put("List.background", new ColorUIResource(SURFACE));
        UIManager.put("List.foreground", new ColorUIResource(TEXT));
        UIManager.put("List.selectionBackground", new ColorUIResource(ACCENT_SOFT));
        UIManager.put("List.selectionForeground", new ColorUIResource(TEXT));
        UIManager.put("TabbedPane.background", new ColorUIResource(BACKGROUND));
        UIManager.put("TabbedPane.contentAreaColor", new ColorUIResource(SURFACE));
        UIManager.put("TabbedPane.selected", new ColorUIResource(SURFACE));
        UIManager.put("TabbedPane.font", new FontUIResource(bold));
        UIManager.put("SplitPane.background", new ColorUIResource(BACKGROUND));
        UIManager.put("MenuBar.background", new ColorUIResource(SURFACE));
        UIManager.put("Menu.font", new FontUIResource(base));
        UIManager.put("MenuItem.font", new FontUIResource(base));
        UIManager.put("ToolTip.background", new ColorUIResource(SURFACE));
        UIManager.put("ToolTip.foreground", new ColorUIResource(TEXT));
        UIManager.put("ScrollPane.background", new ColorUIResource(SURFACE));
        UIManager.put("Viewport.background", new ColorUIResource(SURFACE));
    }

    static Border cardBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(10, 12, 10, 12)
        );
    }

    static Border hairline() {
        return BorderFactory.createLineBorder(BORDER);
    }

    static JPanel card(String title) {
        JPanel panel = new JPanel();
        panel.setBackground(SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(10, 12, 12, 12)
        ));
        if (title != null) {
            panel.setName(title);
        }
        return panel;
    }

    static JLabel muted(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        return label;
    }

    static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        label.setForeground(TEXT);
        return label;
    }

    static JButton flatButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setBackground(SURFACE);
        button.setForeground(TEXT);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(6, 12, 6, 12)
        ));
        button.setMargin(new Insets(2, 8, 2, 8));
        return button;
    }

    static JButton accentButton(String text, String tooltip) {
        JButton button = flatButton(text, tooltip);
        button.setBackground(ACCENT);
        button.setForeground(Color.WHITE);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT.darker()),
                new EmptyBorder(6, 14, 6, 14)
        ));
        return button;
    }

    static void styleSplit(JSplitPane split) {
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);
        split.setDividerSize(8);
        split.setBackground(BACKGROUND);
    }

    static void surface(JComponent component) {
        component.setBackground(SURFACE);
        component.setForeground(TEXT);
        component.setOpaque(true);
    }

    static void styleEditor(JTextComponent editor) {
        editor.setBackground(SURFACE);
        editor.setForeground(TEXT);
        editor.setCaretColor(ACCENT);
        editor.setSelectionColor(ACCENT_SOFT);
        editor.setSelectedTextColor(TEXT);
        editor.setBorder(new EmptyBorder(8, 10, 8, 10));
    }

    static void styleScroll(JScrollPane scroll) {
        scroll.setBorder(hairline());
        scroll.getViewport().setBackground(SURFACE);
    }

    static Dimension compact(int width, int height) {
        return new Dimension(width, height);
    }
}
