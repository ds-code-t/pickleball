package tools.dscode.common.servicecalls;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.Map;

public class RestAssuredUtil {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static RequestSpecification buildRequest(JsonNode config) {
        RequestSpecification spec = RestAssured.given();

        if (config.has("baseUri")) {
            spec.baseUri(config.get("baseUri").asText());
        }
        if (config.has("basePath")) {
            spec.basePath(config.get("basePath").asText());
        }
        if (config.has("port")) {
            spec.port(config.get("port").asInt());
        }
        if (config.has("headers")) {
            Map<String, Object> headers = mapper.convertValue(config.get("headers"), new TypeReference<>() {});
            spec.headers(headers);
        }
        if (config.has("cookies")) {
            Map<String, Object> cookies = mapper.convertValue(config.get("cookies"), new TypeReference<>() {});
            spec.cookies(cookies);
        }
        if (config.has("params")) {
            Map<String, Object> params = mapper.convertValue(config.get("params"), new TypeReference<>() {});
            spec.params(params);
        }
        if (config.has("queryParams")) {
            Map<String, Object> queryParams = mapper.convertValue(config.get("queryParams"), new TypeReference<>() {});
            spec.queryParams(queryParams);
        }
        if (config.has("formParams")) {
            Map<String, Object> formParams = mapper.convertValue(config.get("formParams"), new TypeReference<>() {});
            spec.formParams(formParams);
        }
        if (config.has("pathParams")) {
            Map<String, Object> pathParams = mapper.convertValue(config.get("pathParams"), new TypeReference<>() {});
            spec.pathParams(pathParams);
        }
        if (config.has("body")) {
            JsonNode bodyNode = config.get("body");

            // Always give RestAssured a String to avoid its Jackson module auto-discovery
            String bodyString = bodyNode.isTextual()
                    ? bodyNode.asText()
                    : bodyNode.toString(); // JSON text

            spec.body(bodyString);
        }

        if (config.has("contentType")) {
            spec.contentType(config.get("contentType").asText());
        }
        if (config.has("accept")) {
            spec.accept(config.get("accept").asText());
        }
        if (config.has("auth")) {
            JsonNode authNode = config.get("auth");
            if (authNode.has("none")) {
                spec.auth().none();
            } else if (authNode.has("basic")) {
                JsonNode basic = authNode.get("basic");
                spec.auth().basic(basic.get("username").asText(), basic.get("password").asText());
            } else if (authNode.has("digest")) {
                JsonNode digest = authNode.get("digest");
                spec.auth().digest(digest.get("username").asText(), digest.get("password").asText());
            } else if (authNode.has("form")) {
                JsonNode form = authNode.get("form");
                spec.auth().form(form.get("username").asText(), form.get("password").asText());
            } else if (authNode.has("oauth2")) {
                spec.auth().oauth2(authNode.get("oauth2").asText());
            }
        }
        if (config.has("proxy")) {
            JsonNode proxyNode = config.get("proxy");
            String host = proxyNode.get("host").asText();
            int port = proxyNode.has("port") ? proxyNode.get("port").asInt() : -1;
            String scheme = proxyNode.has("scheme") ? proxyNode.get("scheme").asText() : null;
            if (scheme != null) {
                spec.proxy(host, port, scheme);
            } else if (port != -1) {
                spec.proxy(host, port);
            } else {
                spec.proxy(host);
            }
        }
        if (config.has("urlEncodingEnabled")) {
            spec.urlEncodingEnabled(config.get("urlEncodingEnabled").asBoolean());
        }
        return spec;
    }

    public static Response execute(RequestSpecification spec, JsonNode config) {
        String method = config.has("method") ? config.get("method").asText("GET") : "GET";
        String endpoint = config.has("endpoint") ? config.get("endpoint").asText() : "";

        return switch (method.toUpperCase()) {
            case "POST"   -> spec.post(endpoint);
            case "PUT"    -> spec.put(endpoint);
            case "DELETE" -> spec.delete(endpoint);
            case "PATCH"  -> spec.patch(endpoint);
            case "OPTIONS"-> spec.options(endpoint);
            case "HEAD"   -> spec.head(endpoint);
            default       -> spec.get(endpoint);
        };
    }


    public static JsonNode extractResponse(Response response) {
        ObjectNode root = mapper.createObjectNode();

        root.put("statusCode", response.getStatusCode());
        root.put("statusLine", response.getStatusLine());

        ObjectNode headers = mapper.createObjectNode();
        response.getHeaders().forEach(h ->
                headers.put(h.getName(), h.getValue()));
        root.set("headers", headers);

        String contentType = response.getContentType();
        if (contentType != null && contentType.contains("json")) {
            try {
                JsonNode body = mapper.readTree(response.asString());
                root.set("body", body);
            } catch (Exception e) {
                root.put("body", response.asString());
            }
        } else {
            root.put("body", response.asString());
        }

        return root;
    }


}