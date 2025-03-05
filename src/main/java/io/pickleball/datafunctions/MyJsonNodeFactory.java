package io.pickleball.datafunctions;


import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;


public class MyJsonNodeFactory extends JsonNodeFactory {


    public MyJsonNodeFactory(boolean bigDecimalExact)
    {
       super(bigDecimalExact);
    }


    protected MyJsonNodeFactory()
    {
        super(false);
    }

    @Override
    public MyArrayNode arrayNode() { return new MyArrayNode(this); }

    @Override
    public MyArrayNode arrayNode(int capacity) { return new MyArrayNode(this, capacity); }

    @Override
    public MyObjectNode objectNode() { return new MyObjectNode(this); }

}
