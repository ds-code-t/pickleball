//package io.pickleball.customtypes;
//
//
//import org.mvel2.CompileException;
//import org.mvel2.MVEL;
//import org.mvel2.ParserContext;
//
//import java.io.Serializable;
//import java.util.HashMap;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//import static io.pickleball.valueresolution.BooleanResolver.resolveObjectToBoolean;
//
//
//public class EvalExpression extends HashMap<String, Object> {
//
//    private final Object defaultValue;
//
//    private final ParserContext context;
//    private final String  inputExpression;
//    private final Object  evaluatedValue;
//
//    @Override
//    public String toString() {
////        return inputExpression;
//        return String.valueOf(evaluatedValue);
//    }
//
//    public EvalExpression(String inputExpression) {
//        this(inputExpression, null);
//    }
//
//
//    public EvalExpression(String inputExpression, String defaultValue) {
//        context = new ParserContext();
//        context.setStrictTypeEnforcement(false);
//        context.setStrongTyping(false);
//        this.inputExpression = inputExpression.substring(2, inputExpression.length() - 2);
//        this.defaultValue = defaultValue;
//
//        this.evaluatedValue = evaluate(this.inputExpression);
//
//    }
//
//
//    public Object evaluate(String inputString) {
//        Object obj;
//        try {
//            Serializable compiled = MVEL.compileExpression(inputString+";", context);
////            System.out.println("@@evaluate: " + inputExpression);
//            obj = MVEL.executeExpression(compiled, this);
//        } catch (CompileException | ClassCastException e) {
//            System.out.println("@@message: " + e.getMessage());
//            if(e.getMessage().contains("cannot be cast to class java.lang.Boolean"))
//                return resolveObjectToBoolean(inputString);
//            throw new RuntimeException("failed to evaluate '"+ inputString+ "'",e);
//        }
//
//        return obj;
//    }
//
//
//
////    @Override
////    public boolean containsKey(Object key) {
////        if (CLASS_LITERALS.containsKey(key))
////            return false;
////
////
////        return true;
////    }
//
//    @Override
//    public Object get(Object key) {
//        System.out.println("@@get override key: " + key);
////        if (!super.containsKey(key))
//
//
//
//            return String.valueOf(key);
//
//
//
//    }
//
//
//    private static final Pattern QUOTE_PATTERN = Pattern.compile(
//            "([\"'`])"           // Capture opening quote, single quote, or backtick
//                    + "((?:\\\\.|(?!\\1).)*?)" // Capture any escaped char or any char that isn't the delimiter
//                    + "\\1"              // Match the same quote/backtick as closing
//    );
//
//
//
//}
