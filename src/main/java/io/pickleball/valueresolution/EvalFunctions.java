package io.pickleball.valueresolution;

import com.googlecode.aviator.runtime.function.AbstractVariadicFunction;
import com.googlecode.aviator.runtime.type.AviatorObject;
import com.googlecode.aviator.runtime.type.AviatorRuntimeJavaType;
import io.pickleball.datafunctions.EvalList;
import io.pickleball.exceptions.PickleballException;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import static io.pickleball.datafunctions.EvalList.createEvalList;

public class EvalFunctions {


    public static class SeqListFunctionOverride extends AbstractVariadicFunction {
        @Override
        public String getName() {
            return "seq.list"; // Overrides built-in seq.list
        }

        @Override
        public AviatorObject variadicCall(Map<String, Object> env, AviatorObject... args) {
            System.out.println("@@seq.list " + env);
            System.out.println("@@args: " + Arrays.asList(args));
            EvalList customList = createEvalList();

            // Add all arguments to the list
            for (AviatorObject arg : args) {
                customList.addObject(arg.getValue(env));
            }

            // Return as AviatorRuntimeJavaType, not AviatorJavaType
            return new AviatorRuntimeJavaType(customList);
        }
    }

    public static class predicateCheck extends AbstractVariadicFunction {
        @Override
        public String getName() {
            return "predicateCheck"; // Overrides built-in seq.list
        }

        @Override
        public AviatorObject variadicCall(Map<String, Object> env, AviatorObject... args) {
            System.out.println("@@predicateCheck: " + env);
            System.out.println("@@args: " + Arrays.asList(args));
            // Create instance of custom list

            String opChain = String.valueOf(args[0].getValue(env));
            Object vals = args[1].getValue(env);

            EvalList valList = vals instanceof EvalList ? (EvalList) vals : createEvalList(vals);


            valList.matchCheckModes(opChain);

            return AviatorRuntimeJavaType.valueOf(valList.getBoolValue());
        }
    }


    public static class findFirst extends AbstractVariadicFunction {
        private static final Map<String, Predicate<Object>> PREDICATES = new HashMap<>();

        static {
            PREDICATES.put("non-blank value", obj -> obj instanceof String s && !s.trim().isEmpty());
            PREDICATES.put("empty string", obj -> obj instanceof String s && s.isEmpty());
            PREDICATES.put("integer > 5", obj -> obj instanceof Integer i && i > 5);
        }

        @Override
        public String getName() {
            return "findFirst"; // Overrides built-in seq.list
        }

        @Override
        public AviatorObject variadicCall(Map<String, Object> env, AviatorObject... args) {
            String predicateType = String.valueOf(args[0].getValue(env));
            Predicate<Object> predicate = PREDICATES.get(predicateType);
            if (predicate == null) {
                throw new PickleballException("Unknown predicate: " + predicateType);
            }
            for( int i = 1; i< args.length; i++)
            {
                Object obj = args[i];
                    if (predicate.test(obj)) {
                        AviatorRuntimeJavaType.valueOf(obj);
                    }
            }
            throw new PickleballException("No values pass the predicate: " + predicateType);
        }
    }

}
