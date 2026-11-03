package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

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

    /** Descriptor files under {@code .pickleball/runs}, ordered by run id. */
    public static List<Path> files(Path project) {
        Path root = RunView.runsRoot(project);
        if (!Files.isDirectory(root)) return List.of();
        List<Path> found = new ArrayList<>();
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(dir -> {
                        Path file = dir.resolve(DIRECTORY).resolve(SessionDescriptor.FILE_NAME);
                        if (Files.isRegularFile(file)) found.add(file);
                    });
        } catch (IOException failure) {
            throw new IllegalStateException("Could not list session descriptors: " + failure.getMessage(), failure);
        }
        return List.copyOf(found);
    }

    /** Published sessions that are not {@code stopped}. */
    public static List<Path> liveFiles(Path project) {
        List<Path> live = new ArrayList<>();
        for (Path file : files(project)) {
            if (readFile(file).state() != SessionDescriptor.State.STOPPED) live.add(file);
        }
        return List.copyOf(live);
    }

    /**
     * Finds a descriptor by session id, then by run id.
     * Stopped sessions are included. An unknown id is empty.
     */
    public static Optional<Path> findFile(Path project, String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("session id is required.");
        String token = id.trim();
        Path bySession = null;
        Path byRun = null;
        int sessionHits = 0;
        int runHits = 0;
        for (Path file : files(project)) {
            SessionDescriptor descriptor = readFile(file);
            if (token.equals(descriptor.sessionId())) {
                sessionHits++;
                bySession = file;
            } else if (token.equals(descriptor.runId())) {
                runHits++;
                byRun = file;
            }
        }
        if (sessionHits > 1) {
            throw new IllegalStateException("More than one session descriptor has sessionId " + token + ".");
        }
        if (sessionHits == 1) return Optional.of(bySession);
        if (runHits > 1) {
            throw new IllegalStateException("More than one session descriptor has runId " + token + ".");
        }
        if (runHits == 1) return Optional.of(byRun);
        return Optional.empty();
    }

    public static SessionDescriptor readFile(Path file) {
        try {
            return SessionDescriptor.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read " + file + ": " + failure.getMessage(), failure);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Could not read " + file + ": " + failure.getMessage(), failure);
        }
    }

    public static final String HOLD_FILE = "hold.json";

    /**
     * Sets {@code held} and remembers the state to restore.
     * A second hold does not replace that remembered state.
     */
    public static SessionDescriptor hold(Path project, String id, Instant updatedAt) {
        Located located = locate(project, id);
        if (located.descriptor().state() == SessionDescriptor.State.HELD) {
            return transition(project, located.descriptor().runId(), SessionDescriptor.State.HELD, updatedAt);
        }
        SessionDescriptor.State resume = located.descriptor().state();
        if (resume != SessionDescriptor.State.RUNNING && resume != SessionDescriptor.State.PAUSED) {
            throw new IllegalStateException(
                    "Cannot hold a session that is " + resume.wire() + "."
            );
        }
        Path sidecar = located.file().getParent().resolve(HOLD_FILE);
        Workspace.atomicWriteString(sidecar, "{\"resume\":\"" + resume.wire() + "\"}\n");
        try {
            return transition(project, located.descriptor().runId(), SessionDescriptor.State.HELD, updatedAt);
        } catch (RuntimeException failure) {
            try {
                Files.deleteIfExists(sidecar);
            } catch (IOException ignored) {
                // The descriptor was not replaced.
            }
            throw failure;
        }
    }

    /** Restores the state remembered by {@link #hold}. */
    public static SessionDescriptor release(Path project, String id, Instant updatedAt) {
        Located located = locate(project, id);
        if (located.descriptor().state() != SessionDescriptor.State.HELD) {
            throw new IllegalStateException(
                    "Session " + id + " is " + located.descriptor().state().wire() + ", not held."
            );
        }
        SessionDescriptor.State resume = remembered(located.file().getParent());
        SessionDescriptor updated = transition(project, located.descriptor().runId(), resume, updatedAt);
        try {
            Files.deleteIfExists(located.file().getParent().resolve(HOLD_FILE));
        } catch (IOException failure) {
            throw new IllegalStateException("Could not clear the hold: " + failure.getMessage(), failure);
        }
        return updated;
    }

    private static Located locate(Path project, String id) {
        Path file = findFile(project, id).orElseThrow(() ->
                new IllegalStateException("No session descriptor for " + id + "."));
        return new Located(file, readFile(file));
    }

    private static SessionDescriptor.State remembered(Path sessionDirectory) {
        Path sidecar = sessionDirectory.resolve(HOLD_FILE);
        if (!Files.isRegularFile(sidecar)) return SessionDescriptor.State.RUNNING;
        try {
            String text = Files.readString(sidecar, StandardCharsets.UTF_8);
            if (text.contains("\"paused\"")) return SessionDescriptor.State.PAUSED;
            return SessionDescriptor.State.RUNNING;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read " + sidecar + ": " + failure.getMessage(), failure);
        }
    }

    private record Located(Path file, SessionDescriptor descriptor) {
    }
}
