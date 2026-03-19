//package tools.dscode.common.dataoperations;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.fasterxml.jackson.databind.node.ObjectNode;
//
//import java.util.List;
//
//public final class TableQueriesDemo {
//
//    private static final ObjectMapper MAPPER = new ObjectMapper();
//
//    public static void main(String[] args) throws Exception {
//        ObjectNode root = (ObjectNode) tildeReader.readTree("""
//            {
//              "tableA": {
//                "Data Row": [
//                  {
//                    "name": "Alice",
//                    "age": 30,
//                    "active": true
//                  },
//                  {
//                    "name": "Bob",
//                    "age": 41,
//                    "active": false
//                  }
//                ]
//              },
//              "wrapper": {
//                "nestedTable": {
//                  "Data Row": [
//                    {
//                      "city": "Phoenix",
//                      "zip": 85001
//                    }
//                  ]
//                },
//                "extraCells": {
//                  "Data Cell": [
//                    { "note": "hello" },
//                    { "flag": true },
//                    123,
//                    null
//                  ]
//                }
//              },
//              "metadata": {
//                "source": "import1"
//              }
//            }
//            """);
//
//        printInput(root);
//
//        printJsonCase(
//                "findRows(root)",
//                """
//                Expected:
//                - flattened contents of every array property named "Data Row"
//                """,
//                TableQueries.findRows(root)
//        );
//
//        printJsonCase(
//                "findCells(root)",
//                """
//                Expected:
//                - explicit cells from every "Data Cell" array first
//                - then scalar fields extracted from rows found under "Data Row"
//                """,
//                TableQueries.findCells(root)
//        );
//
//        printStringCase(
//                "findCellValues(root)",
//                """
//                Expected:
//                - List<String>
//                - null or missing becomes empty string
//                - likely:
//                  hello
//                  true
//                  123
//
//                  Alice
//                  30
//                  true
//                  Bob
//                  41
//                  false
//                  Phoenix
//                  85001
//                """,
//                TableQueries.findCellValues(root)
//        );
//
//        printStringCase(
//                "findHeaders(root)",
//                """
//                Expected:
//                - keys from the first row only
//                - likely:
//                  name
//                  age
//                  active
//                """,
//                TableQueries.findHeaders(root)
//        );
//    }
//
//    private static void printInput(ObjectNode root) throws Exception {
//        System.out.println("==================================================");
//        System.out.println("INPUT JSON");
//        System.out.println("==================================================");
//        System.out.println(pretty(root));
//        System.out.println();
//    }
//
//    private static void printJsonCase(String label, String expected, List<JsonNode> actual) throws Exception {
//        System.out.println("==================================================");
//        System.out.println(label);
//        System.out.println("==================================================");
//        System.out.println(expected.strip());
//        System.out.println();
//        System.out.println("Actual:");
//        if (actual == null || actual.isEmpty()) {
//            System.out.println("[]");
//        } else {
//            for (int i = 0; i < actual.size(); i++) {
//                System.out.println("[" + i + "]");
//                System.out.println(pretty(actual.get(i)));
//            }
//        }
//        System.out.println();
//    }
//
//    private static void printStringCase(String label, String expected, List<String> actual) {
//        System.out.println("==================================================");
//        System.out.println(label);
//        System.out.println("==================================================");
//        System.out.println(expected.strip());
//        System.out.println();
//        System.out.println("Actual:");
//        if (actual == null || actual.isEmpty()) {
//            System.out.println("[]");
//        } else {
//            for (int i = 0; i < actual.size(); i++) {
//                System.out.println("[" + i + "] " + actual.get(i));
//            }
//        }
//        System.out.println();
//    }
//
//    private static String pretty(JsonNode node) throws Exception {
//        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
//    }
//
//    private TableQueriesDemo() {}
//}