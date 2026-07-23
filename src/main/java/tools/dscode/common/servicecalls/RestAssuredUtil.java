package tools.dscode.common.servicecalls;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.restassured.RestAssured;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

public final class RestAssuredUtil {

    private static final Set<String> STANDARD_PROPERTIES = Set.of(
            "baseUri",
            "basePath",
            "port",
            "headers",
            "cookies",
            "params",
            "queryParams",
            "formParams",
            "pathParams",
            "body",
            "contentType",
            "accept",
            "auth",
            "proxy",
            "urlEncodingEnabled"
    );

    private RestAssuredUtil() {
    }

    /**
     * Builds a request from the service-call REQUEST object only.
     * Kept for source compatibility with existing Java callers.
     */
    public static RequestSpecification buildRequest(JsonNode request) {
        return buildRequest(request, MAPPER.createObjectNode());
    }

    /**
     * Builds a request from separate REQUEST and CONFIGURATION objects.
     *
     * <p>CONFIGURATION is applied first. REQUEST is applied second so explicit
     * request values take precedence where REST Assured uses replacement
     * semantics. Unknown CONFIGURATION properties are treated as REST Assured
     * method paths and invoked reflectively.</p>
     */
    public static RequestSpecification buildRequest(
            JsonNode request,
            JsonNode configuration
    ) {
        ObjectNode requestObject = requireObject(request, "REQUEST");
        ObjectNode configurationObject = optionalObject(configuration, "CONFIGURATION");

        RequestSpecification specification = RestAssured.given();

        applyStandardProperties(specification, configurationObject);
        applyConfiguredMethods(specification, configurationObject);
        applyStandardProperties(specification, requestObject);

        // Compatibility for older request objects that nested reflective
        // REST Assured calls under request.config.
        JsonNode legacyConfiguration = requestObject.get("config");
        if (legacyConfiguration != null && !legacyConfiguration.isNull()) {
            ObjectNode legacyObject = requireObject(legacyConfiguration, "request.config");
            applyStandardProperties(specification, legacyObject);
            applyConfiguredMethods(specification, legacyObject);
        }

        return specification;
    }

    public static RequestSpecification logRequestAndResponse(
            RequestSpecification specification,
            PrintStream logStream
    ) {
        return specification
                .filter(new RequestLoggingFilter(logStream))
                .filter(new ResponseLoggingFilter(logStream));
    }

    public static Response execute(
            RequestSpecification specification,
            String method,
            String endpoint
    ) {
        String requestMethod = method == null || method.isBlank()
                ? "GET"
                : method.trim().toUpperCase(java.util.Locale.ROOT);
        String requestEndpoint = endpoint == null ? "" : endpoint.trim();
        return specification.request(requestMethod, requestEndpoint);
    }

    /**
     * Kept for source compatibility with existing Java callers.
     */
    public static Response execute(
            RequestSpecification specification,
            JsonNode request
    ) {
        ObjectNode requestObject = requireObject(request, "REQUEST");
        return execute(
                specification,
                requestObject.path("method").asText("GET"),
                requestObject.path("endpoint").asText("")
        );
    }

    /**
     * Converts a REST Assured response into a mapping-friendly ObjectNode.
     * A null response produces an empty object rather than an execution error.
     */
    public static ObjectNode extractResponse(Response response) {
        ObjectNode result = MAPPER.createObjectNode();
        if (response == null) {
            return result;
        }

        result.put("statusCode", response.getStatusCode());
        result.put("statusLine", response.getStatusLine());
        result.put("contentType", response.getContentType());

        ObjectNode headers = MAPPER.createObjectNode();
        response.getHeaders().forEach(header ->
                headers.put(header.getName(), header.getValue())
        );
        result.set("headers", headers);

        String body = response.asString();
        String contentType = response.getContentType();
        if (body != null
                && !body.isBlank()
                && contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT).contains("json")) {
            try {
                JsonNode parsed = MAPPER.readTree(body);
                if (parsed == null) {
                    result.put("body", body);
                } else {
                    result.set("body", parsed);
                }
            } catch (Exception ignored) {
                result.put("body", body);
            }
        } else {
            result.put("body", body == null ? "" : body);
        }

