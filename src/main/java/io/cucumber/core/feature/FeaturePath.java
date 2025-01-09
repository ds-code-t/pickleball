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

import java.io.File;
import java.net.URI;
import java.util.Locale;

import static io.cucumber.core.resource.ClasspathSupport.CLASSPATH_SCHEME_PREFIX;
import static io.cucumber.core.resource.ClasspathSupport.RESOURCE_SEPARATOR_CHAR;
import static io.cucumber.core.resource.ClasspathSupport.rootPackageUri;
import static java.util.Objects.requireNonNull;

/**
 * A feature path is a URI to a single feature file or directory of features.
 * <p>
 * This URI can either be absolute: {@code scheme:/absolute/path/to.feature}, or
 * relative to the current working directory:
 * {@code scheme:relative/path/to.feature}. In either form, when the scheme is
 * omitted {@code file} will be assumed.
 * <p>
 * On systems that use a {@code File.separatorChar} other then `{@code /}`
 * {@code File.separatorChar} can be used as a path separator. When doing so
 * when the scheme must be omitted: {@code path\to.feature}. <em>It is
 * recommended to use `{@code /}` as the path separator.</em>
 *
 * @see FeatureIdentifier
 * @see FeatureWithLines
 */
public class FeaturePath {

    private FeaturePath() {

    }

    public static URI parse(String featureIdentifier) {
        requireNonNull(featureIdentifier, "featureIdentifier may not be null");
        if (featureIdentifier.isEmpty()) {
            throw new IllegalArgumentException("featureIdentifier may not be empty");
        }

        // Legacy from the Cucumber Eclipse plugin
        // Older versions of Cucumber allowed it.
        if (CLASSPATH_SCHEME_PREFIX.equals(featureIdentifier)) {
            return rootPackageUri();
        }

        if (nonStandardPathSeparatorInUse(featureIdentifier)) {
            String standardized = replaceNonStandardPathSeparator(featureIdentifier);
            return parseAssumeFileScheme(standardized);
        }

        if (isWindowsOS() && pathContainsWindowsDrivePattern(featureIdentifier)) {
            return parseAssumeFileScheme(featureIdentifier);
        }

        if (probablyURI(featureIdentifier)) {
            return parseProbableURI(featureIdentifier);
        }

        return parseAssumeFileScheme(featureIdentifier);
    }

    private static boolean nonStandardPathSeparatorInUse(String featureIdentifier) {
        return File.separatorChar != RESOURCE_SEPARATOR_CHAR
                && featureIdentifier.contains(File.separator);
    }

    private static String replaceNonStandardPathSeparator(String featureIdentifier) {
        return featureIdentifier.replace(File.separatorChar, RESOURCE_SEPARATOR_CHAR);
    }

    private static URI parseAssumeFileScheme(String featureIdentifier) {
        File featureFile = new File(featureIdentifier);
        return featureFile.toURI();
    }

    private static boolean isWindowsOS() {
        String osName = System.getProperty("os.name");
        return normalize(osName).contains("windows");
    }

    private static boolean pathContainsWindowsDrivePattern(String featureIdentifier) {
        return featureIdentifier.matches("^[a-zA-Z]:.*$");
    }

    private static boolean probablyURI(String featureIdentifier) {
        return featureIdentifier.matches("^[a-zA-Z+.\\-]+:.*$");
    }

    private static URI parseProbableURI(String featureIdentifier) {
        URI uri = URI.create(featureIdentifier);
        if ("file".equals(uri.getScheme())) {
            return parseAssumeFileScheme(uri.getSchemeSpecificPart());
        }
        return uri;
    }

    private static String normalize(final String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "");
    }

}
