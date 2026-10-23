package io.cucumber.messages.types;

import java.util.List;

import io.cucumber.core.gherkin.messages.NGherkinFactory;

import static tools.dscode.common.GlobalConstants.PARSER_FLAG;

/**
 * Adds metadata properties to PickleStep, and intercepts the constructor call.
 * If the incoming 'text' contains PARSER_FLAG, split on first occurrence:
 *   - left  -> used as the real PickleStep 'text'
 *   - right -> stored into introduced 'metaText'
 *
 * If the official step text ends with an inline argument of the form
 * TYPE:::...|, split on the last TYPE::: marker:
 *   - left  -> used as the real PickleStep 'text'
 *   - TYPE  -> stored into introduced 'inlineArgumentType'
 *   - right -> stored into introduced 'inlineArgumentText'
 *
 * Otherwise, construct normally and leave metadata fields as null.
 */
public privileged aspect PickleStepMetaTextAspect {

    /* =========================
     * Introduced member(s)
     * ========================= */
    /* ===== Introduced members (ITDs) ===== */
    public String io.cucumber.messages.types.PickleStep.metaText;
    public String io.cucumber.messages.types.PickleStep.inlineArgumentType;
    public String io.cucumber.messages.types.PickleStep.inlineArgumentText;

    /** Getter exposed to callers (ITD). */
    public String io.cucumber.messages.types.PickleStep.getMetaText() {
        return metaText == null ? "" : metaText;
    }

    /** Convenience flag: true when metaText was captured. */
    public boolean io.cucumber.messages.types.PickleStep.hasMetaText() {
        return metaText != null && !metaText.isEmpty();
    }

    public String io.cucumber.messages.types.PickleStep.getInlineArgumentType() {
        return inlineArgumentType == null ? "" : inlineArgumentType;
    }

    public String io.cucumber.messages.types.PickleStep.getInlineArgumentText() {
        return inlineArgumentText == null ? "" : inlineArgumentText;
    }

    public boolean io.cucumber.messages.types.PickleStep.hasInlineArgument() {
        return inlineArgumentType != null && !inlineArgumentType.isEmpty()
                && inlineArgumentText != null && !inlineArgumentText.isEmpty();
    }

    public List<?> io.cucumber.messages.types.PickleStep.getInlineArgumentAsList() {
        return InlinePickleArgument.asListOrMaps(getInlineArgumentText());
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
        String officialText = text;
        String metaText = null;

        final String flag = PARSER_FLAG;
        if (text != null && flag != null && !flag.isEmpty()) {
            int idx = text.indexOf(flag);
            if (idx >= 0) {

                // Split on first occurrence only
                officialText = text.substring(0, idx);
                metaText = text.substring(idx + flag.length());
            }
        }

        InlinePickleArgument.Extracted inlineArgument = InlinePickleArgument.extract(officialText);
        if (inlineArgument != null) {
            officialText = inlineArgument.stepText();
        }

        // Newlines inside resolved step text were escaped so this temporary
        // feature stayed one Gherkin line. Put the original breaks back after
        // inline-argument extraction, which treats breaks as whitespace.
        officialText = NGherkinFactory.restoreStepLineBreaks(officialText);
        if (metaText != null) {
            metaText = NGherkinFactory.restoreStepLineBreaks(metaText);
        }

        PickleStep ps = (PickleStep) proceed(argument, astNodeIds, id, type, officialText);
        ps.metaText = metaText;
        if (inlineArgument != null) {
            ps.inlineArgumentType = inlineArgument.argumentType();
            ps.inlineArgumentText = NGherkinFactory.restoreStepLineBreaks(inlineArgument.argumentText());
        }
        return ps;
    }



}
