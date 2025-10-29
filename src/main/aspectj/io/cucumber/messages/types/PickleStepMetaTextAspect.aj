package io.cucumber.messages.types;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Adds a 'metaText' property to PickleStep, and intercepts the constructor call.
 * If the incoming 'text' contains PARSER_FLAG, split on first occurrence:
 *   - left  -> used as the real PickleStep 'text'
 *   - right -> stored into introduced 'metaText'
 *
 * Otherwise, construct normally and leave 'metaText' as null.
 */
public privileged aspect PickleStepMetaTextAspect {

    /* =========================
     * Introduced member(s)
     * ========================= */
    private String io.cucumber.messages.types.PickleStep.metaText;

    /** Returns the additional meta text (may be null). */
    public String io.cucumber.messages.types.PickleStep.getMetaText() {
        return metaText;
    }

    /** Convenience flag: true when metaText was captured. */
    public boolean io.cucumber.messages.types.PickleStep.hasMetaText() {
        return metaText != null && !metaText.isEmpty();
    }

    /* =========================
     * Pointcut: constructor call to PickleStep
     * PickleStep(PickleStepArgument, List<String>, String, PickleStepType, String)
     * ========================= */
    pointcut pickleStepCtorCall(
            PickleStepArgument argument,
            List<String> astNodeIds,
            String id,
            PickleStepType type,
            String text
    ) :
            call(io.cucumber.messages.types.PickleStep.new(
                    io.cucumber.messages.types.PickleStepArgument,
                            java.util.List,
                            java.lang.String,
                            io.cucumber.messages.types.PickleStepType,
                            java.lang.String))
                    && args(argument, astNodeIds, id, type, text);

    /* =========================
     * Around advice: possibly rewrite 'text' and set 'metaText'
     * ========================= */
    io.cucumber.messages.types.PickleStep around(
            PickleStepArgument argument,
            List<String> astNodeIds,
            String id,
            PickleStepType type,
            String text
    ) : pickleStepCtorCall(argument, astNodeIds, id, type, text) {

        final String flag = resolveMetaFlag();
        if (text != null && flag != null && !flag.isEmpty()) {
            int idx = text.indexOf(flag);
            if (idx >= 0) {
                // Split on first occurrence only
                String left  = text.substring(0, idx);
                String right = text.substring(idx + flag.length());

                // Proceed with 'left' as the official PickleStep text
                PickleStep ps = (PickleStep) proceed(argument, astNodeIds, id, type, left);
                // Store the right-hand part in our introduced field
                ps.metaText = right;
                return ps;
            }
        }

        // No flag -> construct normally; metaText remains null
        return (PickleStep) proceed(argument, astNodeIds, id, type, text);
    }

    /* =========================
     * Flag resolution helpers
     * ========================= */

    /**
     * Resolve PARSER_FLAG in priority order:
     *  1) System property: -Dcucumber.meta.flag=...
     *  2) public static String PARSER_FLAG in known classes (via reflection)
     *  3) default fallback "@@META@@"
     */
    private static String resolveMetaFlag() {
        String prop = System.getProperty("cucumber.meta.flag");
        if (prop != null && !prop.isEmpty()) return prop;

        // Add/adjust candidates as needed in your project
        String[] candidates = new String[] {
                "io.cucumber.gherkin.Meta",                 // e.g., a utility holder you define
                "io.cucumber.gherkin.EncodingParserMeta",   // example placeholder
                "tools.dscode.aspects.MetaFlags"            // example placeholder
        };
        for (String fqn : candidates) {
            String v = tryGetPublicStaticStringField(fqn, "PARSER_FLAG");
            if (v != null && !v.isEmpty()) return v;
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
            // Not found / not accessible — ignore
        }
        return null;
    }
}
