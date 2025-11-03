//package io.cucumber.messages.types;
//
//import java.lang.reflect.Field;
//
//import static java.util.Objects.requireNonNull;
//import static tools.dscode.common.GlobalConstants.PARSER_FLAG;
//
///**
// * Adds a new 'metaText' property to Step and peeks at the constructor arguments.
// * If the 'text' argument contains PARSER_FLAG, split on the first occurrence:
// *   - left  -> becomes the Step's 'text'
// *   - right -> stored in the introduced 'metaText' property
// *
// * Otherwise, construct normally and leave metaText as null.
// */
//public privileged aspect StepMetaTextAspect {
//
//    /* =========================
//     * Introduced member(s)
//     * ========================= */
//    public String io.cucumber.messages.types.Step.metaText;
//
//    /** Returns the additional meta text (may be null). */
//    public String io.cucumber.messages.types.Step.getMetaText() {
//        return metaText;
//    }
//
//    /** (Optional) convenience: true when metaText was captured. */
//    public boolean io.cucumber.messages.types.Step.hasMetaText() {
//        return metaText != null && !metaText.isEmpty();
//    }
//
//    /* =========================
//     * Pointcut: constructor call to Step
//     * Step(Location, String, StepKeywordType, String, DocString, DataTable, String)
//     * ========================= */
//    pointcut stepCtorCall(
//            Location location,
//            String keyword,
//            StepKeywordType keywordType,
//            String text,
//            DocString docString,
//            DataTable dataTable,
//            String id
//    ) :
//            call(io.cucumber.messages.types.Step.new(Location, String, StepKeywordType, String, DocString, DataTable, String))
//                    && args(location, keyword, keywordType, text, docString, dataTable, id);
//
//    /* =========================
//     * Around advice: possibly rewrite 'text' and set 'metaText'
//     * ========================= */
//    io.cucumber.messages.types.Step around(
//            Location location,
//            String keyword,
//            StepKeywordType keywordType,
//            String text,
//            DocString docString,
//            DataTable dataTable,
//            String id
//    ) : stepCtorCall(location, keyword, keywordType, text, docString, dataTable, id) {
//
//        final String flag = PARSER_FLAG;
//        if (text != null && flag != null && !flag.isEmpty()) {
//            int idx = text.indexOf(flag);
//            System.out.println("@@idx:: " + idx);
//            System.out.println("@text- idx:: " + text);
//            if (idx >= 0) {
//                // Split on the first occurrence only
//                String left  = text.substring(0, idx);
//                String right = text.substring(idx + flag.length());
//                System.out.println("@@left:::: " + left);
//                System.out.println("@@right:::: " + right);
//                // Proceed with 'left' as the actual step text
//                Step step = (Step) proceed(location, keyword, keywordType, left, docString, dataTable, id);
//                // Store the right-hand part in our introduced field
//                step.metaText = right;
//                return step;
//            }
//        }
//
//        // No flag -> construct normally, metaText stays null
//        Step step = (Step) proceed(location, keyword, keywordType, text, docString, dataTable, id);
//        return step;
//    }
//
//
//}
