package io.cucumber.messages.types;

import java.util.List;

import static tools.dscode.common.GlobalConstants.PARSER_FLAG;

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
    /* ===== Introduced members (ITDs) ===== */
    public String io.cucumber.messages.types.PickleStep.metaText;

    /** Getter exposed to callers (ITD). */
    public String io.cucumber.messages.types.PickleStep.getMetaText() {
        return metaText == null ? "" : metaText;
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

        final String flag = PARSER_FLAG;
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



}
