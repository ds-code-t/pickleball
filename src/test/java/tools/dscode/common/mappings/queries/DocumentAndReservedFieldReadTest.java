package tools.dscode.common.mappings.queries;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.NodeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

class DocumentAndReservedFieldReadTest {

    @Test
    void savedSingleLetterTokensRoundTrip() {
        NodeMap map = new NodeMap();
        map.put("m", "else");
        map.put("i", "eye");
        map.put("q", "yes");

        assertEquals("else", map.get("m"));
        assertEquals("eye", map.get("i"));
        assertEquals("yes", map.get("q"));
        assertEquals("`m`[][-1]", Tokenized.preprocessReadQuery("m"));
        assertEquals("`i`[][-1]", Tokenized.preprocessReadQuery("i"));
        assertEquals("q[][-1]", Tokenized.preprocessReadQuery("q"));
    }

    @Test
    void documentIndexSelectsThatRecord() {
        ObjectNode wrapper = MAPPER.createObjectNode();
        ArrayNode customers = wrapper.putArray("customers");
        customers.addObject().put("name", "Ava").put("city", "Phoenix");
        customers.addObject().put("name", "Ben").put("city", "Tempe");

        assertEquals("Ava", Tokenized.readDocument(wrapper, "customers #1.name"));
        assertEquals("Phoenix", Tokenized.readDocument(wrapper, "customers #1.city"));
        assertEquals("Ava", Tokenized.readDocument(wrapper, "customers[0].name"));
        assertEquals("Ben", Tokenized.readDocument(wrapper, "customers #2.name"));
        assertEquals("Tempe", Tokenized.readDocument(wrapper, "customers[1].city"));
        assertEquals(
                "customers[0].name",
                Tokenized.preprocessReadQuery("customers[0].name")
        );
    }

    @Test
    void aBareKeyIsNotAStepReturnAddress() {
        assertEquals(false, tools.dscode.common.mappings.MappingProcessor.isStepReturnAddress("key"));
        assertEquals(false, tools.dscode.common.mappings.MappingProcessor.isStepReturnAddress("feature.scenario"));
        assertEquals(true, tools.dscode.common.mappings.MappingProcessor.isStepReturnAddress("feature.scenario.step"));
    }
}
