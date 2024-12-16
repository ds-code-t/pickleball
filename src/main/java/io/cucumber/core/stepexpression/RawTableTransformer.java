package io.cucumber.core.stepexpression;

import java.util.List;

@FunctionalInterface
public interface RawTableTransformer<T> {

    T transform(List<List<String>> raw);

}
