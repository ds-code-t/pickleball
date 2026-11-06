package tools.dscode.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Drops {@code WARN:} and {@code WARNING:} lines. {@code error:} and {@code fix:} stay.
 * Discover and confirm use this for {@code --quiet}. Seed warnings are written with
 * {@link Appendable#append}, so the filter is the byte stream, not {@code println} alone.
 */
final class QuietWarnings extends OutputStream {
    private final OutputStream delegate;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    private QuietWarnings(OutputStream delegate) {
        this.delegate = delegate;
    }

    static PrintStream filtering(PrintStream delegate) {
        OutputStream raw = delegate == null ? OutputStream.nullOutputStream() : delegate;
        return new PrintStream(new QuietWarnings(raw), true, StandardCharsets.UTF_8);
    }

    @Override
    public void write(int value) throws IOException {
        if (value == '\n') {
            flushLine();
            return;
        }
        pending.write(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        for (int i = 0; i < length; i++) write(bytes[offset + i]);
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        if (pending.size() > 0) flushLine();
        delegate.flush();
    }

    private void flushLine() throws IOException {
        String line = pending.toString(StandardCharsets.UTF_8).replace("\r", "");
        pending.reset();
        if (line.startsWith("WARN:") || line.startsWith("WARNING:")) return;
        delegate.write(line.getBytes(StandardCharsets.UTF_8));
        delegate.write('\n');
    }
}
