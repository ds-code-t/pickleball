package tools.dscode.studio.runtime;

/** Safe representation of a value returned from the consumer test JVM. */
public record RuntimeValue(
        String type,
        boolean jsonCompatible,
        Object jsonValue,
        String text
) {
}
