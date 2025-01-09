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

package io.cucumber.core.feature;

import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.FeatureParserException;
import io.cucumber.core.resource.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.function.Supplier;

import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

public final class FeatureParser {

    private final Supplier<UUID> idGenerator;

    public FeatureParser(Supplier<UUID> idGenerator) {
        this.idGenerator = idGenerator;
    }

    public Optional<Feature> parseResource(Resource resource) {
        requireNonNull(resource);
        URI uri = resource.getUri();

        ServiceLoader<io.cucumber.core.gherkin.FeatureParser> services = ServiceLoader
                .load(io.cucumber.core.gherkin.FeatureParser.class);
        Iterator<io.cucumber.core.gherkin.FeatureParser> iterator = services.iterator();
        List<io.cucumber.core.gherkin.FeatureParser> parser = new ArrayList<>();
        while (iterator.hasNext()) {
            parser.add(iterator.next());
        }
        Comparator<io.cucumber.core.gherkin.FeatureParser> version = comparing(
            io.cucumber.core.gherkin.FeatureParser::version);

        try (InputStream source = resource.getInputStream()) {
            return Collections.max(parser, version).parse(uri, source, idGenerator);

        } catch (IOException e) {
            throw new FeatureParserException("Failed to parse resource at: " + uri, e);
        }
    }

}
