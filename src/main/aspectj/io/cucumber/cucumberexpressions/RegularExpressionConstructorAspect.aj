//// File: io/cucumber/cucumberexpressions/RegularExpressionConstructorAspect.aj
//package io.cucumber.cucumberexpressions;
//
//import java.util.regex.Pattern;
//
//import static tools.dscode.common.GlobalConstants.RETURN_STEP_FLAG;
//
//public aspect RegularExpressionConstructorAspect {
//
//    // Your custom replace logic
//    private static final String FROM_REGEX = "yourRegexHere";
//    private static final String TO_REPLACEMENT = "yourReplacementHere";
//
//    // Intercept execution of the RegularExpression constructor
//    pointcut ctor(Pattern p, ParameterTypeRegistry registry) :
//            execution(io.cucumber.cucumberexpressions.RegularExpression.new(Pattern, io.cucumber.cucumberexpressions.ParameterTypeRegistry))
//                    && args(p, registry);
//
//    // Around advice replaces the Pattern BEFORE constructor body executes
//    RegularExpression around(Pattern p, ParameterTypeRegistry registry) : ctor(p, registry) {
//
//        // Original regex string
//        String original = p.pattern();
//
//        Pattern effective = original.startsWith(RETURN_STEP_FLAG) ? Pattern.compile(original.replace(RETURN_STEP_FLAG,"^")) : p;
//
//        // Call the real constructor with modified argument
//        return proceed(effective, registry);
//    }
//}
