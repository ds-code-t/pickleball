//package io.pickleball.datafunctions;
//
//import java.io.IOException;
//
//import java.io.IOException;
//import java.io.IOException;
//
//public class DataNodeTest {
//    public static void main(String[] args) {
//        try {
//            testJsonParsing();
//            testXmlParsing();
//            testYamlParsing();
//            testComplexPathAccess();
//            testFormatConversion();
//            testEdgeCases();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }
//
//    private static void testXmlParsing() throws IOException {
//        System.out.println("\n=== Testing XML Parsing ===");
//        String xmlInput = """
//            <?xml version="1.0" encoding="UTF-8"?>
//            <root>
//                <person id="123">
//                    <name>John Doe</name>
//                    <age>30</age>
//                    <contacts>
//                        <email type="primary">john@example.com</email>
//                        <phone>555-0123</phone>
//                    </contacts>
//                </person>
//            </root>
//            """;
//
//        DataNode node = DataNode.parse(xmlInput);
//        System.out.println("Name: " + node.find("person.name").asText());
//        System.out.println("Age: " + node.find("person.age").asInt());
//        System.out.println("Person ID: " + node.find("person.id").asText());
//        System.out.println("Email: " + node.find("person.contacts.email").asText());
//        System.out.println("Email type: " + node.find("person.contacts.email.type").asText());
//        System.out.println("Phone: " + node.find("person.contacts.phone").asText());
//    }
//
//    private static void testJsonParsing() throws IOException {
//        System.out.println("\n=== Testing JSON Parsing ===");
//        String jsonInput = """
//            {
//                "name": "test app",
//                "settings": {
//                    "environment": "production",
//                    "features": ["logging", "monitoring"],
//                    "numbers": [1, 2, 3]
//                }
//            }
//            """;
//
//        DataNode node = DataNode.parse(jsonInput);
//        System.out.println("Name: " + node.find("name").asText());
//        System.out.println("Environment: " + node.find("settings.environment").asText());
//        System.out.println("First feature: " + node.find("settings.features[0]").asText());
//        System.out.println("Second number: " + node.find("settings.numbers[1]").asInt());
//    }
//
//    private static void testYamlParsing() throws IOException {
//        System.out.println("\n=== Testing YAML Parsing ===");
//        String yamlInput = """
//            server:
//              host: localhost
//              port: 8080
//              settings:
//                timeout: 30
//                retries: 3
//              endpoints:
//                - name: api
//                  path: /api/v1
//                - name: admin
//                  path: /admin
//            """;
//
//        DataNode node = DataNode.parse(yamlInput);
//        System.out.println("Host: " + node.find("server.host").asText());
//        System.out.println("Port: " + node.find("server.port").asInt());
//        System.out.println("First endpoint name: " + node.find("server.endpoints[0].name").asText());
//        System.out.println("Second endpoint path: " + node.find("server.endpoints[1].path").asText());
//    }
//
//    private static void testComplexPathAccess() throws IOException {
//        System.out.println("\n=== Testing Complex Path Access ===");
//        String jsonInput = """
//            {
//                "users": [
//                    {
//                        "id": 1,
//                        "profile": {
//                            "name": "Alice",
//                            "settings": {
//                                "notifications": {
//                                    "email": true,
//                                    "sms": false
//                                }
//                            }
//                        }
//                    }
//                ]
//            }
//            """;
//
//        DataNode node = DataNode.parse(jsonInput);
//        System.out.println("Deep nested value: " +
//                node.find("users[0].profile.settings.notifications.email").asBoolean());
//
//        // Test non-existent paths
//        System.out.println("Non-existent path returns null: " +
//                (node.find("users[0].nonexistent") == null));
//        System.out.println("Invalid array index returns null: " +
//                (node.find("users[99]") == null));
//    }
//
//    private static void testFormatConversion() throws IOException {
//        System.out.println("\n=== Testing Format Conversion ===");
//        String jsonInput = """
//            {
//                "person": {
//                    "name": "Jane Smith",
//                    "age": 25,
//                    "hobbies": ["reading", "hiking"]
//                }
//            }
//            """;
//
//        DataNode node = DataNode.parse(jsonInput);
//
//        System.out.println("=== Original JSON ===");
//        System.out.println(node.toJson());
//
//        System.out.println("\n=== Converted to XML ===");
//        System.out.println(node.toXml());
//
//        System.out.println("\n=== Converted to YAML ===");
//        System.out.println(node.toYaml());
//    }
//
//    private static void testEdgeCases() throws IOException {
//        System.out.println("\n=== Testing Edge Cases ===");
//
//        // Empty object
//        DataNode emptyNode = DataNode.parse("{}");
//        System.out.println("Empty object parsed successfully: " + emptyNode.isObject());
//
//        // Empty array
//        DataNode emptyArray = DataNode.parse("[]");
//        System.out.println("Empty array parsed successfully: " + emptyArray.isArray());
//
//        // XML with attributes
//        String xmlWithAttrs = """
//            <?xml version="1.0"?>
//            <root>
//                <element attr="value">content</element>
//            </root>
//            """;
//        DataNode xmlNode = DataNode.parse(xmlWithAttrs);
//        System.out.println("XML attribute access: " + xmlNode.find("element.attr").asText());
//        System.out.println("XML content access: " + xmlNode.find("element").asText());
//
//        try {
//            DataNode.parse("invalid format");
//            System.out.println("Failed: Should throw exception for invalid format");
//        } catch (IllegalArgumentException e) {
//            System.out.println("Success: Caught invalid format exception");
//        }
//    }
//}