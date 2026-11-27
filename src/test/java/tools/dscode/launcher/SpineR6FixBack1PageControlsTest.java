package tools.dscode.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.SpineSession;
import tools.dscode.common.agentcontrol.UiLease;
import tools.dscode.store.StorePaths;
import tools.dscode.store.StoreScope;
import tools.dscode.store.sql.SqlConnections;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fork, map, promote, and the branch tree run from the renderer page through
 * the Node server and the {@code /store} bridge.
 */
class SpineR6FixBack1PageControlsTest {
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
    void theRendererDrivesForkMapPromoteAndTree() throws Exception {
        System.setProperty("pkb_journal", "off");
        String session = SpineSession.create(project, "agent-1");
        SpineR6PageLive.finishedRun(project, session, "page-fork");
        assertEquals(0, SpineR6PageLive.verb(project, session, "store", "branch", "fork",
                "--from-run=page-fork", "--name=touch-a").code());
        assertEquals(0, SpineR6PageLive.verb(project, session, "store", "branch", "fork",
                "--from-run=page-fork", "--name=touch-b").code());
        assertEquals(0, SpineR6PageLive.verb(project, session, "store", "branch", "fork",
                "--from-run=page-fork", "--name=absent-c").code());
        assertEquals(0, SpineR6PageLive.verb(project, session, "store", "file", "put",
                "--path=notes.txt", "--text=hello-a", "--sandbox=touch-a").code());
        assertEquals(0, SpineR6PageLive.verb(project, session, "store", "file", "put",
                "--path=notes.txt", "--text=hello-b", "--sandbox=touch-b").code());
        assertEquals(0, SpineR6PageLive.verb(project, session, "store", "file", "put",
                "--path=other.txt", "--text=nope", "--sandbox=absent-c").code());
        List<String> branches = SpineR6PageLive.treeBranches(project, session, "notes.txt");
        assertTrue(branches.contains("touch-a"), branches.toString());
        assertTrue(branches.contains("touch-b"), branches.toString());
        assertFalse(branches.contains("absent-c"), branches.toString());

        page = SpineR6PageLive.open(project);
        page.settleOnMain();
        page.setValue("fork-run", "page-fork");
        page.click("fork");
        page.awaitEquals(SpineR6PageLive.TITLE, "page-fork");
        page.awaitEquals(SpineR6PageLive.ACCENT, SpineR6PageLive.accent("page-fork"));
        assertTrue(Files.isRegularFile(StorePaths.sandboxDatabase(project, "page-fork")), "fork sandbox");

        page.click("tab-map");
        page.awaitContains("document.getElementById('map-body').textContent", "boundRun");
        page.awaitContains("document.getElementById('map-body').textContent", "page-fork");
        page.awaitContains("document.getElementById('map-body').textContent", "boundStep");

        page.setValue("map-key", "city");
        page.setValue("map-value", "Austin");
        page.click("map-set");
        page.awaitContains("document.getElementById('map-body').textContent", "map set");
        SpineR6PageLive.Capture mapped = SpineR6PageLive.verb(project, session, "store", "delta", "list",
                "--sandbox=page-fork", "--json");
        assertEquals(0, mapped.code(), mapped.out() + mapped.err());
        assertTrue(mapped.out().contains("\"kind\":\"map\""), mapped.out());
        assertTrue(mapped.out().contains("city"), mapped.out());

        UiLease.take(project, session, "ui", "agent-2");
        page.setValue("map-key", "blocked");
        page.setValue("map-value", "no");
        page.click("map-set");
        page.awaitContains("document.getElementById('map-body').textContent", "error:");
        SpineR6PageLive.Capture refused = SpineR6PageLive.verb(project, session, "store", "delta", "list",
                "--sandbox=page-fork", "--json");
        assertFalse(refused.out().contains("blocked"), refused.out() + refused.err());
        UiLease.release(project, session, "ui");

        String before = page.parentHead();
        page.click("promote");
        Thread.sleep(1500);
        assertEquals(before, page.parentHead(), "promote with no logs choice");

        page.setValue("promote-logs", "clear");
        page.click("promote");
        page.awaitParentHeadChanges(before);

        page.click("tree");
        page.awaitEquals("String(document.querySelectorAll('#tree-body details').length)", Integer.toString(branches.size()));
        String drawn = page.eval("document.getElementById('tree-body').innerText");
        for (String branch : branches) assertTrue(drawn.contains(branch), drawn);
        assertFalse(drawn.contains("absent-c"), drawn);
    }
}
