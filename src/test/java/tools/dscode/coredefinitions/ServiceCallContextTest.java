package tools.dscode.coredefinitions;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

class ServiceCallContextTest {

    @Test
    void initializationCreatesLocalAliasesAndNamedEntriesInBothMaps() {
        NodeMap scenarioMap = new NodeMap();
        NodeMap runMap = new NodeMap(MapConfigurations.MapType.RUN_MAP);

        ObjectNode serviceCall = ServiceCallContext.initialize(
            scenarioMap,
            runMap,
            "readItemCall",
            "Read an item through REST"
        );

        ObjectNode request = ServiceCallContext.request(scenarioMap);
        ObjectNode response = ServiceCallContext.response(scenarioMap);

        request.put("endpoint", "http://127.0.0.1:8765/api/items/73");
        request.put("method", "GET");
        response.put("statusCode", 200);

        assertSame(request, scenarioMap.getRoot().get("request"));
        assertSame(response, scenarioMap.getRoot().get("response"));
        assertSame(request, serviceCall.get("request"));
        assertSame(response, serviceCall.get("response"));

        ArrayNode localCalls = (ArrayNode) scenarioMap
            .getRoot()
            .get("readItemCall");
        ArrayNode runCalls = (ArrayNode) runMap
            .getRoot()
            .get("readItemCall");

        assertEquals(1, localCalls.size());
        assertEquals(1, runCalls.size());
        assertSame(serviceCall, localCalls.get(0));
        assertSame(serviceCall, runCalls.get(0));
        assertEquals(
            200,
            runCalls.get(0).path("response").path("statusCode").asInt()
        );
    }

    @Test
    void localDirectAndQualifiedResponsePathsResolveTheSameValue() {
        NodeMap scenarioMap = new NodeMap();
        NodeMap runMap = new NodeMap(MapConfigurations.MapType.RUN_MAP);

        ServiceCallContext.initialize(
            scenarioMap,
            runMap,
            "readItemCall",
            "Read an item through REST"
        );

        ServiceCallContext.response(scenarioMap).put("statusCode", 200);

        assertEquals(200, scenarioMap.get("response.statusCode"));
        assertEquals(200, scenarioMap.get("readItemCall.response.statusCode"));
        assertEquals(200, runMap.get("readItemCall.response.statusCode"));
    }

    @Test
    void repeatedNamesAppendSeparateCompleteCalls() {
        NodeMap runMap = new NodeMap(MapConfigurations.MapType.RUN_MAP);
        NodeMap firstScenarioMap = new NodeMap();
        NodeMap secondScenarioMap = new NodeMap();

        ServiceCallContext.initialize(
            firstScenarioMap,
            runMap,
            "readItemCall",
            "Read an item through REST"
        );
        ServiceCallContext.initialize(
            secondScenarioMap,
            runMap,
            "readItemCall",
            "Read an item through REST"
        );

        ArrayNode history = (ArrayNode) runMap.getRoot().get("readItemCall");

        assertEquals(2, history.size());
        assertNotSame(
            history.get(0).get("request"),
            history.get(1).get("request")
        );
        assertNotSame(
            history.get(0).get("response"),
            history.get(1).get("response")
        );
    }

    @Test
    void responseReplacementMutatesEverySharedReference() {
        NodeMap scenarioMap = new NodeMap();
        NodeMap runMap = new NodeMap(MapConfigurations.MapType.RUN_MAP);

        ObjectNode serviceCall = ServiceCallContext.initialize(
            scenarioMap,
            runMap,
            "readItemCall",
            "Read an item through REST"
        );

        ObjectNode sharedResponse = ServiceCallContext.response(scenarioMap);
        ObjectNode extractedResponse = MAPPER.createObjectNode();
        extractedResponse.put("statusCode", 200);
        extractedResponse.put("method", "GET");

        ServiceCallContext.replaceContents(sharedResponse, extractedResponse);

        assertSame(sharedResponse, serviceCall.get("response"));
        assertEquals(200, serviceCall.path("response").path("statusCode").asInt());
        assertEquals("GET", serviceCall.path("response").path("method").asText());
        assertEquals(200, scenarioMap.get("readItemCall.response.statusCode"));
        assertEquals(200, runMap.get("readItemCall.response.statusCode"));
    }

    @Test
    void nonArrayRunMapCollisionIsRejected() {
        NodeMap scenarioMap = new NodeMap();
        NodeMap runMap = new NodeMap(MapConfigurations.MapType.RUN_MAP);
        runMap.getRoot().put("readItemCall", "reserved value");

        assertThrows(
            IllegalStateException.class,
            () -> ServiceCallContext.initialize(
                scenarioMap,
                runMap,
                "readItemCall",
                "Read an item through REST"
            )
        );
    }

    @Test
    void reservedLocalNamesAreRejected() {
        NodeMap scenarioMap = new NodeMap();
        NodeMap runMap = new NodeMap(MapConfigurations.MapType.RUN_MAP);

        assertThrows(
            IllegalArgumentException.class,
            () -> ServiceCallContext.initialize(
                scenarioMap,
                runMap,
                "response",
                "Read an item through REST"
            )
        );
    }
}
