package tools.dscode.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.match.ForeignStepHosts;
import tools.dscode.store.StoreDatabase;
import tools.dscode.store.StoreScope;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeStepHostTsTest {
    @TempDir
    Path project;

    private NodeStepHost host;

    @AfterEach
    void clearScope() {
        if (host != null) host.close();
        ForeignStepHosts.end();
        ForeignStepHosts.install(null);
        StoreScope.clear();
    }

    @Test
    void erasableTypeScriptRuns() throws Exception {
        String id = insert("glue/js/typed.ts", """
                defineStep("typed", function (): string {
                  const label: string = "beta";
                  return resolveText(label);
                });
                """);
        host = start();
        assertEquals("beta", host.invoke(id, "typed", List.of()));
    }

    @Test
    void enumFailsAtLoad() throws Exception {
        String id = insert("glue/js/enum.ts", """
                enum Color { Red }
                defineStep("e", function () { return "no"; });
                """);
        host = start();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> host.invoke(id, "e", List.of()));
        assertTrue(failure.getMessage().contains("error:"), failure.getMessage());
        assertTrue(failure.getMessage().contains("fix: keep the TypeScript erasable"), failure.getMessage());
    }

    @Test
    void namespaceFailsAtLoad() throws Exception {
        String id = insert("glue/js/namespace.ts", """
                namespace Box { export const n = 1; }
                defineStep("n", function () { return "no"; });
                """);
        host = start();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> host.invoke(id, "n", List.of()));
        assertTrue(failure.getMessage().contains("fix: keep the TypeScript erasable"), failure.getMessage());
    }

    @Test
    void parameterPropertyFailsAtLoad() throws Exception {
        String id = insert("glue/js/param.ts", """
                class Holder { constructor(public name: string) {} }
                defineStep("p", function () { return "no"; });
                """);
        host = start();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> host.invoke(id, "p", List.of()));
        assertTrue(failure.getMessage().contains("fix: keep the TypeScript erasable"), failure.getMessage());
    }

    private NodeStepHost start() {
        ForeignStepHosts.begin(project);
        return new NodeStepHost();
    }

    private String insert(String path, String source) throws Exception {
        String id = path.replace('/', '-');
        try (StoreDatabase database = StoreDatabase.open(project)) {
            try (PreparedStatement statement = database.connection().prepareStatement("""
                    INSERT INTO files(
                        path, content_hash, raw_content, mtime, source_type, last_hydrated_at,
                        last_synced_hash, norm_path)
                    VALUES (?, 'h1', ?, 0, 'glue', '2026-11-26T00:00:00Z', 'h1', ?)
                    """)) {
                statement.setString(1, path);
                statement.setBytes(2, source.getBytes(StandardCharsets.UTF_8));
                statement.setString(3, path);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = database.connection().prepareStatement("""
                    INSERT INTO step_definitions(
                        definition_id, language, pattern, method_name, source_path, source_line,
                        content_hash, hydrated_at, removed, host)
                    VALUES (?, 'ts', 'typed', 'defineStep', ?, 1, 'h1', '2026-11-26T00:00:00Z', 0, 'ts')
                    """)) {
                statement.setString(1, id);
                statement.setString(2, path);
                statement.executeUpdate();
            }
        }
        return id;
    }
}
