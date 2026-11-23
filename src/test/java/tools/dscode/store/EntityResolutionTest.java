package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code <entity:Type#id>} resolves at runtime, including an id that contains a dot. */
class EntityResolutionTest {
    private static final String OPERATOR = """
            {"name":"Avery","app":"MyQ"}
            """.trim();

    @TempDir
    Path project;

    @AfterEach
    void restore() {
        tools.dscode.store.sql.SqlConnections.closeProject(project);
        System.clearProperty("pkb_store");
        System.clearProperty("pkb_store_project");
        MappingProcessor.resetCommonMaps();
    }

    @Test
    void dottedEntityIdResolvesAtRuntimeAndAFieldOnAPlainIdStillResolves() throws Exception {
        Files.createDirectories(project.resolve("store-entities/Operator"));
        Files.createDirectories(project.resolve("store-entities/Account"));
        Files.write(project.resolve("store-entities/Operator/ae-mgr-all.app.entity"),
                StoreEntities.canonicalFile("Operator", "ae-mgr-all.app", 1, OPERATOR));
        Files.write(project.resolve("store-entities/Account/ada.entity"),
                StoreEntities.canonicalFile("Account", "ada", 1, "{\"name\":\"Ada\"}"));
        System.setProperty("pkb_store", "true");
        System.setProperty("pkb_store_project", project.toString());
        MappingProcessor.resetCommonMaps();

        assertTrue(String.valueOf(StoreEntities.lookup("entity:Operator#ae-mgr-all.app")).contains("Avery"));
        assertEquals("Avery", StoreEntities.lookup("entity:Operator#ae-mgr-all.app.name"));
        assertEquals("Ada", StoreEntities.lookup("entity:Account#ada.name"));
        assertEquals("Avery", new ParsingMap().resolveWholeText("<entity:Operator#ae-mgr-all.app.name>"));

        RunResult result = PickleballEngine.run("""
                Feature: Operator
                  Scenario: Resolve
                    * ASSERT: "<entity:Operator#ae-mgr-all.app.name>" equals "Avery"
                """, EngineOptions.of().project(project));
        assertEquals(Status.PASSED, result.status(), result.message());
        assertFalse(result.message() != null && result.message().contains("<entity:"), result.message());
    }

    @Test
    void entityReferenceStaysLiteralWhenStoreIsOff() throws Exception {
        Files.createDirectories(project.resolve("store-entities/Operator"));
        Files.write(project.resolve("store-entities/Operator/ae-mgr-all.app.entity"),
                StoreEntities.canonicalFile("Operator", "ae-mgr-all.app", 1, OPERATOR));
        System.setProperty("pkb_store", "false");
        System.setProperty("pkb_store_project", project.toString());
        MappingProcessor.resetCommonMaps();
        assertNull(StoreEntities.lookup("entity:Operator#ae-mgr-all.app"));
        assertEquals("<entity:Operator#ae-mgr-all.app.name>",
                new ParsingMap().resolveWholeText("<entity:Operator#ae-mgr-all.app.name>"));
    }
}
