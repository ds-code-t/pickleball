package tools.dscode;

import com.xpathy.Tag;
import com.xpathy.XPathy;
import io.cucumber.java.en.Given;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.annotations.LifecycleManager;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.domoperations.elementstates.BinaryStateConditions;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.treeparsing.xpathcomponents.XPathyBuilder;


import java.util.List;

import static com.xpathy.Attribute.aria_label;
import static com.xpathy.Attribute.id;
import static com.xpathy.Attribute.name;
import static com.xpathy.Attribute.role;
import static com.xpathy.Attribute.title;
import static com.xpathy.Attribute.type;
import static com.xpathy.Case.LOWER;
import static com.xpathy.Tag.any;
import static com.xpathy.Tag.input;
import static tools.dscode.common.domoperations.ExecutionDictionary.CONTAINS_TEXT;
import static tools.dscode.common.domoperations.ExecutionDictionary.STARTING_CONTEXT;
import static tools.dscode.common.mappings.MappingProcessor.getRunMap;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.combineOr;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.colocatedDeepNormalizedVisibleText;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.customElementSuffixPredicate;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.deepNormalizedVisibleText;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.descendantDeepNormalizedVisibleText;
import static tools.dscode.common.util.debug.DebugUtils.onMatch;
import static tools.dscode.common.util.debug.DebugUtils.printDebug;

public class Registrations {
    public static void main2(String[] args) {
        NodeMap nodeMap1 = new NodeMap();
        nodeMap1.put("key1", "k1a");
        nodeMap1.put("key1", "k1b");
        nodeMap1.put("key2.A", "k2a");
        nodeMap1.put("key2.A", "k2b");
        System.out.println("@@nodeMap1:: " +nodeMap1.getRoot());
    }

    public static void main4(String[] args) {
        NodeMap nodeMap1 = new NodeMap();
        nodeMap1.put("key1", "value");
        nodeMap1.put("key2.A", "value");
        nodeMap1.put("key3.A.B", "value");
        nodeMap1.put("key4.A.B.C", "value");
        nodeMap1.put("key2[-1].A", "value");
        System.out.println("@@nodeMap1:: " +nodeMap1.getRoot());

        NodeMap nodeMap2 = new NodeMap();
        nodeMap2.put("key2[-1].A", "value1");
        nodeMap2.put("key2[-1].A", "value2");
        nodeMap2.put("key1", "value3");
        nodeMap2.put("key2.A", "value4");
        nodeMap2.put("key3.A.B", "value5");
        nodeMap2.put("key4.A.B.C", "value6");
        nodeMap2.put("key2[-1].A", "value7");
        System.out.println("@@nodeMap2:: " + nodeMap2.getRoot());

        NodeMap nodeMap3 = new NodeMap();
        nodeMap3.put("key2[-1].A", "value8");
        nodeMap3.put("key2[-1].A", "value9");
        nodeMap3.put("key4.A.B.C", "value6");
        nodeMap3.put("key4.A.B.C", "value6");
        nodeMap3.put("key4[-1].A.B.C", "value6");
        System.out.println("@@nodeMap3:: " +nodeMap3.getRoot());

        NodeMap nodeMap4 = new NodeMap();
        nodeMap4.put("key2[-1].A.B.C", "value10");
        nodeMap4.put("key2[-1].A.B.C", "value11");
        System.out.println("@@nodeMap4:: " +nodeMap4.getRoot());
    }


    private static void print(String label, XPathy xpathy) {
        System.out.println(label + ":");
        System.out.println("  " + xpathy.getXpath());
        System.out.println();
    }

    public static void main4f(String[] args) {

        runNodeMapTest("nodeMap10-explicit-append", nodeMap -> {
            nodeMap.put("key13[-1].A[].b", "11B");
//            nodeMap.put("key13[-1].A[].b", "22B");
//            nodeMap.put("key13[-1].A[-1].a", "--A");
//            nodeMap.put("key13[-1].A[].b", "33B");
            nodeMap.put("key13[-1].A.b", "44B");

        });

//        runNodeMapTest("nodeMap14-wildcard", nodeMap -> {
//            nodeMap.put("key18[*].A", "k18a");
//            nodeMap.put("key18.A", "k18b");
//            nodeMap.put("key18[*].A", "k18c");
//        });
    }

