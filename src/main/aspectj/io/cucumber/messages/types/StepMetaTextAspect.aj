package io.cucumber.messages.types;

import java.lang.reflect.Field;

import static java.util.Objects.requireNonNull;

/**
 * Adds a new 'metaText' property to Step and peeks at the constructor arguments.
 * If the 'text' argument contains META_FLAG, split on the first occurrence:
 *   - left  -> becomes the Step's 'text'
 *   - right -> stored in the introduced 'metaText' property
 *
 * Otherwise, construct normally and leave metaText as null.
 */
public privileged aspect StepMetaTextAspect {

    /* =========================
     * Introduced member(s)
     * ========================= */
    private String io.cucumber.messages.types.Step.metaText;

    /** Returns the additional meta text (may be null). */
    public String io.cucumber.messages.types.Step.getMetaText() {
        return metaText;
    }

    /** (Optional) convenience: true when metaText was captured. */
    public boolean io.cucumber.messages.types.Step.hasMetaText() {
        return metaText != null && !metaText.isEmpty();
    }

    /* =========================
     * Pointcut: constructor call to Step
     * Step(Location, String, StepKeywordType, String, DocString, DataTable, String)
     * ========================= */
    pointcut stepCtorCall(
            Location location,
            String keyword,
            StepKeywordType keywordType,
            String text,
            DocString docString,
            DataTable dataTable,
            String id
    ) :
            call(io.cucumber.messages.types.Step.new(Location, String, StepKeywordType, String, DocString, DataTable, String))
                    && args(location, keyword, keywordType, text, docString, dataTable, id);

    /* =========================
     * Around advice: possibly rewrite 'text' and set 'metaText'
     * ========================= */
    io.cucumber.messages.types.Step around(
            Location location,
            String keyword,
            StepKeywordType keywordType,
            String text,
            DocString docString,
            DataTable dataTable,
            String id
    ) : stepCtorCall(location, keyword, keywordType, text, docString, dataTable, id) {

        final String flag = resolveMetaFlag();
        if (text != null && flag != null && !flag.isEmpty()) {
            int idx = text.indexOf(flag);
            if (idx >= 0) {
                // Split on the first occurrence only
                String left  = text.substring(0, idx);
                String right = text.substring(idx + flag.length());
                // Proceed with 'left' as the actual step text
                Step step = (Step) proceed(location, keyword, keywordType, left, docString, dataTable, id);
                // Store the right-hand part in our introduced field
                step.metaText = right;
                return step;
            }
        }

        // No flag -> construct normally, metaText stays null
        Step step = (Step) proceed(location, keyword, keywordType, text, docString, dataTable, id);
        return step;
    }

    /* =========================
     * Flag resolution helpers
     * ========================= */

    /**
     * Resolve the META flag to look for, in priority order:
     *  1) System property: -Dcucumber.meta.flag=...
     *  2) Public static String META_FLAG in one of the known classes (via reflection)
     *  3) Fallback default "@@META@@"
     */
    private static String resolveMetaFlag() {
        String prop = System.getProperty("cucumber.meta.flag");
        if (prop != null && !prop.isEmpty()) {
            return prop;
        }
        // Try a few likely holders; customize this list for your project
        String[] candidates = new String[] {
                "io.cucumber.gherkin.EncodingParserLineSwap", // if you exposed a public static META_FLAG
                "io.cucumber.gherkin.Meta",
                "tools.dscode.aspects.MetaFlags"
        };
        for (String fqn : candidates) {
            String v = tryGetPublicStaticStringField(fqn, "META_FLAG");
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "@@META@@";
    }

    private static String tryGetPublicStaticStringField(String className, String fieldName) {
        try {
            Class<?> c = Class.forName(className);
            Field f = c.getField(fieldName);
            if (f.getType() == String.class) {
                Object v = f.get(null);
                return (String) v;
            }
        } catch (Throwable ignore) {
            // class/field not found or inaccessible — ignore and continue
        }
        return null;
    }
}
