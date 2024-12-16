package io.cucumber.core.feature;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

public interface Options {
    List<URI> getFeaturePaths();

    default String getFeaturePathsKey() {
        return getFeaturePaths().stream()
                .map(URI::toString)
                .collect(Collectors.joining("~"));
    }
}
