package io.cucumber.core.feature;

import io.cucumber.core.resource.Resource;
import io.cucumber.core.runtime.FeaturePathLoadCache;

import java.util.Optional;

/**
 * Maven Surefire parses every feature twice. The second pass reuses a feature
 * whose file size and last-modified time have not changed.
 */
public aspect FeatureParser_Cache {

    pointcut parse(FeatureParser parser, Resource resource):
            execution(public Optional io.cucumber.core.feature.FeatureParser.parseResource(Resource))
                    && this(parser)
                    && args(resource);

    Optional around(FeatureParser parser, Resource resource): parse(parser, resource) {
        return FeaturePathLoadCache.parseResource(resource, () -> proceed(parser, resource));
    }
}
