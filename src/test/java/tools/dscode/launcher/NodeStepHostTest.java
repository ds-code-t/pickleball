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

class NodeStepHostTest {
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
    void resolveTextReturnsTheJavaResult() throws Exception {
        String id = insert("js", "alpha", "glue/js/alpha.js", """
                defineStep("alpha", function () {
                  return resolveText("alpha");
                });
                """, "h1");
        host = start();
        Object value = host.invoke(id, "alpha", List.of());
        assertEquals("alpha", value);
        assertTrue(host.calls().contains("resolveText"), host.calls().toString());
    }

    @Test
    void secretReturnsTheMaskAndAReferenceIsKept() throws Exception {
        String masked = insert("js", "secret", "glue/js/secret.js", """
                defineStep("secret", function () {
                  return secret("password");
                });
                """, "h1");
        String reference = insert("js", "reference", "glue/js/reference.js", """
                defineStep("reference", function () {
                  return secret("<db.password>");
                });
                """, "h1");
        host = start();
        assertEquals("~MASK;", host.invoke(masked, "secret", List.of()));
        assertEquals("<db.password>", host.invoke(reference, "reference", List.of()));
    }

    @Test
    void unknownFnFailsTheStep() throws Exception {
        String id = insert("js", "bad", "glue/js/bad.js", """
                defineStep("bad", function () {
                  return call("nope", []);
                });
                """, "h1");
        host = start();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> host.invoke(id, "bad", List.of()));
        assertTrue(failure.getMessage().contains("unknown fn"), failure.getMessage());
    }

    @Test
    void bareImportFailsAtLoad() throws Exception {
        String id = insert("js", "bare", "glue/js/bare.js", """
                import x from "left-pad";
                defineStep("bare", function () { return x; });
                """, "h1");
        host = start();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> host.invoke(id, "bare", List.of()));
        assertTrue(failure.getMessage().contains("error: bare import is a rebuild"), failure.getMessage());
        assertTrue(failure.getMessage().contains("fix: add the package to workbench-ui and rebuild"), failure.getMessage());
    }

    @Test
    void relativeImportLoadsTheHydratedRow() throws Exception {
        insertFile("glue/js/helper.js", """
                export function twice(n) { return String(Number(n) * 2); }
                """, "helper");
        String id = insert("js", "double", "glue/js/double.js", """
                import { twice } from "./helper.js";
                defineStep("double", function (n) { return twice(n); });
                """, "h1");
        host = start();
        assertEquals("4", host.invoke(id, "double", List.of("2")));
    }

    @Test
    void aChangedContentHashLoadsAgain() throws Exception {
        String id = insert("js", "reload", "glue/js/reload.js", """
                defineStep("reload", function () { return resolveText("one"); });
                """, "h1");
        host = start();
        assertEquals("one", host.invoke(id, "reload", List.of()));
        replace("glue/js/reload.js", id, """
                defineStep("reload", function () { return resolveText("two"); });
                """, "h2");
        assertEquals("two", host.invoke(id, "reload", List.of()));
    }

    private NodeStepHost start() {
        ForeignStepHosts.begin(project);
        return new NodeStepHost();
    }

    private String insert(String language, String pattern, String path, String source, String hash) throws Exception {
        insertFile(path, source, hash);
        String id = path.replace('/', '-');
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement("""
                     INSERT INTO step_definitions(
                         definition_id, language, pattern, method_name, source_path, source_line,
                         content_hash, hydrated_at, removed, host)
                     VALUES (?, ?, ?, 'defineStep', ?, 1, ?, '2026-11-26T00:00:00Z', 0, ?)
                     """)) {
            statement.setString(1, id);
            statement.setString(2, language);
            statement.setString(3, pattern);
            statement.setString(4, path);
            statement.setString(5, hash);
            statement.setString(6, language);
            statement.executeUpdate();
        }
        return id;
    }

    private void insertFile(String path, String source, String hash) throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement("""
                     INSERT INTO files(
                         path, content_hash, raw_content, mtime, source_type, last_hydrated_at,
                         last_synced_hash, norm_path)
                     VALUES (?, ?, ?, 0, 'glue', '2026-11-26T00:00:00Z', ?, ?)
                     """)) {
            statement.setString(1, path);
            statement.setString(2, hash);
            statement.setBytes(3, source.getBytes(StandardCharsets.UTF_8));
            statement.setString(4, hash);
            statement.setString(5, path);
            statement.executeUpdate();
        }
    }

    private void replace(String path, String id, String source, String hash) throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project)) {
            try (PreparedStatement statement = database.connection().prepareStatement("""
                    UPDATE files SET raw_content = ?, content_hash = ?, last_synced_hash = ?
                    WHERE path = ?
                    """)) {
                statement.setBytes(1, source.getBytes(StandardCharsets.UTF_8));
                statement.setString(2, hash);
                statement.setString(3, hash);
                statement.setString(4, path);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = database.connection().prepareStatement("""
                    UPDATE step_definitions SET content_hash = ? WHERE definition_id = ?
                    """)) {
                statement.setString(1, hash);
                statement.setString(2, id);
                statement.executeUpdate();
            }
        }
    }
}
