
package tools.dscode.studio.gui;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Font;

final class EditorTab extends JPanel {
    private final String path;
    private final JTextArea editor = new JTextArea();
    private final Runnable stateChanged;
    private String savedText;

    EditorTab(String path, String content, Runnable stateChanged) {
        super(new BorderLayout());
        this.path = path;
        this.savedText = content;
        this.stateChanged = stateChanged;

        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        editor.setText(content);
        editor.setCaretPosition(0);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                stateChanged.run();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                stateChanged.run();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                stateChanged.run();
            }
        });

        add(new JScrollPane(editor), BorderLayout.CENTER);
    }

    String path() {
        return path;
    }

    String text() {
        return editor.getText();
    }

    boolean dirty() {
        return !savedText.equals(editor.getText());
    }

    void markSaved(String content) {
        savedText = content;
        stateChanged.run();
    }

    void replaceSavedContent(String content) {
        savedText = content;
        editor.setText(content);
        editor.setCaretPosition(0);
        stateChanged.run();
    }

    void navigateToLine(int line) {
        int requested = Math.max(1, line);
        try {
            int offset = editor.getLineStartOffset(
                    Math.min(requested - 1, Math.max(0, editor.getLineCount() - 1))
            );
            editor.setCaretPosition(offset);
            editor.requestFocusInWindow();
        } catch (BadLocationException ignored) {
            editor.setCaretPosition(0);
        }
    }

    String title() {
        int separator = path.lastIndexOf('/');
        String name = separator < 0 ? path : path.substring(separator + 1);
        return dirty() ? name + " *" : name;
    }
}
