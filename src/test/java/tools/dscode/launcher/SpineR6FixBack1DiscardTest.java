package tools.dscode.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.SpineSession;
import tools.dscode.store.StorePaths;
import tools.dscode.store.StoreScope;
import tools.dscode.store.sql.SqlConnections;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The renderer page discards an empty fork when the browser leaves, and leaves
 * a fork that has a later delta.
 */
class SpineR6FixBack1DiscardTest {
    @TempDir
    Path project;

    private SpineR6PageLive page;

    @AfterEach
    void release() {
        if (page != null) page.close();
        StoreScope.clear();
        System.clearProperty("pkb_journal");
        SqlConnections.closeProject(project);
    }

    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void emptyForkDisappearsAfterThePageLeaves() throws Exception {
        System.setProperty("pkb_journal", "off");
        String session = SpineSession.create(project, "agent-1");
        SpineR6PageLive.finishedRun(project, session, "page-fork");
        page = SpineR6PageLive.open(project);
        page.settleOnMain();
        page.setValue("fork-run", "page-fork");
        page.click("fork");
        page.awaitEquals(SpineR6PageLive.TITLE, "page-fork");
        Path database = StorePaths.sandboxDatabase(project, "page-fork");
        assertTrue(Files.isRegularFile(database), "fork sandbox");
        page.leave();
        page.awaitGone(database);
    }

    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void forkWithAMapDeltaStaysAfterThePageLeaves() throws Exception {
        System.setProperty("pkb_journal", "off");
        String session = SpineSession.create(project, "agent-1");
        SpineR6PageLive.finishedRun(project, session, "page-fork");
        page = SpineR6PageLive.open(project);
        page.settleOnMain();
        page.setValue("fork-run", "page-fork");
        page.click("fork");
        page.awaitEquals(SpineR6PageLive.TITLE, "page-fork");
        page.click("tab-map");
        page.setValue("map-key", "city");
        page.setValue("map-value", "Austin");
        page.click("map-set");
        page.awaitContains("document.getElementById('map-body').textContent", "map set");
        Path database = StorePaths.sandboxDatabase(project, "page-fork");
        SpineR6PageLive.Capture listed = SpineR6PageLive.verb(project, session, "store", "delta", "list",
                "--sandbox=page-fork", "--json");
        assertTrue(listed.out().contains("\"kind\":\"map\""), listed.out() + listed.err());
        assertTrue(listed.out().contains("city"), listed.out() + listed.err());
        page.leave();
        page.awaitStays(database);
        SpineR6PageLive.Capture after = SpineR6PageLive.verb(project, session, "store", "delta", "list",
                "--sandbox=page-fork", "--json");
        assertTrue(after.out().contains("\"kind\":\"map\""), after.out() + after.err());
        assertTrue(after.out().contains("city"), after.out() + after.err());
    }
}
