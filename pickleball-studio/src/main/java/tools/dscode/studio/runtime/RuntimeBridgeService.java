
package tools.dscode.studio.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.studio.build.GradleBuildService;
import tools.dscode.studio.build.ManagedGradleRunResult;
import tools.dscode.studio.build.ManagedMavenRunResult;
import tools.dscode.studio.build.MavenBuildService;
import tools.dscode.studio.process.ManagedProcessSummary;
import tools.dscode.studio.workspace.WorkspaceInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RuntimeBridgeService implements AutoCloseable {
    public static final int DEFAULT_RUNTIME_TIMEOUT_SECONDS = 3600;

    private static final String ENV_SESSION_DIR = "PKB_STUDIO_BRIDGE_SESSION_DIR";
    private static final String ENV_SESSION_ID = "PKB_STUDIO_BRIDGE_SESSION_ID";
    private static final String ENV_TOKEN = "PKB_STUDIO_BRIDGE_TOKEN";
    private static final String ENV_PAUSE_FIRST_SCENARIO = "PKB_STUDIO_BRIDGE_PAUSE_FIRST_SCENARIO";

    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private final WorkspaceInfo workspace;
    private final MavenBuildService maven;
    private final GradleBuildService gradle;
    private final Path sessionRoot;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public RuntimeBridgeService(
            WorkspaceInfo workspace,
            MavenBuildService maven,
            GradleBuildService gradle
    ) {
        this(
                workspace,
                maven,
                gradle,
                Path.of(
                        System.getProperty("user.home"),
                        ".pickleball",
                        "studio",
                        "bridge"
                )
        );
    }

    RuntimeBridgeService(
            WorkspaceInfo workspace,
            MavenBuildService maven,
            GradleBuildService gradle,
            Path sessionRoot
    ) {
        this.workspace = workspace;
        this.maven = maven;
        this.gradle = gradle;
        this.sessionRoot = sessionRoot.toAbsolutePath().normalize();
    }

    public RuntimeLaunchResult start(
            List<String> buildArguments,
            Integer timeoutSeconds,
            Boolean pauseFirstScenario
    ) {
        List<String> arguments = buildArguments == null || buildArguments.isEmpty()
                ? List.of("test")
                : List.copyOf(buildArguments);
        int timeout = timeoutSeconds == null
                ? DEFAULT_RUNTIME_TIMEOUT_SECONDS
                : timeoutSeconds;
        if (timeout < 1) {
            throw new IllegalArgumentException("Runtime build timeout must be greater than zero.");
        }

        String sessionId = UUID.randomUUID().toString();
        String token = token();
        Path directory = sessionRoot.resolve(sessionId);
        createSessionDirectory(directory);

        Session session = new Session(sessionId, token, directory);
        sessions.put(sessionId, session);

        Map<String, String> environment = bridgeEnvironment(
                session,
                pauseFirstScenario == null || pauseFirstScenario
        );

        try {
            if (workspace.gradleProject()) {
                ManagedGradleRunResult result = gradle.start(arguments, timeout, environment);
                return new RuntimeLaunchResult(sessionId, "Gradle", result.process());
            }
            if (workspace.mavenProject()) {
                ManagedMavenRunResult result = maven.start(arguments, timeout, environment);
                return new RuntimeLaunchResult(sessionId, "Maven", result.process());
            }
            throw new IllegalArgumentException(
                    "Workspace is not a Gradle or Maven project: " + workspace.root()
            );
        } catch (RuntimeException failure) {
            sessions.remove(sessionId);
            deleteDirectoryIfEmpty(directory);
            throw failure;
        }
    }

    public List<RuntimeBridgeDescriptor> list(String sessionId) {
        Session session = requireSession(sessionId);
        if (!Files.isDirectory(session.directory)) {
            return List.of();
        }

        List<RuntimeBridgeDescriptor> result = new ArrayList<>();
        try (var files = Files.list(session.directory)) {
            for (Path descriptorFile : files
                    .filter(path -> path.getFileName().toString().startsWith("runtime-"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList()) {
                RuntimeBridgeDescriptor descriptor = readDescriptor(descriptorFile);
                if (!session.id.equals(descriptor.sessionId())) {
                    continue;
                }
                if (!ProcessHandle.of(descriptor.pid())
                        .map(ProcessHandle::isAlive)
                        .orElse(false)) {
                    Files.deleteIfExists(descriptorFile);
                    continue;
                }
                result.add(descriptor);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not inspect runtime bridge session: " + session.directory,
                    failure
            );
        }

        result.sort(Comparator
                .comparing(RuntimeBridgeDescriptor::startedAt)
                .thenComparing(RuntimeBridgeDescriptor::runtimeId));
        return List.copyOf(result);
    }

    public RuntimeBridgeStatus status(String sessionId, String runtimeId) {
        Session session = requireSession(sessionId);
        return client(session, runtimeId).status();
    }

    public List<RuntimeScenarioStatus> scenarios(String sessionId, String runtimeId) {
        Session session = requireSession(sessionId);
        return client(session, runtimeId).scenarios();
    }

    public RuntimeControlResult pause(
            String sessionId,
            String runtimeId,
            Integer waitSeconds,
            Integer leaseSeconds
    ) {
        return pause(sessionId, runtimeId, null, waitSeconds, leaseSeconds);
    }

    public RuntimeControlResult pause(
            String sessionId,
            String runtimeId,
            String scenarioId,
            Integer waitSeconds,
            Integer leaseSeconds
    ) {
        Session session = requireSession(sessionId);
        return client(session, runtimeId).pause(scenarioId, waitSeconds, leaseSeconds);
    }

    public RuntimeControlResult resume(String sessionId, String runtimeId) {
        return resume(sessionId, runtimeId, null);
    }

    public RuntimeControlResult resume(
            String sessionId,
            String runtimeId,
            String scenarioId
    ) {
        Session session = requireSession(sessionId);
        return client(session, runtimeId).resume(scenarioId);
    }

    public RuntimeControlResult executeStep(
            String sessionId,
            String runtimeId,
            String text,
            String argument,
            Integer timeoutSeconds
    ) {
        return executeStep(sessionId, runtimeId, null, text, argument, timeoutSeconds);
    }

    public RuntimeControlResult executeStep(
            String sessionId,
            String runtimeId,
            String scenarioId,
            String text,
            String argument,
            Integer timeoutSeconds
    ) {
        Session session = requireSession(sessionId);
        return client(session, runtimeId).executeStep(
                scenarioId,
                text,
                argument,
                timeoutSeconds
        );
    }

    public RuntimeValueResult mappingGet(
            String sessionId,
            String runtimeId,
            String scenarioId,
            String mapReference,
            String key,
            Integer timeoutSeconds
    ) {
        Session session = requireSession(sessionId);
        return client(session, runtimeId).mappingGet(
                scenarioId,
                mapReference,
                key,
                timeoutSeconds
        );
    }

    public RuntimeValueResult mappingPut(
            String sessionId,
            String runtimeId,
            String scenarioId,
            String mapReference,
            String key,
            String jsonValue,
            Integer timeoutSeconds
    ) {
        Session session = requireSession(sessionId);
        return client(session, runtimeId).mappingPut(
                scenarioId,
                mapReference,
                key,
                jsonValue,
                timeoutSeconds
        );
    }

    public RuntimeValueResult mappingResolve(
            String sessionId,
            String runtimeId,
            String scenarioId,
            String input,
            Integer timeoutSeconds
    ) {
        Session session = requireSession(sessionId);
        return client(session, runtimeId).mappingResolve(
                scenarioId,
                input,
                timeoutSeconds
        );
    }

    @Override
    public void close() {
        for (Session session : sessions.values()) {
            for (RuntimeBridgeDescriptor descriptor : safeList(session)) {
                RuntimeBridgeClient client = new RuntimeBridgeClient(descriptor, session.token);
                try {
                    List<RuntimeScenarioStatus> scenarios = client.scenarios();
                    if (scenarios.isEmpty()) {
                        client.resume();
                    } else {
                        scenarios.forEach(scenario -> {
                            try {
                                client.resume(scenario.scenarioId());
                            } catch (RuntimeException ignored) {
                            }
                        });
                    }
                } catch (RuntimeException ignored) {
                    try {
                        client.resume();
                    } catch (RuntimeException ignoredAgain) {
                    }
                }
            }
        }
        sessions.clear();
    }

    private RuntimeBridgeClient client(Session session, String runtimeId) {
        if (runtimeId == null || runtimeId.isBlank()) {
            throw new IllegalArgumentException("Runtime id must not be blank.");
        }
        RuntimeBridgeDescriptor descriptor = list(session.id).stream()
                .filter(candidate -> runtimeId.equals(candidate.runtimeId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown live runtime bridge id " + runtimeId
                                + " for session " + session.id
                ));
        return new RuntimeBridgeClient(descriptor, session.token);
    }

    private Session requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Runtime bridge session id must not be blank.");
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException(
                    "Unknown Studio runtime bridge session: " + sessionId
            );
        }
        return session;
    }

    private RuntimeBridgeDescriptor readDescriptor(Path file) {
        try {
            return json.readValue(file.toFile(), RuntimeBridgeDescriptor.class);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not read runtime bridge descriptor: " + file,
                    failure
            );
        }
    }

    private List<RuntimeBridgeDescriptor> safeList(Session session) {
        try {
            return list(session.id);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private Map<String, String> bridgeEnvironment(
            Session session,
            boolean pauseFirstScenario
    ) {
        Map<String, String> environment = new HashMap<>();
        environment.put(ENV_SESSION_DIR, session.directory.toString());
        environment.put(ENV_SESSION_ID, session.id);
        environment.put(ENV_TOKEN, session.token);
        environment.put(ENV_PAUSE_FIRST_SCENARIO, Boolean.toString(pauseFirstScenario));
        return Map.copyOf(environment);
    }

    private void createSessionDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            try {
                Files.setPosixFilePermissions(
                        directory,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
                );
            } catch (UnsupportedOperationException ignored) {
                // Windows and other non-POSIX file systems use their native directory ACLs.
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not create Studio runtime bridge session directory: " + directory,
                    failure
            );
        }
    }

    private void deleteDirectoryIfEmpty(Path directory) {
        try {
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
        }
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final class Session {
        private final String id;
        private final String token;
        private final Path directory;

        private Session(String id, String token, Path directory) {
            this.id = id;
            this.token = token;
            this.directory = directory;
        }
    }
}