    public static void mainw (String[] args) {
        runNodeMapTest("nodeMap1-baseline", nodeMap -> {
            nodeMap.put("key1", "k1a");
            nodeMap.put("key1", "k1b");
            nodeMap.put("key2.A", "k2a");
            nodeMap.put("key2.A", "k2b");
        });

        runNodeMapTest("nodeMap2-explicit-last", nodeMap -> {
            nodeMap.put("key2[-1].A", "k2a");
            nodeMap.put("key2[-1].A", "k2b");
            nodeMap.put("key2.A", "k2c");
            nodeMap.put("key2[-1].A", "k2d");
        });

        runNodeMapTest("nodeMap3-deep-implicit", nodeMap -> {
            nodeMap.put("key3.A.B", "k3a");
            nodeMap.put("key3.A.B", "k3b");
            nodeMap.put("key4.A.B.C", "k4a");
            nodeMap.put("key4.A.B.C", "k4b");
        });

        runNodeMapTest("nodeMap4-deep-explicit-last", nodeMap -> {
            nodeMap.put("key4[-1].A.B.C", "k4a");
            nodeMap.put("key4[-1].A.B.C", "k4b");
            nodeMap.put("key4.A.B.C", "k4c");
            nodeMap.put("key4[-1].A.B.C", "k4d");
        });

        runNodeMapTest("nodeMap5-singleton-looking", nodeMap -> {
            nodeMap.put("-key5", "k5a");
            nodeMap.put("-key5", "k5b");
            nodeMap.put("-key6.A", "k6a");
            nodeMap.put("-key6.A", "k6b");
        });

        runNodeMapTest("nodeMap6-value-assignment-looking", nodeMap -> {
            nodeMap.put("key7=", "k7a");
            nodeMap.put("key7=", "k7b");
            nodeMap.put("key8.A=", "k8a");
            nodeMap.put("key8.A=", "k8b");
        });

        runNodeMapTest("nodeMap7-dollar-root-looking", nodeMap -> {
            nodeMap.put("$.key9", "k9a");
            nodeMap.put("$.key9", "k9b");
            nodeMap.put("$.key10.A", "k10a");
            nodeMap.put("$.key10.A", "k10b");
        });

        runNodeMapTest("nodeMap8-hash-index-rewrite", nodeMap -> {
            nodeMap.put("key11#1.A", "k11a");
            nodeMap.put("key11#2.A", "k11b");
            nodeMap.put("key11#1.A", "k11c");
        });

        runNodeMapTest("nodeMap9-positive-indexes", nodeMap -> {
            nodeMap.put("key12[0].A", "k12a");
            nodeMap.put("key12[1].A", "k12b");
            nodeMap.put("key12[0].A", "k12c");
        });

        runNodeMapTest("nodeMap10-explicit-append", nodeMap -> {
            nodeMap.put("key13[].A", "k13a");
            nodeMap.put("key13[].A", "k13b");
            nodeMap.put("key13[-1].A", "k13c");
        });

        runNodeMapTest("nodeMap11-space-segments", nodeMap -> {
            nodeMap.put("key14.Some Name", "k14a");
            nodeMap.put("key14.Some Name", "k14b");
            nodeMap.put("key15.Some Name.Inner Value", "k15a");
        });

        runNodeMapTest("nodeMap12-backticked-segments", nodeMap -> {
            nodeMap.put("key16.`Already Wrapped`", "k16a");
            nodeMap.put("key16.`Already Wrapped`", "k16b");
        });

        runNodeMapTest("nodeMap13-map-prefix", nodeMap -> {
            nodeMap.put("DEFAULT::key17.A", "k17a");
            nodeMap.put("DEFAULT::key17.A", "k17b");
        });

        runNodeMapTest("nodeMap14-wildcard", nodeMap -> {
            nodeMap.put("key18[*].A", "k18a");
            nodeMap.put("key18.A", "k18b");
            nodeMap.put("key18[*].A", "k18c");
        });

        runNodeMapTest("nodeMap15-leading-bracket", nodeMap -> {
            nodeMap.put("[0].A", "k15a");
            nodeMap.put("[1].A", "k15b");
        });

        runNodeMapTest("nodeMap16-type-conflicts", nodeMap -> {
            nodeMap.put("key19", "k19a");
            nodeMap.put("key19.A", "k19b");
            nodeMap.put("key20.A", "k20a");
            nodeMap.put("key20", "k20b");
            nodeMap.put("key20[-1].A.B", "k20c");
        });
    }


