package tools.dscode.engine.match;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Hosts loaded once per top-level {@link tools.dscode.engine.PickleballEngine} run.
 * The engine does not name a language. A missing host fails the step.
 */
public final class ForeignStepHosts {
    private static final Object LOCK = new Object();
    private static final ThreadLocal<Path> PROJECT = new ThreadLocal<>();
    private static List<ForeignStepHost> installed;
    private static List<ForeignStepHost> loaded;
    private static int depth;

    private ForeignStepHosts() {
    }

    /** Test seam. A null list returns to ServiceLoader on the next begin. */
    public static void install(List<ForeignStepHost> hosts) {
        synchronized (LOCK) {
            installed = hosts == null ? null : List.copyOf(hosts);
            if (depth == 0) loaded = null;
        }
    }

    public static void begin(Path project) {
        synchronized (LOCK) {
            if (loaded == null) loaded = resolve();
            depth++;
        }
        PROJECT.set(project == null ? Path.of("").toAbsolutePath().normalize() : project.toAbsolutePath().normalize());
    }

    public static void end() {
        PROJECT.remove();
        List<ForeignStepHost> closing = null;
        synchronized (LOCK) {
            if (depth > 0) depth--;
            if (depth == 0 && loaded != null) {
                closing = loaded;
                loaded = null;
            }
        }
        if (closing == null) return;
        for (ForeignStepHost host : closing) {
            if (host instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // Closing a host must not hide the step result.
                }
            }
        }
    }

    public static boolean started() {
        synchronized (LOCK) {
            return depth > 0;
        }
    }

    public static Path project() {
        Path project = PROJECT.get();
        if (project != null) return project;
        String property = System.getProperty("pkb_project");
        if (property != null && !property.isBlank()) return Path.of(property).toAbsolutePath().normalize();
        return null;
    }

    public static boolean supports(String language) {
        String token = language == null ? "" : language;
        for (ForeignStepHost host : hosts()) {
            if (host.supports(token)) return true;
        }
        return false;
    }

    public static Object invoke(String definitionId, String stepText, List<String> args, String hostName) {
        String token = hostName == null || hostName.isBlank() ? "java" : hostName;
        for (ForeignStepHost host : hosts()) {
            if (host.supports(token)) return host.invoke(definitionId, stepText, args);
        }
        throw new IllegalStateException("error: no step host for " + token
                + "\nfix: register a ForeignStepHost that supports " + token);
    }

    private static List<ForeignStepHost> hosts() {
        synchronized (LOCK) {
            if (loaded == null) loaded = resolve();
            return loaded;
        }
    }

    private static List<ForeignStepHost> resolve() {
        if (installed != null) return new ArrayList<>(installed);
        List<ForeignStepHost> found = new ArrayList<>();
        ServiceLoader.load(ForeignStepHost.class, ForeignStepHost.class.getClassLoader()).forEach(found::add);
        return found;
    }
}
