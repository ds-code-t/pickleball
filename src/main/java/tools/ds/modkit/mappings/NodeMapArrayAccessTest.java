//package tools.ds.modkit.mappings;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import org.junit.jupiter.api.Test;
////
//import static org.junit.jupiter.api.Assertions.*;
//
//public class NodeMapArrayAccessTest {
//
//    private static final ObjectMapper MAPPER = new ObjectMapper();
//
//    @Test
//    void testNestedArrayTreatAsObject() {
//        NodeMap map = new NodeMap();
//
//        // Put a nested array element
//        map.put("nums.arrayA[0].tag1", "AAA");
//
//        // Query without specifying [0], should use [-1] implicitly
//        assertEquals("AAA", map.get("nums.arrayA.tag1"));
//    }
//
//    @Test
//    void testArrayOfScalars() {
//        NodeMap map = new NodeMap();
//
//        map.put("nums.arrayB[0]", "BBB");
//        map.put("nums.arrayB[1]", "CCC");
//
//        // "nums.arrayB" without index should return last element ("CCC")
//        assertEquals("CCC", map.get("nums.arrayB"));
//    }
//
//    @Test
//    void testTopLevelArrayAccess() {
//        NodeMap map = new NodeMap();
//
//        map.put("logs", MAPPER.createObjectNode().put("msg", "first"));
//        map.put("logs", MAPPER.createObjectNode().put("msg", "second"));
//
//        // "logs" without index → last element
//        assertEquals("second", map.get("logs").get("msg"));
//    }
//
//    @Test
//    void testEmptyArrayReturnsNull() {
//        NodeMap map = new NodeMap();
//
//        // no array created yet
//        assertNull(map.get("nums.arrayX.tagY"));
//    }
//
//    @Test
//    void testOutOfRangeIndexReturnsNull() {
//        NodeMap map = new NodeMap();
//
//        map.put("nums.arrayC[0]", "one");
//        // request arrayC[5] which doesn't exist
//        assertNull(map.get("nums.arrayC[5]"));
//    }
//}