        return result;
    }

    private static void applyStandardProperties(
            RequestSpecification specification,
            ObjectNode values
    ) {
        if (values.has("baseUri")) {
            specification.baseUri(values.get("baseUri").asText());
        }
        if (values.has("basePath")) {
            specification.basePath(values.get("basePath").asText());
        }
        if (values.has("port")) {
            specification.port(values.get("port").asInt());
        }
        if (values.has("headers")) {
            specification.headers(asMap(values.get("headers")));
        }
        if (values.has("cookies")) {
            specification.cookies(asMap(values.get("cookies")));
        }
        if (values.has("params")) {
            specification.params(asMap(values.get("params")));
        }
        if (values.has("queryParams")) {
            specification.queryParams(asMap(values.get("queryParams")));
        }
        if (values.has("formParams")) {
            specification.formParams(asMap(values.get("formParams")));
        }
        if (values.has("pathParams")) {
            specification.pathParams(asMap(values.get("pathParams")));
        }
        if (values.has("body")) {
            JsonNode body = values.get("body");
            specification.body(body.isTextual() ? body.asText() : body.toString());
        }
        if (values.has("contentType")) {
            specification.contentType(values.get("contentType").asText());
        }
        if (values.has("accept")) {
            specification.accept(values.get("accept").asText());
        }
        if (values.has("auth")) {
            applyAuthentication(specification, values.get("auth"));
        }
        if (values.has("proxy")) {
            applyProxy(specification, values.get("proxy"));
        }
        if (values.has("urlEncodingEnabled")) {
            specification.urlEncodingEnabled(values.get("urlEncodingEnabled").asBoolean());
        }
    }

    private static void applyConfiguredMethods(
            RequestSpecification specification,
            ObjectNode configuration
    ) {
        configuration.fields().forEachRemaining(entry -> {
            if (!STANDARD_PROPERTIES.contains(entry.getKey())) {
                apply(specification, entry.getKey(), entry.getValue());
            }
        });
    }

    private static Map<String, ?> asMap(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("REST Assured map property must be an object");
        }
        return MAPPER.convertValue(value, new TypeReference<>() { });
    }

    private static void applyAuthentication(
            RequestSpecification specification,
            JsonNode authentication
    ) {
        if (authentication == null || !authentication.isObject()) {
            throw new IllegalArgumentException("REST Assured auth must be an object");
        }

        if (authentication.has("none")) {
            specification.auth().none();
        } else if (authentication.has("basic")) {
            JsonNode basic = authentication.get("basic");
            specification.auth().basic(
                    basic.path("username").asText(),
                    basic.path("password").asText()
            );
        } else if (authentication.has("digest")) {
            JsonNode digest = authentication.get("digest");
            specification.auth().digest(
                    digest.path("username").asText(),
                    digest.path("password").asText()
            );
        } else if (authentication.has("form")) {
            JsonNode form = authentication.get("form");
            specification.auth().form(
                    form.path("username").asText(),
                    form.path("password").asText()
            );
        } else if (authentication.has("oauth2")) {
            specification.auth().oauth2(authentication.get("oauth2").asText());
        }
    }

    private static void applyProxy(
            RequestSpecification specification,
            JsonNode proxy
    ) {
        if (proxy == null || !proxy.isObject()) {
            throw new IllegalArgumentException("REST Assured proxy must be an object");
        }

        String host = proxy.path("host").asText();
        int port = proxy.path("port").asInt(-1);
        String scheme = proxy.path("scheme").asText("");

        if (host.isBlank()) {
            throw new IllegalArgumentException("REST Assured proxy.host cannot be blank");
        }

        if (!scheme.isBlank()) {
            specification.proxy(host, port, scheme);
        } else if (port >= 0) {
            specification.proxy(host, port);
        } else {
            specification.proxy(host);
        }
    }

    private static void apply(Object target, String methodPath, JsonNode value) {
        Object current = target;
        String[] names = methodPath.split("\\.");

        for (int index = 0; index < names.length; index++) {
            JsonNode arguments = index == names.length - 1
                    ? value
                    : MAPPER.createArrayNode();
            current = invoke(current, names[index], arguments);
        }
    }

    private static Object invoke(Object target, String methodName, JsonNode value) {
        List<JsonNode> values = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(values::add);
        } else if (!(value.isTextual() && value.asText().isBlank())) {
            values.add(value);
        }

        Exception last = null;
        for (Method method : target.getClass().getMethods()) {
            int fixedArguments = method.isVarArgs()
                    ? method.getParameterCount() - 1
                    : method.getParameterCount();

            if (!method.getName().equals(methodName)
                    || values.size() < fixedArguments
                    || (!method.isVarArgs() && values.size() != fixedArguments)) {
                continue;
            }

            try {
                Object[] arguments = new Object[method.getParameterCount()];
                for (int index = 0; index < fixedArguments; index++) {
                    arguments[index] = convert(
                            values.get(index),
                            method.getParameterTypes()[index]
                    );
                }

                if (method.isVarArgs()) {
                    Class<?> componentType = method
                            .getParameterTypes()[fixedArguments]
                            .getComponentType();
                    Object remaining = java.lang.reflect.Array.newInstance(
                            componentType,
                            values.size() - fixedArguments
                    );

                    for (int index = fixedArguments; index < values.size(); index++) {
                        java.lang.reflect.Array.set(
                                remaining,
                                index - fixedArguments,
                                convert(values.get(index), componentType)
                        );
                    }
                    arguments[fixedArguments] = remaining;
                }

                Object result = method.invoke(target, arguments);
                return result == null ? target : result;
            } catch (Exception exception) {
                last = exception;
            }
        }

        throw new IllegalArgumentException(
                "No REST Assured method matched "
                        + methodName
                        + " with "
                        + values.size()
                        + " argument(s)",
                last
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convert(JsonNode value, Class<?> type) {
        if (type == String.class) return value.asText();
        if (type == int.class || type == Integer.class) return value.asInt();
        if (type == long.class || type == Long.class) return value.asLong();
        if (type == boolean.class || type == Boolean.class) return value.asBoolean();
        if (type == double.class || type == Double.class) return value.asDouble();
        if (type == float.class || type == Float.class) return (float) value.asDouble();
        if (type.isEnum()) {
            return Enum.valueOf((Class<? extends Enum>) type, value.asText());
        }
        return MAPPER.convertValue(value, type);
    }

    private static ObjectNode requireObject(JsonNode value, String description) {
        if (value instanceof ObjectNode objectNode) {
            return objectNode;
        }
        throw new IllegalArgumentException(description + " must be an object");
    }

    private static ObjectNode optionalObject(JsonNode value, String description) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return MAPPER.createObjectNode();
        }
        return requireObject(value, description);
    }
}
