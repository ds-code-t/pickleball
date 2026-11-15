package tools.dscode.dolt.store;

/** One connection is bound to one branch. A transaction never spans two branches. */
public record Binding(String name, boolean primary) {
    public static Binding main() {
        return new Binding("main", true);
    }

    public static Binding sandbox(String name) {
        if (name == null || !name.matches("[a-z0-9][a-z0-9-]{0,47}")) {
            throw new IllegalArgumentException(
                    "error: sandbox name must match [a-z0-9][a-z0-9-]{0,47}");
        }
        return new Binding(name, false);
    }

    public String branch() {
        return primary ? "main" : "sbx-" + name;
    }

    /** JDBC URL path. P1: {@code pickleball/sbx-<name>} binds {@code active_branch()}. */
    public String databasePath() {
        return primary ? "pickleball" : "pickleball/" + branch();
    }

    /** Run-state {@code sandbox} column. Empty on main. */
    public String sandboxColumn() {
        return primary ? "" : name;
    }
}
