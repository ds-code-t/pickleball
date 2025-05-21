package io.pickleball.valueresolution;

import com.googlecode.aviator.*;
import com.googlecode.aviator.lexer.token.OperatorType;
import com.googlecode.aviator.runtime.function.AbstractFunction;
import com.googlecode.aviator.runtime.type.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.Duration;
import java.time.Period;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

public class AviatorWrapper {

    public enum Conversion {
        toBoolean,
        toInt,
        toBigDecimal,
        toString
    }


    private final AviatorEvaluatorInstance evaluator;

    private static final Set<String> FALSE_VALUES = new HashSet<>(Arrays.asList(
            "NULL", "0", "0.000", "FALSE", "NO", ""
    ));

    public AviatorWrapper() {
        evaluator = AviatorEvaluator.getInstance(); // Fresh instance
        evaluator.addFunction(new EvalFunctions.SeqListFunctionOverride());
        evaluator.addFunction(new EvalFunctions.SeqMapFunctionOverride());
        evaluator.addFunction(new EvalFunctions.predicateCheck());
        evaluator.aliasOperator(OperatorType.AND, "AND");
        evaluator.aliasOperator(OperatorType.OR, "OR");
        Set<Feature> enabledFeatures = new HashSet<>();

        // Enables variable assignment (e.g., a = 5)
        // Security Risk: Low - Safe unless variables can trigger unsafe operations elsewhere
        enabledFeatures.add(Feature.Assignment);

        // Enables return statements in functions or blocks
        // Security Risk: Low - Just control flow, no direct risk
        enabledFeatures.add(Feature.Return);

        // Enables if/elsif/else conditional statements
        // Security Risk: Low - Purely control flow, safe
        enabledFeatures.add(Feature.If);

        // Enables for loop statements
        // Security Risk: Moderate - Safe with MAX_LOOP_COUNT set; risk of infinite loops otherwise
        enabledFeatures.add(Feature.ForLoop);

        // Enables while loop statements
        // Security Risk: Moderate - Safe with MAX_LOOP_COUNT; risk of infinite loops without limit
        enabledFeatures.add(Feature.WhileLoop);

        // Enables let statements for block-scoped variables
        // Security Risk: Low - Enhances safety by isolating variables
        enabledFeatures.add(Feature.Let);

        // Enables lexical (static) scoping for variables
        // Security Risk: Low - Safe, improves variable predictability
        enabledFeatures.add(Feature.LexicalScope);

        // Enables lambda expressions (e.g., lambda(x) -> x + 1 end)
        // Security Risk: Low - Safe unless lambda bodies invoke risky operations
        enabledFeatures.add(Feature.Lambda);

        // Enables named function definitions with fn
        // Security Risk: Low - Safe, depends on function content; requires Assignment and Lambda
        enabledFeatures.add(Feature.Fn);

        // Enables access to internal variables like __env__, __instance__
        // Security Risk: High - Exposes internal state, could be exploited for unintended access
        enabledFeatures.add(Feature.InternalVars);

        // Enables module system with exports, require, and load
        // Security Risk: High - Risky if scripts access filesystem or external resources
        enabledFeatures.add(Feature.Module);

        // Enables try/catch/finally and throw statements
        // Security Risk: Low - Safe unless throw is used with unsafe objects
        enabledFeatures.add(Feature.ExceptionHandle);

        // Enables new Class(arguments) to create Java class instances
        // Security Risk: High - Risky unless ALLOWED_CLASS_SET restricts to safe classes
        enabledFeatures.add(Feature.NewInstance);

        // Enables string interpolation (e.g., "hello #{name}")
        // Security Risk: Moderate - Safe if InternalVars is controlled; requires InternalVars
        enabledFeatures.add(Feature.StringInterpolation);

        // Enables use package.class to import Java classes dynamically
        // Security Risk: High - Risky without ALLOWED_CLASS_SET restricting imports
        enabledFeatures.add(Feature.Use);

        // Enables access to Java class static fields (e.g., Class.FIELD)
        // Security Risk: High - Risky (e.g., System.out) without ALLOWED_CLASS_SET restrictions
        enabledFeatures.add(Feature.StaticFields);

        // Enables invocation of Java class static methods (e.g., Class.method(args))
        // Security Risk: High - Very risky (e.g., Runtime.getRuntime().exec()) without ALLOWED_CLASS_SET
        enabledFeatures.add(Feature.StaticMethods);

        // Apply the feature set to the evaluator
        evaluator.setOption(Options.FEATURE_SET, enabledFeatures);

        // Optional: Add additional safety measures
        // Set a maximum loop count to prevent infinite loops
        evaluator.setOption(Options.MAX_LOOP_COUNT, 1000);



        // Expanded set of safe, practical classes
        Set<Class<?>> safeClasses = new HashSet<>();
        // Whitelist safe classes to mitigate risks from NewInstance, StaticFields, StaticMethods, etc.
        // Basic Types and Wrappers
        safeClasses.add(String.class);          // String manipulation, immutable and safe
        safeClasses.add(Integer.class);         // Integer operations, safe
        safeClasses.add(Double.class);          // Floating-point operations, safe
        safeClasses.add(Long.class);            // Long integer operations, safe
        safeClasses.add(Boolean.class);         // Boolean operations, safe
        safeClasses.add(Character.class);       // Character operations, safe
        safeClasses.add(Byte.class);            // Byte operations, safe
        safeClasses.add(Short.class);           // Short integer operations, safe
        safeClasses.add(Float.class);           // Float operations, safe

        // Math-Related Classes
        safeClasses.add(Math.class);            // Mathematical functions (sin, cos, etc.), pure and safe
        safeClasses.add(BigInteger.class);      // Arbitrary-precision integers, safe for math
        safeClasses.add(BigDecimal.class);      // Arbitrary-precision decimals, safe for precise math
        safeClasses.add(Random.class);          // Random number generation, safe and useful

        // Time-Related Classes (java.time package)
        safeClasses.add(LocalDate.class);       // Date without time, immutable and safe
        safeClasses.add(LocalTime.class);       // Time without date, immutable and safe
        safeClasses.add(LocalDateTime.class);   // Date and time, immutable and safe
        safeClasses.add(Instant.class);         // Point in time (UTC), immutable and safe
        safeClasses.add(Duration.class);        // Time duration, immutable and safe
        safeClasses.add(Period.class);          // Date-based period (e.g., years), immutable and safe
        safeClasses.add(ChronoUnit.class);      // Time units (e.g., DAYS, HOURS), safe enum

        // Collections and Data Structures
        safeClasses.add(ArrayList.class);       // Dynamic list, safe for data storage
        safeClasses.add(LinkedList.class);      // Linked list, safe for data storage
        safeClasses.add(HashSet.class);         // Unordered set, safe for unique elements
        safeClasses.add(TreeSet.class);         // Sorted set, safe for ordered unique elements
        safeClasses.add(HashMap.class);         // Key-value map, safe for data mapping
        safeClasses.add(TreeMap.class);         // Sorted key-value map, safe for ordered mapping
        safeClasses.add(Collections.class);     // Utility methods for collections (e.g., sort), safe
        safeClasses.add(Arrays.class);          // Utility methods for arrays (e.g., sort), safe

        // Miscellaneous Utilities
        safeClasses.add(UUID.class);            // Unique identifier generation, safe and useful

        // Apply the safe classes to the evaluator
        evaluator.setOption(Options.ALLOWED_CLASS_SET, safeClasses);

        evaluator.addFunction(new AbstractFunction() {
            @Override
            public String getName() {
                return "currentTimeMillis";
            }

            @Override
            public AviatorObject call(Map<String, Object> env) {
                return AviatorLong.valueOf(System.currentTimeMillis());
            }
        });

//        evaluator.enableSandboxMode();
//        evaluator.enableFeature(Feature.StaticMethods);
//        try {
////            evaluator.importFunctions(java.lang.System.class);
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }
//        System.out.println("StaticMethods enabled: " + evaluator.isFeatureEnabled(Feature.StaticMethods));
        configureBooleanOperators();
    }

    private void configureBooleanOperators() {
        evaluator.addOpFunction(OperatorType.AND, new AbstractFunction() {
            @Override
            public String getName() { return "&&"; }
            @Override
            public AviatorObject call(Map<String, Object> env, AviatorObject arg1, AviatorObject arg2) {
                boolean left = coerceToBoolean(arg1.getValue(env));
                if (!left) return AviatorBoolean.FALSE;
                boolean right = coerceToBoolean(arg2.getValue(env));
                return AviatorBoolean.valueOf(left && right);
            }
        });

        evaluator.addOpFunction(OperatorType.OR, new AbstractFunction() {
            @Override
            public String getName() { return "||"; }
            @Override
            public AviatorObject call(Map<String, Object> env, AviatorObject arg1, AviatorObject arg2) {
                boolean left = coerceToBoolean(arg1.getValue(env));
                if (left) return AviatorBoolean.TRUE;
                boolean right = coerceToBoolean(arg2.getValue(env));
                return AviatorBoolean.valueOf(left || right);
            }
        });

        evaluator.setFunctionMissing(new FunctionMissing() {
            @Override
            public AviatorObject onFunctionMissing(String name, Map<String, Object> env, AviatorObject... args) {
                // Log the missing function/reference
                System.err.println("WARNING: Unresolved reference detected: " + name);

                // You can choose to throw an exception instead of returning null
                // throw new RuntimeException("Unresolved reference: " + name);

                // Or return AviatorNil (null)
                return AviatorNil.NIL;
            }
        });

        evaluator.setFunctionMissing(new FunctionMissing() {
            @Override
            public AviatorObject onFunctionMissing(String name, Map<String, Object> env, AviatorObject... args) {
                // Log the missing function/reference
                System.err.println("WARNING: Unresolved reference detected: " + name);

                // You can choose to throw an exception instead of returning null
                // throw new RuntimeException("Unresolved reference: " + name);

                // Or return AviatorNil (null)
                return AviatorNil.NIL;
            }
        });

    }

    private boolean coerceToBoolean(Object value) {
        String strValue = String.valueOf(value).trim().toUpperCase();
        return !FALSE_VALUES.contains(strValue);
    }

    public Object evaluate(String expression, Map<String, Object> variables) {
        return evaluator.execute(expression, variables);
    }


//    public static class CustomSeqListFunction extends AbstractVariadicFunction {
//        @Override
//        public String getName() {
//            return "seq.list"; // Overrides built-in seq.list
//        }
//
//        @Override
//        public AviatorObject variadicCall(Map<String, Object> env, AviatorObject... args) {
//            // Create instance of custom list
//            EvalList customList = createEvalList();
//
//            // Add all arguments to the list
//            for (AviatorObject arg : args) {
//                customList.addObject(arg.getValue(env));
//            }
//
//            // Return as AviatorRuntimeJavaType, not AviatorJavaType
//            return new AviatorRuntimeJavaType(customList);
//        }
//    }

}










