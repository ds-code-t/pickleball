package tools.ds.modkit.evaluations;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.runtime.function.AbstractVariadicFunction;
import com.googlecode.aviator.runtime.function.FunctionUtils;
import com.googlecode.aviator.runtime.type.AviatorBoolean;
import com.googlecode.aviator.runtime.type.AviatorNil;
import com.googlecode.aviator.runtime.type.AviatorObject;
import com.googlecode.aviator.runtime.type.AviatorString;

import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;

import static tools.ds.modkit.evaluations.AviatorUtil.isTruthy;

public class AviatorFunctions {

    static {
        AviatorEvaluator.addFunction(new FirstNotBlankFn());
        AviatorEvaluator.addFunction(new FirstNotEmptyFn());
        AviatorEvaluator.addFunction(new FirstNotNullFn());
        AviatorEvaluator.addFunction(new GetBoolVal());
    }

    /**
     * firstNotBlank(s1, s2, ...): returns the first string that is not null/empty/whitespace
     */
    public static final class FirstNotBlankFn extends AbstractVariadicFunction {
        @Override
        public String getName() {
            return "firstNotBlank";
        }

        @Override
        public AviatorObject variadicCall(Map<String, Object> env, AviatorObject... args) {
            for (AviatorObject ao : args) {
                String s = FunctionUtils.getStringValue(ao, env);
                if (s != null && !s.trim().isEmpty()) {
                    return new AviatorString(s);
                }
            }
            return AviatorNil.NIL;
        }
    }

    /**
     * firstNotEmpty(s1, s2, ...): returns the first string that is not null/""
     */
    public static final class FirstNotEmptyFn extends AbstractVariadicFunction {
        @Override
        public String getName() {
            return "firstNotEmpty";
        }

        @Override
        public AviatorObject variadicCall(Map<String, Object> env, AviatorObject... args) {
            for (AviatorObject ao : args) {
                String s = FunctionUtils.getStringValue(ao, env);
                if (s != null && !s.isEmpty()) {
                    return new AviatorString(s);
                }
            }
            return AviatorNil.NIL;
        }
    }

    /**
     * firstNotNull(s1, s2, ...): returns the first string that does NOT start with "<" and end with ">".
     * Example: skips "<NULL>", "<anything>", but returns "hello" or "world".
     */
    public static final class FirstNotNullFn extends AbstractVariadicFunction {
        @Override
        public String getName() {
            return "firstNotNull";
        }

        @Override
        public AviatorObject variadicCall(Map<String, Object> env, AviatorObject... args) {
            for (AviatorObject ao : args) {
                String s = FunctionUtils.getStringValue(ao, env);
                if (s != null && !(s.startsWith("<") && s.endsWith(">"))) {
                    return new AviatorString(s);
                }
            }
            return AviatorNil.NIL;
        }
    }


    public static final class GetBoolVal extends AbstractVariadicFunction {
        @Override
        public String getName() {
            return "getBool";
        }

        @Override
        public AviatorObject variadicCall(Map<String, Object> env, AviatorObject... args) {
            System.out.println("@@GetBool "+ Arrays.stream(args).toList());
            if (args == null || args.length == 0) {
                return AviatorBoolean.FALSE;
            }

            // Resolve the first argument against env
            Object resolved = args[0].getValue(env);  // <-- resolves identifiers & expressions
            System.out.println("@@resolved: " + resolved);
            // If you want to keep your existing isTruthy(String) signature:
            String s = (resolved == null || resolved == AviatorNil.NIL) ? "" : String.valueOf(resolved);
            System.out.println("@@s " + s);
            boolean result = isTruthy(s);  // your custom logic that takes String
            System.out.println("@@result " + result);
            // If you have isTruthy(Object), you could instead do:
            // boolean result = isTruthy(resolved);

            return AviatorBoolean.valueOf(result);
        }
    }


    public static final String operatorFlag = "\u206ATF";
    static String IF = operatorFlag + "IF:", ELSE = operatorFlag + "ELSE:", ELSEIF = operatorFlag + "ELSEIF:", THEN = operatorFlag + "THEN:";

    static String processTernaryExpression(String fullString) {
        System.out.println("@@processTernaryExpression: " + fullString);
        fullString = fullString.replaceAll("\\bELSE-IF:", ELSEIF)
                .replaceAll("\\bIF:", IF)
                .replaceAll("\\bTHEN:", THEN)
                .replaceAll("\\bELSE:", ELSE);

        Pattern p = Pattern.compile("(\\([^()]+\\))");
        System.out.println("@@fullString: " + fullString);
        String returnString = p.matcher(fullString)
                .replaceAll(m -> "(" + processTernaryString(m.group(1)) + ")");


        System.out.println("@@returnString: " + returnString);
        return processTernaryString(returnString);
    }

    private static String processTernaryString(String input) {
        System.out.println("@@processTernaryString2: " + input);
        if (!input.contains(operatorFlag)) return input;
        input = input.replaceAll("^([^" + operatorFlag + "]*)" + "(" + ELSE + "|" + ELSEIF + ")", IF + " getBoolstate($1) $2 ");
        input = input.replaceAll("((?:" + IF + "|" + ELSEIF + ")[^" + operatorFlag + "]*)(" + ELSE + "|" + ELSEIF + "|$)", " $1 " + THEN + " true $2 ");
        input = input.replaceAll("(" + THEN + "[^" + operatorFlag + "]*$)", " $1 " + ELSE + " false ");
        input = input.replaceAll(ELSEIF, " : " + IF);
        input = input.replaceAll(IF + "([^" + operatorFlag + "]*)" + THEN, "getBool($1) " + THEN);
        input = input.replaceAll(THEN, " ? ");
        input = input.replaceAll(ELSE, " : ");
        return input;
    }

    private static String normalize(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }

    private static void runTest(String input, String expected) {
        String actual = processTernaryExpression(input);
        System.out.println("Input   : " + normalize(input));
        System.out.println("Expected: " + normalize(expected));
        System.out.println("Actual  : " + normalize(actual));
        System.out.println();
    }

    public static void main(String[] args) {
        runTest("IF: x > 3 THEN: 1 ELSE: 2",
                "bool(x > 3) ? 1 : 2");

        runTest("IF: x > 3 THEN: 1",
                "bool(x > 3) ? 1 : false");

        runTest("IF: x > 3 THEN: 1 ELSE-IF: y < 5 THEN: 2 ELSE: 3",
                "bool(x > 3) ? 1 : bool(y < 5) ? 2 : 3");

        runTest("(IF: flag THEN: a ELSE: b)",
                "(bool(flag) ? a : b)");

        runTest("z + (IF: cond THEN: foo ELSE: bar)",
                "z + (bool(cond) ? foo : bar)");
    }
}


