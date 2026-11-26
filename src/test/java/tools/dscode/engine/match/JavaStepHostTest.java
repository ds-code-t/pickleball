package tools.dscode.engine.match;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.StoreDatabase;
import tools.dscode.store.StoreScope;
import tools.dscode.store.StoreStepCatalog;

import java.nio.file.Path;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaStepHostTest {
    @TempDir
    Path project;

    @AfterEach
    void clearScope() {
        ForeignStepHosts.end();
        ForeignStepHosts.install(null);
        StoreScope.clear();
    }

    @Test
    void hostReturnsTheCapturedGroup() throws Exception {
        String path = "src/test/java/tools/dscode/engine/match/JavaStepHostFixture.java";
        String pattern = "^I count (\\d+)$";
        String id = StoreStepCatalog.definitionId("java", pattern, path, 8);
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement("""
                     INSERT INTO step_definitions(
                         definition_id, language, pattern, method_name, source_path, source_line,
                         content_hash, hydrated_at, removed, host)
                     VALUES (?, 'java', ?, 'count', ?, 8, 'hash', '2026-11-26T00:00:00Z', 0, 'java')
                     """)) {
            statement.setString(1, id);
            statement.setString(2, pattern);
            statement.setString(3, path);
            statement.executeUpdate();
        }
        ForeignStepHosts.begin(project);
        Object value = new JavaStepHost().invoke(id, "I count 7", null);
        assertEquals("7", value);
    }
}
