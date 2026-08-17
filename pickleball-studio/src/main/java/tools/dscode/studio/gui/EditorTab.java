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
    private String savedSha256;
    private boolean reportedDirty;
    private boolean updating;

    EditorTab(
            String path,
            String content,
            String savedSha256,
            Runnable stateChanged
    ) {
        super(new BorderLayout());
        this.path = path;
        this.savedText = content;
        this.savedSha256 = savedSha256;
        this.stateChanged = stateChanged;

        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        editor.setText(content);
        editor.setCaretPosition(0);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) { changed(); }
            @Override
            public void removeUpdate(DocumentEvent event) { changed(); }
            @Override
            public void changedUpdate(DocumentEvent event) { changed(); }
        });

        add(new JScrollPane(editor), BorderLayout.CENTER);
    }

    String path() { return path; }
    String text() { return editor.getText(); }
    String baseSha256() { return savedSha256; }
    boolean dirty() { return !savedText.equals(editor.getText()); }

    void markSaved(String content, String sha256) {
        savedText = content;
        savedSha256 = sha256;
        changed();
    }

    void replaceSavedContent(String content, String sha256) {
        updating = true;
        try {
            savedText = content;
            savedSha256 = sha256;
            editor.setText(content);
            editor.setCaretPosition(0);
        } finally {
            updating = false;
        }
        changed();
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

    private void changed() {
        if (updating) {
            return;
        }
        boolean dirty = dirty();
        if (dirty != reportedDirty) {
            reportedDirty = dirty;
            stateChanged.run();
        }
    }
}