    private static void runNodeMapTest(String name, NodeMapTest test) {
        NodeMap nodeMap = new NodeMap();

        try {
            test.run(nodeMap);
            System.out.println("@@" + name + ":: " + nodeMap.getRoot());
        } catch (Exception e) {
            System.out.println("@@" + name + "-ERROR:: " + e.getClass().getName() + ": " + e.getMessage());
            System.out.println("@@" + name + "-PARTIAL:: " + nodeMap.getRoot());
            e.printStackTrace(System.out);
        }
    }





    public static void maidsfn(String[] args) {
        runNodeMapTest(
                "01-normal-single-token-appends-array",
                """
                Expected:
                key1 should be an ArrayNode.
                key1 appends both scalar values.
                {"key1":["a","b"]}
                """,
                nodeMap -> {
                    nodeMap.put("key1", "a");
                    nodeMap.put("key1", "b");
                }
        );

        runNodeMapTest(
                "02-value-assignment-single-token-overwrites-direct-property",
                """
                Expected:
                key2= should NOT create an ArrayNode.
                key2 should be a direct scalar property.
                {"key2":"b"}
                """,
                nodeMap -> {
                    nodeMap.put("key2=", "a");
                    nodeMap.put("key2=", "b");
                }
        );

        runNodeMapTest(
                "03-value-assignment-single-token-overwrites-existing-array",
                """
                Expected:
                key3 starts as an ArrayNode from normal key3 writes.
                key3= replaces the entire key3 property with a scalar.
                {"key3":"direct"}
                """,
                nodeMap -> {
                    nodeMap.put("key3", "a");
                    nodeMap.put("key3", "b");
                    nodeMap.put("key3=", "direct");
                }
        );

        runNodeMapTest(
                "04-value-assignment-object-overwrites-existing-array",
                """
                Expected:
                key4= replaces the whole key4 ArrayNode with an ObjectNode.
                {"key4":{"A":"objA","B":"objB"}}
                """,
                nodeMap -> {
                    nodeMap.put("key4", "oldA");
                    nodeMap.put("key4", "oldB");

                    java.util.Map<String, Object> value = new java.util.LinkedHashMap<>();
                    value.put("A", "objA");
                    value.put("B", "objB");

                    nodeMap.put("key4=", value);
                }
        );

        runNodeMapTest(
                "05-value-assignment-list-overwrites-existing-scalar",
                """
                Expected:
                key5= can assign an ArrayNode directly from a List.
                key5 should be an ArrayNode, but it is directly assigned, not append-created.
                {"key5":["x","y"]}
                """,
                nodeMap -> {
                    nodeMap.put("key5=", "before");
                    nodeMap.put("key5=", java.util.List.of("x", "y"));
                }
        );

        runNodeMapTest(
                "06-value-assignment-longer-path-still-appends-top-level",
                """
                Expected:
                key6.A= still uses normal top-level append behavior.
                key6 should be an ArrayNode.
                {"key6":[{"A":"a"},{"A":"b"}]}
                """,
                nodeMap -> {
                    nodeMap.put("key6.A=", "a");
                    nodeMap.put("key6.A=", "b");
                }
        );

        runNodeMapTest(
                "07-value-assignment-deep-path-still-appends-top-level",
                """
                Expected:
                key7.A.B= still uses normal top-level append behavior.
                key7 should be an ArrayNode.
                {"key7":[{"A":{"B":"a"}},{"A":{"B":"b"}}]}
                """,
                nodeMap -> {
                    nodeMap.put("key7.A.B=", "a");
                    nodeMap.put("key7.A.B=", "b");
                }
        );

        runNodeMapTest(
                "08-singleton-single-token-overwrites-direct-property",
                """
                Expected:
                -key8 disables top-level array behavior.
                key8 should be a direct scalar property, not an ArrayNode.
                {"key8":"b"}
                """,
                nodeMap -> {
                    nodeMap.put("-key8", "a");
                    nodeMap.put("-key8", "b");
                }
        );

        runNodeMapTest(
                "09-singleton-single-token-replaces-existing-array",
                """
                Expected:
                key9 starts as an ArrayNode.
                -key9 removes/replaces the entire top-level key9 property.
                {"key9":"singleton"}
                """,
                nodeMap -> {
                    nodeMap.put("key9", "a");
                    nodeMap.put("key9", "b");
                    nodeMap.put("-key9", "singleton");
                }
        );

        runNodeMapTest(
                "10-singleton-nested-overwrites-direct-object-path",
                """
                Expected:
                -key10.A disables top-level array behavior.
                key10 should be an ObjectNode with property A.
                {"key10":{"A":"b"}}
                """,
                nodeMap -> {
                    nodeMap.put("-key10.A", "a");
                    nodeMap.put("-key10.A", "b");
                }
        );

        runNodeMapTest(
                "11-singleton-deep-path-overwrites-direct-object-path",
                """
                Expected:
                -key11.A.B disables top-level array behavior.
                key11 should be an ObjectNode, not an ArrayNode.
                {"key11":{"A":{"B":"b"}}}
                """,
                nodeMap -> {
                    nodeMap.put("-key11.A.B", "a");
                    nodeMap.put("-key11.A.B", "b");
                }
        );

        runNodeMapTest(
                "12-singleton-nested-replaces-existing-array",
                """
                Expected:
                key12 starts as an ArrayNode.
                -key12.A replaces the whole key12 property with an ObjectNode path.
                {"key12":{"A":"singletonNested"}}
                """,
                nodeMap -> {
                    nodeMap.put("key12.A", "a");
                    nodeMap.put("key12.A", "b");
                    nodeMap.put("-key12.A", "singletonNested");
                }
        );

        runNodeMapTest(
                "13-singleton-with-value-assignment-single-token",
                """
                Expected:
                -key13= should still be direct assignment.
                key13 should be a scalar, not an ArrayNode.
                {"key13":"b"}
                """,
                nodeMap -> {
                    nodeMap.put("-key13=", "a");
                    nodeMap.put("-key13=", "b");
                }
        );

        runNodeMapTest(
                "14-singleton-with-value-assignment-deep-path",
                """
                Expected:
                -key14.A.B= should be direct object path because singleton disables top-level array behavior.
                key14 should be an ObjectNode.
                {"key14":{"A":{"B":"b"}}}
                """,
                nodeMap -> {
                    nodeMap.put("-key14.A.B=", "a");
                    nodeMap.put("-key14.A.B=", "b");
                }
        );

        runNodeMapTest(
                "15-wildcard-missing-array-does-nothing",
                """
                Expected:
                key15 does not exist.
                key15[*].A should do nothing and should not create key15.
                {"⁪META_MapType":"DEFAULT"}
                """,
                nodeMap -> {
                    nodeMap.put("key15[*].A", "wild");
                }
        );

        runNodeMapTest(
                "16-wildcard-empty-array-does-nothing",
                """
                Expected:
                key16 exists as an empty ArrayNode.
                key16[*].A should do nothing.
                {"key16":[]}
                """,
                nodeMap -> {
                    nodeMap.put("key16=", java.util.List.of());
                    nodeMap.put("key16[*].A", "wild");
                }
        );

        runNodeMapTest(
                "17-wildcard-array-updates-existing-objects",
                """
                Expected:
                key17[*].A updates A on each existing object in key17.
                key17 remains an ArrayNode.
                {"key17":[{"A":"wild"},{"A":"wild"}]}
                """,
                nodeMap -> {
                    nodeMap.put("key17.A", "a");
                    nodeMap.put("key17.A", "b");
                    nodeMap.put("key17[*].A", "wild");
                }
        );

        runNodeMapTest(
                "18-wildcard-array-adds-property-to-existing-objects",
                """
                Expected:
                key18[*].B adds/overwrites B on each existing object.
                Existing A properties stay.
                {"key18":[{"A":"a","B":"wild"},{"A":"b","B":"wild"}]}
                """,
                nodeMap -> {
                    nodeMap.put("key18.A", "a");
                    nodeMap.put("key18.A", "b");
                    nodeMap.put("key18[*].B", "wild");
                }
        );

        runNodeMapTest(
                "19-wildcard-array-skips-non-object-elements-for-property-set",
                """
                Expected:
                key19[*].A should only set A on existing ObjectNode elements.
                Scalar elements should be skipped.
                {"key19":["scalar",{"A":"wild"}]}
                """,
                nodeMap -> {
                    nodeMap.put("key19", "scalar");
                    nodeMap.put("key19.A", "object");
                    nodeMap.put("key19[*].A", "wild");
                }
        );

        runNodeMapTest(
                "20-wildcard-array-index-then-property",
                """
                Expected:
                key20[0].A.*.C should update C on every ObjectNode property inside key20[0].A.
                {"key20":[{"A":{"one":{"C":"wild"},"two":{"C":"wild"}}}]}
                """,
                nodeMap -> {
                    nodeMap.put("-key20[0].A.one", new java.util.LinkedHashMap<>());
                    nodeMap.put("key20[0].A.two", new java.util.LinkedHashMap<>());
                    nodeMap.put("key20[0].A.*.C", "wild");
                }
        );

        runNodeMapTest(
                "21-wildcard-object-star-skips-non-object-values-for-deeper-property",
                """
                Expected:
                key21[0].A.*.C should set C only under object-valued properties.
                Non-object values under A should be skipped.
                {"key21":[{"A":{"one":{"C":"wild"},"two":"notObject"}}]}
                """,
                nodeMap -> {
                    nodeMap.put("-key21[0].A.one", new java.util.LinkedHashMap<>());
                    nodeMap.put("key21[0].A.two", "notObject");
                    nodeMap.put("key21[0].A.*.C", "wild");
                }
        );

        runNodeMapTest(
                "22-wildcard-object-star-final-token-replaces-existing-values",
                """
                Expected:
                key22[0].A.* replaces every existing value under A.
                {"key22":[{"A":{"one":"wild","two":"wild"}}]}
                """,
                nodeMap -> {
                    nodeMap.put("-key22[0].A.one", "old1");
                    nodeMap.put("key22[0].A.two", "old2");
                    nodeMap.put("key22[0].A.*", "wild");
                }
        );

        runNodeMapTest(
                "23-wildcard-array-star-final-token-replaces-existing-elements",
                """
                Expected:
                key23[*] replaces every existing array element.
                {"key23":["wild","wild"]}
                """,
                nodeMap -> {
                    nodeMap.put("key23", "a");
                    nodeMap.put("key23", "b");
                    nodeMap.put("key23[*]", "wild");
                }
        );

        runNodeMapTest(
                "24-wildcard-nested-array-existing-path-only",
                """
                Expected:
                key24[*].A[].B should do nothing because wildcard setter is existing-path only
                and [] append is ignored in wildcard mode.
                Existing structure remains unchanged.
                {"key24":[{"A":[]},{"A":[]}]}
                """,
                nodeMap -> {
                    nodeMap.put("key24.A=", java.util.List.of());
                    nodeMap.put("key24.A=", java.util.List.of());
                    nodeMap.put("key24[*].A[].B", "wild");
                }
        );

        runNodeMapTest(
                "25-wildcard-nested-array-update-existing-last-items",
                """
                Expected:
                key25[*].A[-1].B updates B on the last existing item of each A array.
                {"key25":[{"A":[{"B":"wild"}]},{"A":[{"B":"wild"}]}]}
                """,
                nodeMap -> {
                    nodeMap.put("key25.A[].B", "a");
                    nodeMap.put("key25.A[].B", "b");
                    nodeMap.put("key25[*].A[-1].B", "wild");
                }
        );

        runNodeMapTest(
                "26-wildcard-with-singleton-missing-path-does-nothing-after-replace",
                """
                Expected:
                Singleton removes/replaces the top-level property before setting.
                Because wildcard only matches existing paths, -key26[*].A should leave key26 absent.
                {"⁪META_MapType":"DEFAULT"}
                """,
                nodeMap -> {
                    nodeMap.put("key26.A", "a");
                    nodeMap.put("key26.A", "b");
                    nodeMap.put("-key26[*].A", "wild");
                }
        );

        runNodeMapTest(
                "27-wildcard-with-value-assignment-single-token-existing-array",
                """
                Expected:
                key27[*]= is not a normal direct key27= overwrite because wildcard targets existing elements.
                Each existing key27 element should become wild.
                {"key27":["wild","wild"]}
                """,
                nodeMap -> {
                    nodeMap.put("key27", "a");
                    nodeMap.put("key27", "b");
                    nodeMap.put("key27[*]=", "wild");
                }
        );

        runNodeMapTest(
                "28-leading-array-path-rejected",
                """
                Expected:
                Leading array path should throw IllegalArgumentException because NodeMap root is ObjectNode.
                """,
                nodeMap -> {
                    nodeMap.put("[0].A", "a");
                }
        );

        runNodeMapTest(
                "29-named-root-positive-index-still-valid",
                """
                Expected:
                Named root positive index is valid.
                key29 should be an ArrayNode with index 0 object.
                {"key29":[{"A":"zero"}]}
                """,
                nodeMap -> {
                    nodeMap.put("key29[0].A", "zero");
                }
        );

        runNodeMapTest(
                "30-named-root-hash-index-rewrite-valid",
                """
                Expected:
                #1 rewrites to [0], #2 rewrites to [1].
                key30 should be an ArrayNode.
                {"key30":[{"A":"firstAgain"},{"A":"second"}]}
                """,
                nodeMap -> {
                    nodeMap.put("key30#1.A", "first");
                    nodeMap.put("key30#2.A", "second");
                    nodeMap.put("key30#1.A", "firstAgain");
                }
        );
    }



