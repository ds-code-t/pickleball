package tools.dscode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.common.servicecalls.CallMap;
import tools.dscode.common.servicecalls.ServiceCallTemplate;

import java.time.Duration;
import java.util.Map;

public class ServiceCallFluentDemo {

    public static void main(String[] args) {
        System.out.println("=== ServiceCallFluentDemo ===");

        // ------------------------------------------------------------
        // 1) Basic GET (Postman Echo) + request(...) mutation
        // ------------------------------------------------------------
        ServiceCallTemplate echoGetTemplate = new ServiceCallTemplate(
                "GET",
                "https://postman-echo.com",
                null,
                "/get",
                Map.of("X-Demo", "true"),
                Map.of("hello", "world"),
                Map.of(),
                null,
                "application/json",
                null
        );

        CallMap getCall = echoGetTemplate.newCall()
                .request(c -> {
                    ObjectNode req = requestNode(c);
                    req.with("headers").put("X-Extra", "added-in-request-logic");
                    req.with("queryParams").put("q", "from-fluent-request");
                })
                .run(c -> System.out.println("[GET] status=" + c.getResponse().statusCode()));

        System.out.println("[GET] response snippet: " + snippet(responseNode(getCall)));


        // ------------------------------------------------------------
        // 2) Basic POST (Postman Echo) + body auto-parsing + resetRequest()
        // ------------------------------------------------------------
        ServiceCallTemplate echoPostTemplate = new ServiceCallTemplate(
                "POST",
                "https://postman-echo.com",
                null,
                "/post",
                Map.of("X-From", "template"),
                Map.of(),
                Map.of(),
                "application/json",
                "application/json",
                """
                { "name": "Widget", "count": 1, "tags": ["a","b"] }
                """
        );

        CallMap postCall = echoPostTemplate.newCall()
                .requestNode(req -> {
                    JsonNode body = req.get("body");
                    if (body != null && body.isObject()) {
                        ((ObjectNode) body).put("count", 2);
                        ((ObjectNode) body).put("note", "mutated before first run");
                    }
                    req.put("endpoint", "/post");
                })
                .run(c -> System.out.println("[POST #1] status=" + c.getResponse().statusCode()));

        System.out.println("[POST #1] request.body now: " + requestNode(postCall).path("body"));
        System.out.println("[POST #1] response snippet : " + snippet(responseNode(postCall)));

        // Reset request back to the original template snapshot
        postCall.resetRequest()
                .run(c -> System.out.println("[POST #2 after resetRequest] status=" + c.getResponse().statusCode()));

        System.out.println("[POST #2] request.body after reset: " + requestNode(postCall).path("body"));


        // ------------------------------------------------------------
        // 3) Retry demo (httpbin always 503) + retryOn(...) + nested fluent calls
        // ------------------------------------------------------------
        ServiceCallTemplate always503Template = new ServiceCallTemplate(
                "GET",
                "https://httpbin.org",
                null,
                "/status/503",
                Map.of("X-Retry-Demo", "true"),
                Map.of(),
                Map.of(),
                null,
                "application/json",
                null
        );

        CallMap retryCall = always503Template.newCall()
                .retryMax(2)                        // 2 retries => up to 3 total attempts
                .retryWait(Duration.ofMillis(300))  // keep demo fast
                .retryOn(c -> {
                    int code = (c.getResponse() == null) ? -1 : c.getResponse().statusCode();
                    System.out.println("[RETRY predicate] lastStatus=" + code);

                    // Nested fluent call inside predicate: mutate request between retries
                    c.requestNode(req -> {
                        ObjectNode headers = req.with("headers");
                        int attempt = headers.has("X-Attempt") ? headers.get("X-Attempt").asInt() : 0;
                        headers.put("X-Attempt", attempt + 1);
                    });

                    return code == 503;
                })
                .run(c -> System.out.println("[503 run] final status=" + c.getResponse().statusCode()));

        System.out.println("[503 run] request.headers at end: " + requestNode(retryCall).path("headers"));


        // ------------------------------------------------------------
        // 4) Retry-on-429 demo (httpbin always 429)
        // ------------------------------------------------------------
        ServiceCallTemplate always429Template = new ServiceCallTemplate(
                "GET",
                "https://httpbin.org",
                null,
                "/status/429",
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                "application/json",
                null
        );

        always429Template.newCall()
                .retryMax(1)
                .retryWait(Duration.ofMillis(200))
                .retryOn(c -> {
                    int code = c.getResponse().statusCode();
                    System.out.println("[429 predicate] lastStatus=" + code);
                    return code == 429;
                })
                .run(c -> System.out.println("[429 run] final status=" + c.getResponse().statusCode()));


        // ------------------------------------------------------------
        // 5) newCall() demo: chain continues on a brand-new CallMap instance
        // ------------------------------------------------------------
        CallMap chained = echoGetTemplate.newCall()
                .requestNode(req -> req.with("queryParams").put("phase", "A"))
                .run(c -> System.out.println("[CHAIN A] status=" + c.getResponse().statusCode()))
                .newCall() // new instance from template snapshot
                .requestNode(req -> req.with("queryParams").put("phase", "B"))
                .run(c -> System.out.println("[CHAIN B] status=" + c.getResponse().statusCode()));

        System.out.println("[CHAIN B] request.queryParams: " + requestNode(chained).path("queryParams"));

        System.out.println("=== Done ===");
    }

    // ---- helpers that only rely on your existing NodeMap behavior ----

    /** Gets the root.request as an ObjectNode (creates if missing). */
    private static ObjectNode requestNode(CallMap c) {
        JsonNode req = (JsonNode) c.get("request");
        if (req == null || !req.isObject()) {
            ObjectNode fresh = CallMap.MAPPER.createObjectNode();
            c.put("request", fresh);
            return fresh;
        }
        return (ObjectNode) req;
    }

    /** Gets the root.response JsonNode (may be null before run). */
    private static JsonNode responseNode(CallMap c) {
        return (JsonNode) c.get("response");
    }

    /** Short, safe printing so logs don’t explode. */
    private static String snippet(JsonNode n) {
        if (n == null) return "null";
        String s = n.toString();
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
