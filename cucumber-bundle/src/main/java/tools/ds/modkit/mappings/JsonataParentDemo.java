package tools.ds.modkit.mappings;
import com.api.jsonata4java.expressions.EvaluateException;
import com.api.jsonata4java.expressions.Expressions;
import com.api.jsonata4java.expressions.ParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

public class JsonataParentDemo {

    public static String getParentPath(String path) {
        return path.trim() + ".{ 'p': % }.p";
    }

    private static final ObjectMapper M = new ObjectMapper();

    private static void run(JsonNode root, String q) {
        try {
            String pq = getParentPath(q);
            JsonNode out = null;
            try {
                out = Expressions.parse(pq).evaluate(root);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            System.out.println(q + "  =>  " + (out == null ? "null" : out.toPrettyString()));
        } catch (ParseException | EvaluateException e) {
            System.out.println(q + "  =>  ERROR: " + e.getMessage());
        }
    }

    private static void runDistinct(JsonNode root, String q) {
        try {
            String pq = "(" + getParentPath(q) + ")";
            JsonNode out = null;
            try {
                out = Expressions.parse("$distinct(" + pq + ")").evaluate(root);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            System.out.println(q + "  =>  $distinct(parents)  =>  " + (out == null ? "null" : out.toPrettyString()));
        } catch (ParseException | EvaluateException e) {
            System.out.println(q + "  =>  ERROR: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        // Build a simple nested JSON tree
        ObjectNode root = M.createObjectNode();
        ObjectNode a = root.putObject("a");
        ObjectNode b = a.putObject("b");
        b.put("c", 123);
        ArrayNode arr = a.putArray("arr");
        arr.addObject().put("x", 1).put("name", "first");
        arr.addObject().put("x", 2).put("name", "second");
        arr.addObject().put("y", 9);
        ObjectNode nested = a.putObject("nested");
        ArrayNode deep = nested.putArray("deep");
        deep.addObject().put("x", 3);
        deep.addObject().put("x", 4);

        // Try a variety of paths: direct, indexed, wildcards, filters, and recursive descent
        String[] queries = {
                "a.b.c",             // parent should be the 'b' object
                "a.arr[1].x",        // parent is the 2nd array element
                "a.arr[*].x",        // parents are all elements having 'x'
                "a.arr[x > 1].x",    // parents where x > 1
                "a.arr[0]",          // parent of the 1st element -> the array 'arr'
                "a.nested.deep[*].x",// parents are elements in 'deep' that have 'x'
                "a.**.x",            // recursive: all 'x' under 'a'
                "**.x"               // recursive: all 'x' anywhere
        };

        for (String q : queries) run(root, q);

        // For recursive/wildcard cases you may want distinct parents:
        runDistinct(root, "a.**.x");
        runDistinct(root, "**.x");
    }
}
