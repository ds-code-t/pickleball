package tools.dscode.common.agentsurface;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.PopupSurfaceBridge;
import tools.dscode.common.agentcontrol.VisualKinds;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A popup {@code un*} reads the kind of the ledger row its id names and refuses another kind. */
class PopupRemovalKindTest {
    @TempDir
    Path project;

    @Test
    void popupRowKindComesFromItsLabeledMarkup() throws Exception {
        String labeled = PopupSurfaceBridge.label("<span>Box</span>", "border");
        PopupLedger.Row row = PopupLedger.append(project, "pk", "append", "instructions", "", "", labeled, "",
                PopupSurfaceBridge.sentence("border"));
        String id = Long.toString(row.seq());
        assertEquals("border", AgentSurfaceRouter.popupKind(project, "pk", id));
        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class,
                () -> VisualKinds.requireRemovalKind(id, AgentSurfaceRouter.popupKind(project, "pk", id), "label"));
        assertTrue(wrong.getMessage().contains("is a border, not a label; use unborder"), wrong.getMessage());
        assertDoesNotThrow(() -> VisualKinds.requireRemovalKind(id, AgentSurfaceRouter.popupKind(project, "pk", id), "border"));
    }

    @Test
    void unknownOrUnlabeledPopupRowsAreNotRefused() throws Exception {
        PopupLedger.Row row = PopupLedger.append(project, "pk", "append", "instructions", "", "", "", "", "");
        assertEquals("", AgentSurfaceRouter.popupKind(project, "pk", Long.toString(row.seq())));
        assertEquals("", AgentSurfaceRouter.popupKind(project, "pk", "999"));
        assertEquals("", AgentSurfaceRouter.popupKind(project, "pk", "not-a-seq"));
        assertDoesNotThrow(() -> VisualKinds.requireRemovalKind("999", "", "label"));
    }
}
