package io.cucumber.core.stepexpression;

@FunctionalInterface
public interface DocStringTransformer<T> {

    T transform(String docString, String contentType);

}
