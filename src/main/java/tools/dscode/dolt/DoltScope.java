package tools.dscode.dolt;

import tools.dscode.dolt.store.Binding;

import java.util.List;

/**
 * One sandbox per JVM. {@code --sandbox} wins over {@code pkb_dolt_sandbox},
 * then {@code PKB_DOLT_SANDBOX}, otherwise {@code main}. A second sandbox fails.
 * {@code main} is not sticky until a sandbox is bound.
 */
public final class DoltScope {
    private static Binding bound;

    private DoltScope() {
    }

    public static Binding current() {
        Binding existing = bound;
        if (existing != null) return existing;
        String requested = propertyOrEnv();
        if (requested == null || requested.isBlank() || "main".equals(requested)) return Binding.main();
        return bind(Binding.sandbox(requested));
    }

    public static Binding bindCommand(List<String> tokens) {
        String flag = flag(tokens);
        if (flag != null) {
            if (flag.isBlank() || "main".equals(flag)) return bind(Binding.main());
            return bind(Binding.sandbox(flag));
        }
        return current();
    }

    public static Binding bind(Binding next) {
        Binding chosen = next == null ? Binding.main() : next;
        synchronized (DoltScope.class) {
            if (chosen.primary()) {
                if (bound != null && !bound.primary()) {
                    throw new IllegalStateException("this JVM is already bound to sandbox " + label(bound)
                            + "\nfix: use one sandbox per process");
                }
                return bound == null ? Binding.main() : bound;
            }
            if (bound != null && !bound.branch().equals(chosen.branch())) {
                throw new IllegalStateException("this JVM is already bound to sandbox " + label(bound)
                        + "\nfix: use one sandbox per process");
            }
            bound = chosen;
            return bound;
        }
    }

    /** Tests share a JVM. Production commands do not call this. */
    public static void clear() {
        synchronized (DoltScope.class) {
            bound = null;
        }
    }

    public static String label(Binding binding) {
        if (binding == null || binding.primary()) return "main";
        return binding.name();
    }

    private static String propertyOrEnv() {
        String property = System.getProperty("pkb_dolt_sandbox");
        if (property != null && !property.isBlank()) return property.trim();
        String env = System.getenv("PKB_DOLT_SANDBOX");
        if (env != null && !env.isBlank()) return env.trim();
        return null;
    }

    private static String flag(List<String> tokens) {
        if (tokens == null) return null;
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token == null) continue;
            if (token.startsWith("--sandbox=")) return token.substring("--sandbox=".length()).trim();
            if ("--sandbox".equals(token) && index + 1 < tokens.size()) return tokens.get(index + 1).trim();
        }
        return null;
    }
}
