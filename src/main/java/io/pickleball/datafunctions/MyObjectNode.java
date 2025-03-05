package io.pickleball.datafunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

public class MyObjectNode extends ObjectNode {

    public MyObjectNode(JsonNodeFactory nc) {
        super(nc);
    }

    public MyObjectNode(JsonNodeFactory nc, Map<String, JsonNode> children) {
        super(nc, children);
    }
}
