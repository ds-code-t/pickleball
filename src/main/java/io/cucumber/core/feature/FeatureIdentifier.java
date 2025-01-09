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

import java.net.URI;
import java.nio.file.Path;

/**
 * Identifies a single feature.
 * <p>
 * Features are identified by a URI as defined in {@link FeaturePath}.
 * Additionally the scheme specific part must end with {@code .feature}
 *
 * @see FeatureWithLines
 */
public class FeatureIdentifier {

    private static final String FEATURE_FILE_SUFFIX = ".feature";

    private FeatureIdentifier() {

    }

    public static URI parse(String featureIdentifier) {
        return parse(FeaturePath.parse(featureIdentifier));
    }

    public static URI parse(URI featureIdentifier) {
        if (!isFeature(featureIdentifier)) {
            throw new IllegalArgumentException(
                "featureIdentifier does not reference a single feature file: " + featureIdentifier);
        }
        return featureIdentifier;
    }

    public static boolean isFeature(URI featureIdentifier) {
        return featureIdentifier.getSchemeSpecificPart().endsWith(FEATURE_FILE_SUFFIX);
    }

    public static boolean isFeature(Path path) {
        return path.getFileName().toString().endsWith(FEATURE_FILE_SUFFIX);
    }

}
