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

package io.cucumber.core.runtime;

import io.cucumber.core.feature.FeatureParser;
import io.cucumber.core.feature.Options;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.logging.Logger;
import io.cucumber.core.logging.LoggerFactory;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.resource.ResourceScanner;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.cucumber.core.feature.FeatureIdentifier.isFeature;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;

/**
 * Supplies a list of features found on the the feature path provided to
 * RuntimeOptions.
 */
public final class FeaturePathFeatureSupplier implements FeatureSupplier {
    private static final Logger log = LoggerFactory.getLogger(FeaturePathFeatureSupplier.class);

    public final Options featureOptions;
    private final ResourceScanner<Feature> featureScanner;

    public FeaturePathFeatureSupplier(Supplier<ClassLoader> classLoader, Options featureOptions, FeatureParser parser) {
        this.featureOptions = featureOptions;
        this.featureScanner = new ResourceScanner<>(
                classLoader,
                uri -> isFeature(uri),
                parser::parseResource
        );
    }

    @Override
    public List<Feature> get() {
        List<URI> featurePaths = featureOptions.getFeaturePaths();
        List<Feature> features = loadFeatures(featurePaths);
        if (features.isEmpty()) {
            if (featurePaths.isEmpty()) {
                log.warn(() -> "Got no path to feature directory or feature file");
            } else {
                log.warn(
                        () -> "No features found at " + featurePaths.stream().map(URI::toString).collect(joining(", "))
                );
            }
        }
        return features;
    }

    public List<Feature> get(RuntimeOptions runtimeOptions) {
        List<URI> featurePaths = runtimeOptions.getFeaturePaths();
        List<Feature> features = loadFeatures(featurePaths);
        if (features.isEmpty()) {
            if (featurePaths.isEmpty()) {
                log.warn(() -> "Got no path to feature directory or feature file");
            } else {
                log.warn(
                        () -> "No features found at " + featurePaths.stream().map(URI::toString).collect(joining(", "))
                );
            }
        }
        return features;
    }

    private List<Feature> loadFeatures(List<URI> featurePaths) {
        log.debug(() -> "Loading features from " + featurePaths.stream().map(URI::toString).collect(joining(", ")));
        final FeatureBuilder builder = new FeatureBuilder();

        for (URI featurePath : featurePaths) {
            List<Feature> found = featureScanner.scanForResourcesUri(featurePath);
            if (found.isEmpty() && isFeature(featurePath)) {
                throw new IllegalArgumentException("Feature not found: " + featurePath);
            }
            found.forEach(builder::addUnique);
        }

        return builder.build();
    }

    static final class FeatureBuilder {
        private final Map<String, Map<String, Feature>> sourceToFeature = new HashMap<>();
        private final List<Feature> features = new ArrayList<>();

        List<Feature> build() {
            List<Feature> features = new ArrayList<>(this.features);
            features.sort(comparing(Feature::getUri));
            return features;
        }

        void addUnique(Feature parsedFeature) {
            String parsedFileName = getFileName(parsedFeature);

            Map<String, Feature> existingFeatures = sourceToFeature.get(parsedFeature.getSource());
            if (existingFeatures != null) {
                Feature existingFeature = existingFeatures.get(parsedFileName);
                if (existingFeature != null) {
                    FeaturePathFeatureSupplier.log.error(() -> "" +
                            "Duplicate feature found: " +
                            parsedFeature.getUri() + " was identical to " + existingFeature.getUri() + "\n" +
                            "\n" +
                            "This typically happens when you configure cucumber to look " +
                            "for features in the root of your project.\nYour build tool " +
                            "creates a copy of these features in a 'target' or 'build'" +
                            "directory.\n" +
                            "\n" +
                            "If your features are on the class path consider using a class path URI.\n" +
                            "For example: 'classpath:com/example/app.feature'\n" +
                            "Otherwise you'll have to provide a more specific location");
                    return;
                }
            }
            sourceToFeature.putIfAbsent(parsedFeature.getSource(), new HashMap<>());
            sourceToFeature.get(parsedFeature.getSource()).put(parsedFileName, parsedFeature);
            features.add(parsedFeature);
        }

        private String getFileName(Feature feature) {
            String uri = feature.getUri().getSchemeSpecificPart();
            int i = uri.lastIndexOf("/");
            return i > 0 ? uri.substring(i) : uri;
        }

    }

}