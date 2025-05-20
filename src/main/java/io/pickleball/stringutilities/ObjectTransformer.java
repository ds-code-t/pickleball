package io.pickleball.stringutilities;

import java.util.function.Function;
import java.util.Objects;

public class ObjectTransformer {

    private static final int DEFAULT_MAX_ITERATIONS = 10;
    private static final RuntimeException DEFAULT_EXCEPTION =
            new RuntimeException("Maximum iteration limit exceeded");

    /**
     * Repeatedly applies a transformation function until the result stabilizes or
     * exceeds the default maximum iterations (10).
     *
     * @param input initial input object
     * @param transformation function to apply repeatedly
     * @return final transformed object
     * @throws RuntimeException if maximum iterations exceeded
     */
    public static Object transformUntilStable(Object input,
                                              Function<Object, Object> transformation) {

        return transformUntilStable(input, transformation,
                DEFAULT_MAX_ITERATIONS, DEFAULT_EXCEPTION);
    }

    /**
     * Repeatedly applies a transformation function until the result stabilizes or
     * exceeds the specified maximum iterations.
     *
     * @param input initial input object
     * @param transformation function to apply repeatedly
     * @param maxIterations maximum number of iterations allowed
     * @param maxIterationsException exception to throw when max iterations exceeded
     * @return final transformed object
     * @throws RuntimeException specified exception if maximum iterations exceeded
     */
    public static Object transformUntilStable(Object input,
                                              Function<Object, Object> transformation,
                                              int maxIterations,
                                              RuntimeException maxIterationsException) {
        Object current = input;
        int iterations = 0;

        while (iterations < maxIterations) {
            Object next = transformation.apply(current);
            if(!(next instanceof String))
                return next;
            // Handle null cases and use Objects.equals for proper value equality
            if (Objects.equals(next, current)) {
                return current;
            }

            // Special handling for String value comparison
            if (current instanceof String && next instanceof String) {
                if ((current).equals(next)) {
                    return current;
                }
            }

            current = next;
            iterations++;
        }

        throw maxIterationsException;
    }
}