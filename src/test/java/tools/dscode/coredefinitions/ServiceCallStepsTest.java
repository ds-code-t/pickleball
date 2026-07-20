package tools.dscode.coredefinitions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

class ServiceCallStepsTest {

    @Test
    void parsesCommonScalarAndContainerValues() {
        assertTrue(ServiceCallSteps.parseValue("true").asBoolean());
        assertEquals(25, ServiceCallSteps.parseValue("25").asInt());
        assertEquals("abc", ServiceCallSteps.parseValue("abc").asText());
        assertTrue(ServiceCallSteps.parseValue("{\"enabled\":true}").isObject());
        assertTrue(ServiceCallSteps.parseValue("[1,2]").isArray());
    }

    @Test
    void restoresEscapedXmlDelimitersAfterTemplateResolution() {
        assertEquals(
                "<calc:Add><calc:left>5</calc:left></calc:Add>",
                ServiceCallSteps.restoreXmlDelimiters(
                        "~[~calc:Add~]~~[~calc:left~]~5~[~/calc:left~]~~[~/calc:Add~]~"
                )
        );
    }

    @Test
    void deepMergePreservesExistingNestedFields() {
        ObjectNode target = MAPPER.createObjectNode();
        ObjectNode targetHeaders = MAPPER.createObjectNode();
        targetHeaders.put("X-Existing", "one");
        target.set("headers", targetHeaders);

        ObjectNode source = MAPPER.createObjectNode();
        ObjectNode sourceHeaders = MAPPER.createObjectNode();
        sourceHeaders.put("X-New", "two");
        source.set("headers", sourceHeaders);
        source.put("method", "POST");

        ServiceCallSteps.deepMerge(target, source);

        JsonNode headers = target.get("headers");
        assertEquals("one", headers.get("X-Existing").asText());
        assertEquals("two", headers.get("X-New").asText());
        assertEquals("POST", target.get("method").asText());
        assertFalse(target.has("missing"));
    }
}
