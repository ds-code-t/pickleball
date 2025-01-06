package io.pickleball.valueresolution;

//import io.pickleball.customtypes.EvalExpression;
import io.pickleball.mapandStateutilities.LinkedMultiMap;
import io.pickleball.mapandStateutilities.MapsWrapper;
import org.mvel2.ConversionHandler;
import org.mvel2.DataConversion;
import org.mvel2.MVEL;
import org.mvel2.PreProcessor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import static org.mvel2.compiler.AbstractParser.*;

// Example usage
public class MVELExample {
    public static void main(String[] args) {
//        System.out.println("\n@@CLASS_LITERALS");
//        System.out.println(CLASS_LITERALS);
//        System.out.println("\n@@OPERATORS");
//        System.out.println(OPERATORS);
//        System.out.println("\n@@LITERALS");
//        System.out.println(LITERALS);
        LinkedMultiMap<String, String> map = new LinkedMultiMap<>();
        map.put("test","AAA");
        map.put("complete","BBB");
        map.put("test complete","CCC");

        MapsWrapper mapsWrapper = new MapsWrapper(map);

//        String expr =  "Double.MAX_VALUE > '1.0'";
        String expr = " test complete ";
        Serializable compiled = MVEL.compileExpression(expr);
        Object val = MVEL.executeExpression(compiled, mapsWrapper);
        System.out.println("----\n@@expr: " + expr + "  ,eval: " + val);

//        System.out.println("----\n\n");
////
//        EvalExpression ex = new EvalExpression(expr);
//        Object rt = ex.startEval();
//        System.out.println("Evaluated value: : " + rt);
    }
    public class CustomDslPreProcessor implements PreProcessor {

        @Override
        public char[] parse(char[] input) {
            // Transform the input DSL (char array) into an MVEL-compatible syntax
            String inputString = new String(input);
            String transformed = transformDsl(inputString);
            return transformed.toCharArray();
        }

        @Override
        public String parse(String input) {
            // Transform the input DSL (String) into an MVEL-compatible syntax
            return transformDsl(input);
        }

        private String transformDsl(String input) {
            // Example transformation logic:
            // Replace DSL keywords with MVEL-compatible equivalents
            String transformed = input
                    .replace("CREATE", "createUser")
                    .replace("WHERE", "")
                    .replace("AND", ",");

            // Further custom transformations can be added here
            return transformed;
        }
    }


}