    public static void main(String[] args) {
        String nullString = null;
        runNodeMapTest(
                "null",
                """
            
                """,
                nodeMap -> {
                    nodeMap.put(nullString, "nullString Value");
                }
        );

        runNodeMapTest(
                "empty",
                """
            
                """,
                nodeMap -> {
                    nodeMap.put("", "empty Value");
                }
        );

        runNodeMapTest(
                " blank ",
                """
            
                """,
                nodeMap -> {
                    nodeMap.put(" ", "blank Value");
                }
        );

    }

    @FunctionalInterface
    private interface NodeMapTest {
        void run(NodeMap nodeMap);
    }

    private static void runNodeMapTest(String name, String expected, NodeMapTest test) {
        NodeMap nodeMap = new NodeMap();

        System.out.println();
        System.out.println("@@TEST:: " + name);
        System.out.println("@@EXPECTED::");
        System.out.println(expected.strip());

        try {
            test.run(nodeMap);
            System.out.println("@@ACTUAL:: " + nodeMap.getRoot());
        } catch (Exception e) {
            System.out.println("@@ERROR:: " + e.getClass().getName() + ": " + e.getMessage());
            System.out.println("@@PARTIAL:: " + nodeMap.getRoot());
            e.printStackTrace(System.out);
        }
    }




}
