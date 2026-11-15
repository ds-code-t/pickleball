package tools.dscode.dolt.store;

import tools.dscode.dolt.server.DoltServer;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/** Opens one bound session. Schema is created and committed on main before the session is returned. */
public final class DoltStore implements AutoCloseable {
    private final Path project;
    private final Binding binding;
    private final DoltSession session;

    private DoltStore(Path project, Binding binding, DoltSession session) {
        this.project = project;
        this.binding = binding;
        this.session = session;
    }

    public static DoltStore open(Path project, Binding binding) throws IOException, SQLException {
        Path root = project.toAbsolutePath().normalize();
        DoltServer.ensure(root);
        try (DoltSession schema = DoltConnections.borrow(root, Binding.main())) {
            DoltDialect.ensureSchema(schema);
        }
        return new DoltStore(root, binding, DoltConnections.borrow(root, binding));
    }

    public Path project() {
        return project;
    }

    public Binding binding() {
        return binding;
    }

    public DoltSession session() {
        return session;
    }

    /** Creates {@code sbx-<name>} from the latest commit on main. */
    public void createBranch(String sandboxName) throws SQLException {
        Binding check = Binding.sandbox(sandboxName);
        if (!binding.primary()) throw new SQLException("error: create a sandbox branch from main");
        session.script("CALL DOLT_BRANCH('" + check.branch() + "')");
    }

    @Override
    public void close() {
        session.close();
    }
}
