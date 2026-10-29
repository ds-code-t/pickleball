package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * Writes {@link SessionDescriptor} next to one live Workbench session.
 *
 * <p>The file is {@code .pickleball/runs/<runId>/session/descriptor.json}.
 * A live session publishes it. A normal run does not: {@link #publish}
 * with {@code live=false} creates no file and no directory. There is no
 * second writer.</p>
 */
public final class SessionDescriptorWriter {
    public static final String DIRECTORY = "session";

    private SessionDescriptorWriter() {
    }

    public static Path file(Path project, String runId) {
        return RunView.runDirectory(project, runId).resolve(DIRECTORY).resolve(SessionDescriptor.FILE_NAME);
    }

    /**
     * Atomically replaces the descriptor when {@code live} is true.
     * A non-live run is a no-op, including when a descriptor is already there.
     */
    public static Optional<Path> publish(Path project, SessionDescriptor descriptor, boolean live) {
        if (project == null) throw new IllegalArgumentException("project is required.");
        if (descriptor == null) throw new IllegalArgumentException("descriptor is required.");
        if (!live) return Optional.empty();
        Path target = file(project, descriptor.runId());
        Workspace.atomicWriteString(target, descriptor.toJson());
        return Optional.of(target);
    }

    public static Optional<SessionDescriptor> read(Path project, String runId) {
        Path target = file(project, runId);
        if (!Files.isRegularFile(target)) return Optional.empty();
        try {
            return Optional.of(SessionDescriptor.parse(Files.readString(target, StandardCharsets.UTF_8)));
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read " + target + ": " + failure.getMessage(), failure);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Could not read " + target + ": " + failure.getMessage(), failure);
        }
    }

    /**
     * Moves the published descriptor to {@code next}. An illegal move throws
     * before the file is replaced.
     */
    public static SessionDescriptor transition(Path project, String runId, SessionDescriptor.State next, Instant updatedAt) {
        SessionDescriptor current = read(project, runId).orElseThrow(() ->
                new IllegalStateException("No session descriptor for " + RunView.requireSafeId(runId) + "."));
        SessionDescriptor updated = current.withState(next, updatedAt);
        publish(project, updated, true);
        return updated;
    }
}
