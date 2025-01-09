/*
 * This file incorporates work covered by the following copyright and permission notice:
 *
 * Copyright (c) Cucumber Ltd
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.cucumber.core.gherkin.messages;

import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.FeatureParser;
import io.cucumber.core.gherkin.FeatureParserException;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.gherkin.GherkinDialect;
import io.cucumber.gherkin.GherkinDialectProvider;
import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.GherkinDocument;
import io.cucumber.messages.types.ParseError;
import io.cucumber.messages.types.Source;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;

public final class GherkinMessagesFeatureParser implements FeatureParser {

    @Deprecated
    @Override
    public Optional<Feature> parse(URI path, String source, Supplier<UUID> idGenerator) {
        try (InputStream is = new ByteArrayInputStream(source.getBytes(UTF_8))) {
            return parse(path, is, idGenerator);
        } catch (IOException e) {
            throw new FeatureParserException("Failed to parse resource at: " + path, e);
        }
    }

    @Override
    public Optional<Feature> parse(URI path, InputStream source, Supplier<UUID> idGenerator) throws IOException {
        List<Envelope> envelopes = GherkinParser.builder()
                .idGenerator(() -> idGenerator.get().toString())
                .build()
                .parse(path.toString(), source)
                .collect(toList());

        List<String> errors = envelopes.stream()
                .map(Envelope::getParseError)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(ParseError::getMessage)
                .collect(toList());

        if (!errors.isEmpty()) {
            throw new FeatureParserException(
                "Failed to parse resource at: " + path + "\n" + String.join("\n", errors));
        }

        return envelopes.stream()
                .map(Envelope::getGherkinDocument)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst()
                .map(GherkinDocument::getFeature)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(feature -> {
                    CucumberQuery cucumberQuery = new CucumberQuery();
                    cucumberQuery.update(feature);
                    GherkinDialectProvider dialectProvider = new GherkinDialectProvider();
                    String language = feature.getLanguage();
                    GherkinDialect dialect = dialectProvider.getDialect(language)
                            // Can't happen, we just parsed the feature.
                            .orElseThrow(() -> new IllegalStateException(language + "was not a known gherkin Dialect"));

                    List<io.cucumber.messages.types.Pickle> pickleMessages = envelopes.stream()
                            .map(Envelope::getPickle)
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .collect(toList());

                    List<Pickle> pickles = pickleMessages.stream()
                            .map(pickle -> new GherkinMessagesPickle(pickle, path, dialect, cucumberQuery))
                            .collect(toList());

                    Source sourceMessage = envelopes.stream()
                            .map(Envelope::getSource)
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("source message was not emitted by parser"));

                    return new GherkinMessagesFeature(
                        feature,
                        path,
                        sourceMessage.getData(),
                        pickles,
                        envelopes);
                });
    }

    @Override
    public String version() {
        return "8";
    }

}
