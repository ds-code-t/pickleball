package tools.dscode.workbench.player;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GherkinReferenceTest {
    @Test
    void parsesMixedRunTableRows() {
        List<GherkinReference> refs = GherkinReference.parse("When RUN", List.of(
                "| RunType            | Run Tags         | customerName |",
                "| COMPONENT SCENARIO | %save_customer   | Ava          |",
                "| SERVICE CALL       | %health-full-url |              |"
        ));
        assertEquals(2, refs.size());
        assertEquals(GherkinReference.Kind.COMPONENT, refs.get(0).kind());
        assertEquals("%save_customer", refs.get(0).selector());
        assertEquals(GherkinReference.Kind.SERVICE_CALL, refs.get(1).kind());
    }

    @Test
    void parsesDataFileReference() {
        GherkinReference ref = GherkinReference.dataFile(
                "* , save \"<data:/files/customerPayload>\" Data as \"payload\""
        ).orElseThrow();
        assertEquals(GherkinReference.Kind.DATA_FILE, ref.kind());
        assertEquals("files/customerPayload", ref.selector());
    }
}