//package tools.dscode.common.treeparsing;
//
//import com.xpathy.XPathy;
//
//import java.util.List;
//
//import static tools.dscode.common.domoperations.XPathyUtils.deepNormalizedText;
//
//public final class Main {
//    static void printCodepoints(String label, String s) {
//        System.out.print(label + ": ");
//        s.codePoints().forEach(cp -> System.out.print(Integer.toHexString(cp) + " "));
//        System.out.println();
//    }
//
//
//
//    public static void main(String[] args) {
//        DictionaryA dict = new DictionaryA();
//
//        // Print the static definition once (what the grammar looks like)
////        System.out.println("\n==== Definition (ParseNode) Tree ====");
////        TreeDebugPrinter.printDefinitionTree(dict.root);
//
//        // A few sample inputs to exercise the engine
//        List<String> samples = List.of(
//                """
//                        click the Button , then click the Link
//                        """
////                """
////                        , I click the "Submit" Button with name of "finish"
////                        """
////                """
////                        , from the Top Panel   click   , I the click the "Submit" Button, and wait 1 minute
////                        """
//        );
//
//        for (int i = 0; i < samples.size(); i++) {
//            String input = samples.get(i);
//            runCase(dict, "Case " + (i + 1), input);
//        }
//    }
//
//    private static void runCase(DictionaryA dict, String label, String input) {
//        System.out.println("\n==== " + label + " ====");
//        System.out.println("Input:   " + input);
//
//
//        MatchNode top = dict.parse(input);
//
//        System.out.println("\n-- Match (runtime) Tree --");
//        TreeDebugPrinter.printMatchTree(top);
//
//        // Show final strings at the top node
//        System.out.println("\nModified: " + safe(top.modifiedText()));
//        System.out.println("Masked:   " + safe(top.maskedText()));
//    }
//
//    private static String safe(String s) {
//        return (s == null) ? "<null>" : s;
//    }
//}
