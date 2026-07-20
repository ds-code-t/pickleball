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

import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

public class RestAssuredUtil {

    public static RequestSpecification buildRequest(JsonNode request) {
        RequestSpecification specification = RestAssured.given();

        applyStandardConfiguration(specification, request);

        if (request.has("config")) {
            request.get("config").fields().forEachRemaining(entry ->
                    apply(specification, entry.getKey(), entry.getValue())
            );
        }

        return specification;
    }

    private static void applyStandardConfiguration(
            RequestSpecification specification,
            JsonNode request
    ) {
        if (request.has("baseUri")) {
            specification.baseUri(request.get("baseUri").asText());
        }
        if (request.has("basePath")) {
            specification.basePath(request.get("basePath").asText());
        }
        if (request.has("port")) {
            specification.port(request.get("port").asInt());
        }
        if (request.has("headers")) {
            specification.headers(asMap(request.get("headers")));
        }
        if (request.has("cookies")) {
            specification.cookies(asMap(request.get("cookies")));
        }
        if (request.has("params")) {
            specification.params(asMap(request.get("params")));
        }
        if (request.has("queryParams")) {
            specification.queryParams(asMap(request.get("queryParams")));
        }
        if (request.has("formParams")) {
            specification.formParams(asMap(request.get("formParams")));
        }
        if (request.has("pathParams")) {
            specification.pathParams(asMap(request.get("pathParams")));
        }
        if (request.has("body")) {
            JsonNode body = request.get("body");
            specification.body(body.isTextual() ? body.asText() : body.toString());
        }
        if (request.has("contentType")) {
            specification.contentType(request.get("contentType").asText());
        }
        if (request.has("accept")) {
            specification.accept(request.get("accept").asText());
        }
        if (request.has("auth")) {
            applyAuthentication(specification, request.get("auth"));
        }
        if (request.has("proxy")) {
            applyProxy(specification, request.get("proxy"));
        }
        if (request.has("urlEncodingEnabled")) {
            specification.urlEncodingEnabled(request.get("urlEncodingEnabled").asBoolean());
        }
    }

    public static RequestSpecification logRequestAndResponse(
            RequestSpecification specification,
            PrintStream logStream
    ) {
        return specification
                .filter(new RequestLoggingFilter(logStream))
                .filter(new ResponseLoggingFilter(logStream));
    }

    /**
     * Executes a request using explicit method and endpoint values.
     */
    public static Response execute(
            RequestSpecification specification,
            String method,
            String endpoint
    ) {
        String requestMethod = method == null || method.isBlank() ? "GET" : method;
        String requestEndpoint = endpoint == null ? "" : endpoint;
        return specification.request(requestMethod, requestEndpoint);
    }

    /**
     * Backward-compatible overload used by CallMap.
     */
    public static Response execute(
            RequestSpecification specification,
            JsonNode request
    ) {
        return execute(
                specification,
                request.path("method").asText("GET"),
                request.path("endpoint").asText("")
        );
    }

    public static JsonNode extractResponse(Response response) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("statusCode", response.getStatusCode());
        result.put("statusLine", response.getStatusLine());
        result.put("contentType", response.getContentType());

        ObjectNode headers = MAPPER.createObjectNode();
        response.getHeaders().forEach(header ->
                headers.put(header.getName(), header.getValue())
        );
        result.set("headers", headers);

        String body = response.asString();
        if (response.getContentType() != null
                && response.getContentType().contains("json")) {
            try {
                result.set("body", MAPPER.readTree(body));
            } catch (Exception ignored) {
                result.put("body", body);
            }
        } else {
            result.put("body", body);
        }

        return result;
    }

    private static Map<String, ?> asMap(JsonNode value) {
        return MAPPER.convertValue(value, new TypeReference<>() { });
    }

    private static void applyAuthentication(
            RequestSpecification specification,
            JsonNode authentication
    ) {
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
        String host = proxy.path("host").asText();
        int port = proxy.path("port").asInt(-1);
        String scheme = proxy.path("scheme").asText("");

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
                    || (!method.isVarArgs()
                    && values.size() != fixedArguments)) {
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
}
