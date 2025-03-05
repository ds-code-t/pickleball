package io.pickleball.datafunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;

public class MyArrayNode extends ArrayNode {

    public MyArrayNode(MyJsonNodeFactory nf) {
        super(nf);
    }

    public MyArrayNode(MyJsonNodeFactory nf, int capacity)  {
        super(nf, capacity);
    }

    public MyArrayNode(MyJsonNodeFactory nf, List<JsonNode> children) {
        super(nf, children);
    }



}
