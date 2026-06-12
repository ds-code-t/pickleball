package tools.dscode.common.evaluations;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.runtime.function.AbstractVariadicFunction;
import com.googlecode.aviator.runtime.function.FunctionUtils;
import com.googlecode.aviator.runtime.type.AviatorBoolean;
import com.googlecode.aviator.runtime.type.AviatorNil;
import com.googlecode.aviator.runtime.type.AviatorObject;
import com.googlecode.aviator.runtime.type.AviatorString;

import java.util.Map;

import static tools.dscode.common.evaluations.AviatorUtil.isTruthy;

public class AviatorFunctions {

    static {
        AviatorEvaluator.addFunction(new FirstNotBlankFn());
        AviatorEvaluator.addFunction(new FirstNotEmptyFn());
        AviatorEvaluator.addFunction(new FirstNotNullFn());
        AviatorEvaluator.addFunction(new GetBoolVal());
    }

    /**
     * firstNotBlank(s1, s2, ...): returns the first string that is not
     * null/empty/whitespace
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
     * firstNotNull(s1, s2, ...): returns the first string that does NOT start
     * with "&lt;" and end with ">". Example: skips "&lt;NULL>",
     * "&lt;anything>", but returns "hello" or "world".
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
            if (args == null || args.length == 0) {
                return AviatorBoolean.FALSE;
            }

            // Resolve the first argument against env
            Object resolved = args[0].getValue(env); // <-- resolves identifiers
                                                     // & expressions
            // If you want to keep your existing isTruthy(String) signature:
            String s = (resolved == null || resolved == AviatorNil.NIL) ? "" : String.valueOf(resolved);
            boolean result = isTruthy(s); // your custom logic that takes String
            // If you have isTruthy(Object), you could instead do:
            // boolean result = isTruthy(resolved);

            return AviatorBoolean.valueOf(result);
        }
    }


}
