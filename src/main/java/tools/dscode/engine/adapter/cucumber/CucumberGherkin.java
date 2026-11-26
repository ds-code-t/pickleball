package tools.dscode.engine.adapter.cucumber;

import io.cucumber.core.gherkin.messages.NGherkinFactory;
import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.InlinePickleArgument;
import io.cucumber.messages.types.ParseError;
import tools.dscode.common.GlobalConstants;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.parse.EncodingRewrite;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Gherkin parse and pickle-text rewrite. The native parser delegates here. */
public final class CucumberGherkin {
    private CucumberGherkin() {
    }

    public static EngineFeature parse(String uri, String text) {
        String sourceUri = uri == null || uri.isBlank() ? "memory:" : uri;
        String rewritten = EncodingRewrite.apply(text);
        GherkinParser parser = GherkinParser.builder()
                .includeSource(false)
                .includePickles(false)
                .includeGherkinDocument(true)
                .build();
        List<String> errors = new ArrayList<>();
        EngineFeature feature = null;
        for (Envelope envelope : parser.parse(sourceUri, rewritten.getBytes(StandardCharsets.UTF_8)).toList()) {
            envelope.getParseError().map(ParseError::getMessage).ifPresent(errors::add);
            if (envelope.getGherkinDocument().isPresent()) {
                feature = CucumberModelConverter.fromDocument(envelope.getGherkinDocument().get());
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", errors));
        }
        return feature == null ? EngineFeature.of("", List.of()) : feature;
    }

    /**
     * Pickle step text after {@code PickleStepMetaTextAspect}: flag suffix becomes
     * meta text, an inline argument is split off, and escaped line breaks return.
     */
    public static EngineStep official(EngineStep step) {
        if (step == null) return null;
        String text = step.text() == null ? "" : step.text();
        String meta = null;
        int flag = text.indexOf(GlobalConstants.PARSER_FLAG);
        if (flag >= 0) {
            meta = text.substring(flag + GlobalConstants.PARSER_FLAG.length());
            text = text.substring(0, flag);
        }
        InlinePickleArgument.Extracted inline = InlinePickleArgument.extract(text);
        if (inline != null) text = inline.stepText();
        text = NGherkinFactory.restoreStepLineBreaks(text);
        if (meta != null) meta = NGherkinFactory.restoreStepLineBreaks(meta);
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>(step.metadata());
        if (meta != null) metadata.put("metaText", meta);
        if (inline != null) {
            metadata.put("inlineArgumentType", inline.argumentType());
            String argument = NGherkinFactory.restoreStepLineBreaks(inline.argumentText());
            metadata.put("inlineArgumentText", argument == null ? "" : argument);
        }
        if (text.equals(step.text()) && metadata.equals(step.metadata())) return step;
        return new EngineStep(step.keyword(), text, step.argument(), step.location(), metadata, step.fromBackground());
    }
}
