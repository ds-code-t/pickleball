package tools.dscode.common.evaluations;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.lexer.token.OperatorType;
import com.googlecode.aviator.runtime.function.AbstractFunction;
import com.googlecode.aviator.runtime.type.AviatorBoolean;
import com.googlecode.aviator.runtime.type.AviatorObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static tools.dscode.common.evaluations.AviatorFunctions.processTernaryExpression;
import static tools.dscode.common.util.DebugUtils.printDebug;
import static tools.dscode.common.util.Reflect.invokeAnyMethod;

public final class AviatorUtil {

    static {
        // bool(x) helper
        AviatorEvaluator.addFunction(new BoolFn());

        // Override &&
        AviatorEvaluator.addOpFunction(OperatorType.AND, new AbstractFunction() {
            @Override
            public String getName() {
                return "&&";
            }

            @Override
            public AviatorObject call(Map<String, Object> env, AviatorObject a, AviatorObject b) {
                if (!isTruthy(a.getValue(env)))
                    return AviatorBoolean.FALSE; // short-circuit
                return AviatorBoolean.valueOf(isTruthy(b.getValue(env)));
            }
        });

        // Override ||
        AviatorEvaluator.addOpFunction(OperatorType.OR, new AbstractFunction() {
            @Override
            public String getName() {
                return "||";
            }

            @Override
            public AviatorObject call(Map<String, Object> env, AviatorObject a, AviatorObject b) {
                if (isTruthy(a.getValue(env)))
                    return AviatorBoolean.TRUE; // short-circuit
                return AviatorBoolean.valueOf(isTruthy(b.getValue(env)));
            }
        });

        // Override !
        AviatorEvaluator.addOpFunction(OperatorType.NOT, new AbstractFunction() {
            @Override
            public String getName() {
                return "!";
            }

            @Override
            public AviatorObject call(Map<String, Object> env, AviatorObject arg) {
                return AviatorBoolean.valueOf(!isTruthy(arg.getValue(env)));
            }
        });

        // Override ?:
        AviatorEvaluator.addOpFunction(OperatorType.TERNARY, new AbstractFunction() {
            @Override
            public String getName() {
                return "?:";
            }

            @Override
            public AviatorObject call(
                    Map<String, Object> env, AviatorObject condition,
                    AviatorObject ifTrue, AviatorObject ifFalse
            ) {
                if (isTruthy(condition.getValue(env))) {
                    return ifTrue;
                } else {
                    return ifFalse;
                }
            }
        });

    }

    private AviatorUtil() {
    }

    public static void main(String[] args) {
        printDebug("@@eval: " + eval("true ? 1 : 0"));
    }

    public static Object eval(Object expr) {
        return eval(expr, null);
    }

    /**
     * Evaluates an object by converting it to a String and running it through
     * Aviator.
     */
    public static Object eval(Object expr, Map<String, Object> map) {


        if (expr == null)
            return null;
        String processedExpression = preprocessExpression(expr.toString());



        if (map == null)
            return AviatorEvaluator.execute(processedExpression);
        return AviatorEvaluator.execute(processedExpression, map);
    }

    /**
     * Evaluates an object to boolean, using Aviator's final-result coercion.
     */
    public static boolean evalToBoolean(Object expr, Map<String, Object> map) {
        return expr != null && (boolean) AviatorEvaluator.execute(preprocessExpression(expr.toString()), map, true);
    }

    public static String preprocessExpression(String expression) {
        return processTernaryExpression(expression);
    }

    /**
     * Shared truthiness (numbers: non-zero; strings/collections/maps:
     * non-empty).
     */
    public static boolean isTruthy(Object v) {
        if (v == null)
            return false;
        if (v instanceof Boolean returnBool)
            return returnBool;
        if (!(v instanceof String)) {
            Object isEmpty = invokeAnyMethod(v, "isEmpty");
            if (isEmpty instanceof Boolean isEmptyBool) {
                return !isEmptyBool;
            }
        }
        return isStringTruthy(String.valueOf(v));

    }

    private static final Set<String> FALSE_VALUES = new HashSet<>(Arrays.asList(
        "null", "false", "no"));

    public static boolean isStringTruthy(String v) {
        v = v.replaceAll("\"'`\\s", "").strip().toLowerCase();
        if (v.isEmpty())
            return false;
        if (v.replaceAll("[\\[\\],0.]", "").isEmpty())
            return false;
        if (v.startsWith("<") && v.endsWith("<"))
            return false;
        return !FALSE_VALUES.contains(v.replaceAll("[^A-Za-z]", ""));
    }

    /**
     * bool(x) for use inside expressions.
     */
    public static class BoolFn extends AbstractFunction {
        @Override
        public String getName() {
            return "bool";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject x) {
            return AviatorBoolean.valueOf(isTruthy(x.getValue(env)));
        }
    }

    public static class FirstNonNull extends AbstractFunction {
        @Override
        public String getName() {
            return "first";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject x) {
            return AviatorBoolean.valueOf(isTruthy(x.getValue(env)));
        }
    }

}
