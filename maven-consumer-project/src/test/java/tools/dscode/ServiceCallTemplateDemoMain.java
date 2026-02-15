package tools.dscode;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.common.servicecalls.CallMap;

import java.time.Duration;

/**
 * Minimal demo that only references ServiceCallTemplates.* and uses fluent API.
 */
public class ServiceCallTemplateDemoMain {

    public static void main(String[] args) {
        System.out.println("=== ServiceCallTemplateDemoMain ===");

        // 1) GET + request mutation + run post-logic
        ServiceCallTemplates.ECHO_GET
                .newCall()
                .requestNode(req -> {
                    req.with("headers").put("X-Extra", "added-in-main");
                    req.with("queryParams").put("q", "from-main");
                })
                .run(c -> System.out.println("[ECHO_GET] status=" + c.getResponse().statusCode()));

        // 2) POST + mutate JSON body + resetRequest + run again
        CallMap post = ServiceCallTemplates.ECHO_POST_JSON
                .newCall()
                .requestNode(req -> {
                    JsonNode body = req.get("body");
                    if (body != null && body.isObject()) {
                        ((ObjectNode) body).put("count", 2);
                        ((ObjectNode) body).put("note", "mutated in main");
                    }
                })
                .run(c -> System.out.println("[ECHO_POST_JSON #1] status=" + c.getResponse().statusCode()));

        post.resetRequest()
                .run(c -> System.out.println("[ECHO_POST_JSON #2 after reset] status=" + c.getResponse().statusCode()));

        // 3) Retry demo (503) with nested fluent call inside retry predicate
        ServiceCallTemplates.HTTPBIN_ALWAYS_503
                .newCall()
                .retryMax(2) // => 3 total attempts
                .retryWait(Duration.ofMillis(300))
                .retryOn(c -> {
                    int code = c.getResponse() == null ? -1 : c.getResponse().statusCode();
                    System.out.println("[503 predicate] lastStatus=" + code);

                    // Nested fluent call inside predicate
                    c.requestNode(req -> {
                        ObjectNode headers = req.with("headers");
                        int attempt = headers.has("X-Attempt") ? headers.get("X-Attempt").asInt() : 0;
                        headers.put("X-Attempt", attempt + 1);
                    });

                    return code == 503;
                })
                .run(c -> System.out.println("[HTTPBIN_ALWAYS_503] final status=" + c.getResponse().statusCode()));

        // 4) Retry demo (429) + newCall chaining
        CallMap chained = ServiceCallTemplates.ECHO_GET
                .newCall()
                .requestNode(req -> req.with("queryParams").put("phase", "A"))
                .run(c -> System.out.println("[CHAIN A] status=" + c.getResponse().statusCode()))
                .newCall()
                .requestNode(req -> req.with("queryParams").put("phase", "B"))
                .run(c -> System.out.println("[CHAIN B] status=" + c.getResponse().statusCode()));

        System.out.println("[CHAIN B] queryParams=" + ((ObjectNode) chained.get("request")).path("queryParams"));

        // 5) 429 retry example (one retry)
        ServiceCallTemplates.HTTPBIN_ALWAYS_429
                .newCall()
                .retryMax(1) // => 2 total attempts
                .retryWait(Duration.ofMillis(200))
                .retryOn(c -> {
                    int code = c.getResponse() == null ? -1 : c.getResponse().statusCode();
                    System.out.println("[429 predicate] lastStatus=" + code);
                    return code == 429;
                })
                .run(c -> System.out.println("[HTTPBIN_ALWAYS_429] final status=" + c.getResponse().statusCode()));

        System.out.println("=== Done ===");
    }
}
