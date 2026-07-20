package tools.dscode.common.servicecalls;

import io.restassured.specification.RequestSpecification;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registry for REST Assured settings that cannot be represented as scalar
 * Gherkin data, such as filters, custom object mappers, request specifications,
 * authentication schemes, SSL stores, or application-specific hooks.
 */
public final class RestAssuredRequestCustomizerRegistry {

    private static final Map<String, Consumer<RequestSpecification>> CUSTOMIZERS =
            new ConcurrentHashMap<>();

    private RestAssuredRequestCustomizerRegistry() {
    }

    public static void register(
            String name,
            Consumer<RequestSpecification> customizer
    ) {
        String normalizedName = requireName(name);
        CUSTOMIZERS.put(normalizedName, Objects.requireNonNull(customizer, "customizer"));
    }

    public static void unregister(String name) {
        CUSTOMIZERS.remove(requireName(name));
    }

    public static void clear() {
        CUSTOMIZERS.clear();
    }

    public static boolean contains(String name) {
        return CUSTOMIZERS.containsKey(requireName(name));
    }

    public static void apply(String name, RequestSpecification specification) {
        String normalizedName = requireName(name);
        Consumer<RequestSpecification> customizer = CUSTOMIZERS.get(normalizedName);
        if (customizer == null) {
            throw new IllegalArgumentException(
                    "No REST Assured request customizer is registered as '"
                            + normalizedName + "'"
            );
        }
        customizer.accept(Objects.requireNonNull(specification, "specification"));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Customizer name cannot be blank");
        }
        return name.trim();
    }
}
