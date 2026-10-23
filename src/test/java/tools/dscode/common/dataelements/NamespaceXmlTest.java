package tools.dscode.common.dataelements;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.FileAndDataParsing;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.coredefinitions.DocStringDefinitions;
import tools.dscode.coredefinitions.MappingSteps;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamespaceXmlTest {
    private static final String WINDOWS_PATH = "C:\\Users\\Dan She'll\\page.xml";
    private static final String SPACED_PATH = "C:/Program Files/Pickleball/body.xml";

    private static final String UNPREFIXED = "<order id=\"A7\"><item sku=\"X1\">Pen</item></order>";

    private static final String DECLARED_SOAP = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns1="urn:test">
              <soapenv:Body>
                <ns1:R id="A7" path="%s" spaced="%s">ok</ns1:R>
              </soapenv:Body>
            </soapenv:Envelope>
            """.formatted(WINDOWS_PATH, SPACED_PATH);

    private static final String UNDECLARED_SOAP = """
            <soapenv:Envelope>
              <soapenv:Body>
                <ns1:R id="A7" path="%s" spaced="%s">ok</ns1:R>
              </soapenv:Body>
            </soapenv:Envelope>
            """.formatted(WINDOWS_PATH, SPACED_PATH);

    private static final String UNDECLARED_ROOT = """
            <ns1:R id="A7" path="%s" spaced="%s">ok</ns1:R>
            """.formatted(WINDOWS_PATH, SPACED_PATH).strip();

    @Test
    void unprefixedXmlStaysTheSameTree() throws Exception {
        JsonNode node = new DocStringDefinitions().xmlNode(UNPREFIXED);
        assertEquals("A7", node.path("id").asText());
        assertEquals("X1", node.path("item").path("sku").asText());
        assertEquals("Pen", node.path("item").path("").asText());
        assertFalse(node.toString().contains("urn:pickleball"));
    }

    @Test
    void javaAndGherkinAcceptNamespacePrefixedSoap() throws Exception {
        assertSoap(new DocStringDefinitions().xmlNode(DECLARED_SOAP));
        assertSoap(new DocStringDefinitions().xmlNode(UNDECLARED_SOAP));
        assertSoap(new DocStringDefinitions().xmlObjectNode(DECLARED_SOAP));
        assertSoap(StructuredDataConverter.convert(UNDECLARED_SOAP, DataElementKind.XML_DATA));
        assertSoap(FileAndDataParsing.parseDataString(UNDECLARED_SOAP, "C:/Program Files/Pickleball/response.xml"));
        assertSoap(FileAndDataParsing.parseDataString(DECLARED_SOAP, "C:\\Users\\Dan She'll\\response.xml"));
        assertSoap(mapObject(UNDECLARED_SOAP));
        assertSoap(mapObject(DECLARED_SOAP));

        JsonNode root = new DocStringDefinitions().xmlNode(UNDECLARED_ROOT);
        assertEquals("A7", root.path("id").asText());
        assertEquals("ok", root.path("").asText());
        assertEquals(WINDOWS_PATH, root.path("path").asText());
        assertEquals(SPACED_PATH, root.path("spaced").asText());
    }

    @Test
    void gherkinLeavesSoapTextAloneAndStillResolvesOrdinaryReferences() {
        ParsingMap map = new ParsingMap();
        assertEquals(DECLARED_SOAP, map.resolveWholeText(DECLARED_SOAP));
        assertEquals(UNDECLARED_SOAP, map.resolveWholeText(UNDECLARED_SOAP));
        assertEquals(UNDECLARED_ROOT, map.resolveWholeText(UNDECLARED_ROOT));
        assertTrue(map.resolveWholeText("INFO: body=" + UNDECLARED_SOAP).contains(WINDOWS_PATH));
        assertTrue(map.resolveWholeText("<soapenv:Body/>").contains("soapenv:Body"));

        map.put("who", "Ada");
        assertEquals("hello Ada", map.resolveWholeText("hello <who>"));
        assertEquals(UNPREFIXED, map.resolveWholeText(UNPREFIXED));
    }

    @Test
    void doctypeIsStillRejected() {
        assertThrows(DataQueryException.class, () -> new DocStringDefinitions().xmlNode(
                "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///C:/Program Files/secret\">]><ns1:R>&xxe;</ns1:R>"));
    }

    private static void assertSoap(Object value) {
        JsonNode node = value instanceof JsonNode json ? json : FileAndDataParsing.JSON_MAPPER.valueToTree(value);
        JsonNode row = node.path("Body").path("R");
        assertEquals("A7", row.path("id").asText(), node.toString());
        assertEquals("ok", row.path("").asText(), node.toString());
        assertEquals(WINDOWS_PATH, row.path("path").asText(), node.toString());
        assertEquals(SPACED_PATH, row.path("spaced").asText(), node.toString());
    }

    private static JsonNode mapObject(String xml) throws Exception {
        Method parseData = MappingSteps.class.getDeclaredMethod("parseData", String.class, String.class);
        parseData.setAccessible(true);
        return (JsonNode) parseData.invoke(null, xml, "xml");
    }
}
