package tools.dscode;

import tools.dscode.common.servicecalls.ServiceCallTemplate;

import java.util.Map;

/**
 * Predefined templates for public test endpoints.
 *
 * Sources:
 * - https://postman-echo.com
 * - https://httpbin.org
 */
public final class ServiceCallTemplates {

    private ServiceCallTemplates() {}

    public static final ServiceCallTemplate ECHO_GET = new ServiceCallTemplate(
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

    public static final ServiceCallTemplate ECHO_POST_JSON = new ServiceCallTemplate(
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

    /** Deterministic always-503 endpoint (good for retry demos). */
    public static final ServiceCallTemplate HTTPBIN_ALWAYS_503 = new ServiceCallTemplate(
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

    /** Deterministic always-429 endpoint (good for retry demos). */
    public static final ServiceCallTemplate HTTPBIN_ALWAYS_429 = new ServiceCallTemplate(
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
}
