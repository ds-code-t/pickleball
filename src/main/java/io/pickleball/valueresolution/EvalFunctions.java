package io.pickleball.valueresolution;

import com.googlecode.aviator.runtime.function.AbstractVariadicFunction;
import com.googlecode.aviator.runtime.type.AviatorObject;
import com.googlecode.aviator.runtime.type.AviatorRuntimeJavaType;
import io.pickleball.datafunctions.EvalList;
import java.util.Arrays;
import java.util.Map;

import static io.pickleball.datafunctions.EvalList.createEvalList;

public class EvalFunctions {


    public static class SeqListFunctionOverride extends AbstractVariadicFunction {
        @Override
        public String getName() {
            return "seq.list"; // Overrides built-in seq.list
        }

        @Override
        public AviatorObject variadicCall(Map<String, Object> env, AviatorObject... args) {


            EvalList customList = createEvalList();

//            new Exception().printStackTrace();
//            System.out.println("@@objects[1].getClass(): " + objects[0].getClass());
//            Object r = AviatorEvaluator.execute(String.valueOf(objects[0]));
//            System.out.println("@@r: "+ r);
//            System.out.println("@@r.getClass(: "+ r.getClass());

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
            // Create instance of custom list

            String opChain = String.valueOf(args[0].getValue(env));
            Object vals = args[1].getValue(env);

//            if(String.valueOf(vals).startsWith("seq.list("))
//                return new AviatorRuntimeJavaType(( (EvalList) AviatorEvaluator.execute(String.valueOf(vals))).getBoolValue());

            EvalList valList = vals instanceof EvalList ? (EvalList) vals : createEvalList(vals);

//            EvalList.CheckMode checkMode = Arrays.stream(EvalList.CheckMode.values())
//                    .filter(enumValue -> opChain.contains(enumValue.name()))
//                    .findFirst().orElse(EvalList.CheckMode.ANY);

            valList.matchCheckModes(opChain);

//             Return as AviatorRuntimeJavaType, not AviatorJavaType
            return AviatorRuntimeJavaType.valueOf(valList.getBoolValue());
        }
    }

}
