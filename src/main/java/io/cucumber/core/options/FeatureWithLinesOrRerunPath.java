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

package io.cucumber.core.options;

import io.cucumber.core.feature.FeatureWithLines;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Optional;

/**
 * Identifies either:
 * <li>
 * <ul>
 * a single rerun file,
 * </ul>
 * <ul>
 * a directory of containing exclusively rerun files,
 * </ul>
 * <ul>
 * a directory containing feature files,
 * </ul>
 * <ul>
 * a specific feature,
 * </ul>
 * <ul>
 * or specific scenarios and examples (pickles) in a feature
 * </ul>
 * </li>
 * <p>
 * The syntax is either a {@link FeatureWithLines} or an {@code @} followed by a
 * {@link RerunPath}.
 */
class FeatureWithLinesOrRerunPath {

    private final FeatureWithLines featureWithLines;
    private final Collection<FeatureWithLines> featuresWithLinesToRerun;

    FeatureWithLinesOrRerunPath(
            FeatureWithLines featureWithLines, Collection<FeatureWithLines> featuresWithLinesToRerun
    ) {
        this.featureWithLines = featureWithLines;
        this.featuresWithLinesToRerun = featuresWithLinesToRerun;
    }

    static FeatureWithLinesOrRerunPath parse(String arg) {
        if (arg.startsWith("@")) {
            Path rerunFileOrDirectory = Paths.get(arg.substring(1));
            return new FeatureWithLinesOrRerunPath(null, RerunPath.parse(rerunFileOrDirectory));
        } else {
            return new FeatureWithLinesOrRerunPath(FeatureWithLines.parse(arg), null);
        }
    }

    Optional<Collection<FeatureWithLines>> getFeaturesToRerun() {
        return Optional.ofNullable(featuresWithLinesToRerun);
    }

    Optional<FeatureWithLines> getFeatureWithLines() {
        return Optional.ofNullable(featureWithLines);
    }

}
