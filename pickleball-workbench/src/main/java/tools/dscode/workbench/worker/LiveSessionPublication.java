package tools.dscode.workbench.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.control.protocol.DirectControlProvider;
import tools.dscode.control.protocol.RunView;
import tools.dscode.control.protocol.SessionDescriptor;
import tools.dscode.control.protocol.SessionDescriptorWriter;
import tools.dscode.control.protocol.SessionStepGate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One writer for the live session descriptor, the optional provider, and the hold gate.
 * Headless isolate and the GUI controller both call this through {@link WorkbenchLiveSession}.
 */
final class LiveSessionPublication {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path project;
    private final Map<String, String> properties;
    private String runId;
    private String sessionId;
    private SessionDescriptor.State restore;
    private DirectControlProvider.Handle provider;
    private boolean providerDecided;

    LiveSessionPublication(Path project, Map<String, String> properties) {
        this.project = project;
        this.properties = properties == null ? Map.of() : properties;
    }

    String runId() {
        return runId;
    }

    void opened(String sessionId) {
        this.sessionId = safe(sessionId);
        this.runId = chooseRunId(properties.get("pkb_run_id"), this.sessionId);
        Instant now = Instant.now();
        SessionDescriptor descriptor = new SessionDescriptor(
                this.sessionId,
                runId,
                workspace().toString(),
                null,
                null,
                null,
                List.of(),
                SessionDescriptor.State.PAUSED,
                now,
                now
        );
        SessionDescriptorWriter.publish(project, descriptor, true);
        noteRecord();
    }

    void beforeStep() {
        if (runId == null) return;
        SessionDescriptor.State state = SessionStepGate.awaitAdvance(project, runId, SessionStepGate.DEFAULT_WAIT);
        if (state == null) return;
        if (state == SessionDescriptor.State.STOPPING || state == SessionDescriptor.State.STOPPED) {
            throw new IllegalStateException("Session is " + state.wire() + ".");
        }
        if (state == SessionDescriptor.State.PAUSED || state == SessionDescriptor.State.IDLE) {
            restore = state;
            SessionDescriptorWriter.transition(project, runId, SessionDescriptor.State.RUNNING, Instant.now());
        } else {
            restore = null;
        }
    }

    void afterStep(Page page) {
        if (runId == null) return;
        refresh(page == null ? Page.none() : page);
        Optional<SessionDescriptor> current = SessionDescriptorWriter.read(project, runId);
        if (current.isPresent()
                && restore != null
                && current.get().state() == SessionDescriptor.State.RUNNING
                && current.get().state().allows(restore)) {
            SessionDescriptorWriter.transition(project, runId, restore, Instant.now());
        }
        restore = null;
        launchIfReady();
    }

    void playback(SessionDescriptor.State state) {
        if (runId == null || state == null) return;
        Optional<SessionDescriptor> current = SessionDescriptorWriter.read(project, runId);
        if (current.isEmpty()) return;
        if (current.get().state() == SessionDescriptor.State.HELD && state != SessionDescriptor.State.HELD) return;
        if (!current.get().state().allows(state)) return;
        SessionDescriptorWriter.transition(project, runId, state, Instant.now());
    }

    void closing() {
        if (provider != null) {
            provider.detach();
            provider = null;
        }
        if (runId == null) return;
        Optional<SessionDescriptor> current = SessionDescriptorWriter.read(project, runId);
        if (current.isEmpty()) return;
        SessionDescriptor.State state = current.get().state();
        Instant now = Instant.now();
        if (state != SessionDescriptor.State.STOPPING
                && state != SessionDescriptor.State.STOPPED
                && state.allows(SessionDescriptor.State.STOPPING)) {
            SessionDescriptorWriter.transition(project, runId, SessionDescriptor.State.STOPPING, now);
            state = SessionDescriptor.State.STOPPING;
            now = now.plusMillis(1);
        }
        if (state.allows(SessionDescriptor.State.STOPPED)) {
            SessionDescriptorWriter.transition(project, runId, SessionDescriptor.State.STOPPED, now);
        }
    }

    private void refresh(Page page) {
        Optional<SessionDescriptor> current = SessionDescriptorWriter.read(project, runId);
        if (current.isEmpty()) return;
        SessionDescriptor existing = current.get();
        SessionDescriptor updated = existing.withPage(
                page.browserType() != null ? page.browserType() : existing.browserType(),
                page.cdpDebuggerAddress() != null ? page.cdpDebuggerAddress() : existing.cdpDebuggerAddress(),
                page.url() != null ? page.url() : existing.currentUrl(),
                page.windowHandles() != null ? page.windowHandles() : existing.windowHandles(),
                Instant.now()
        );
        SessionDescriptorWriter.publish(project, updated, true);
    }

    private void launchIfReady() {
        if (providerDecided || provider != null) return;
        Optional<SessionDescriptor> current = SessionDescriptorWriter.read(project, runId);
        if (current.isEmpty() || current.get().cdpDebuggerAddress() == null) return;
        String configured = DirectControlProvider.configured(project, runId).orElse(null);
        if (configured == null) return;
        DirectControlProvider.Outcome outcome = DirectControlProvider.open(new DirectControlProvider.Request(
                true,
                configured,
                project,
                runId,
                sessionId,
                current.get().cdpDebuggerAddress(),
                SessionDescriptorWriter.file(project, runId),
                SessionDescriptorWriter.file(project, runId).getParent().resolve("provider.log"),
                null,
                null
        ));
        providerDecided = true;
        if (outcome.kind() == DirectControlProvider.Kind.STARTED) provider = outcome.handle();
    }

    private void noteRecord() {
        Path record = RunView.runDirectory(project, runId).resolve(RunView.RECORD_FILE);
        if (!Files.isRegularFile(record)) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = JSON.readValue(record.toFile(), LinkedHashMap.class);
            body.put("sessionDescriptor", SessionDescriptorWriter.file(project, runId).toAbsolutePath().normalize().toString());
            JSON.writerWithDefaultPrettyPrinter().writeValue(record.toFile(), body);
        } catch (Exception ignored) {
            // The descriptor file is the source of truth.
        }
    }

    private Path workspace() {
        String value = properties.get("pkb_workspace");
        if (value != null && !value.isBlank() && Path.of(value).isAbsolute()) {
            return Path.of(value).normalize();
        }
        return project;
    }

    private static String chooseRunId(String property, String sessionId) {
        if (safe(property) != null) return property.trim();
        if (sessionId != null) return sessionId;
        throw new IllegalArgumentException("A live session id is required.");
    }

    private static String safe(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            return RunView.requireSafeId(id.trim());
        } catch (RuntimeException failure) {
            return null;
        }
    }

    record Page(String browserType, String cdpDebuggerAddress, String url, List<String> windowHandles) {
        static Page none() {
            return new Page(null, null, null, null);
        }
    }
}
