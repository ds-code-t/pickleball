# REST Assured compatibility fix

This revision restores the existing Pickleball `RestAssuredUtil` API while retaining the new service-call steps.

## Fixed

- Restored `execute(RequestSpecification, JsonNode)` for `CallMap`.
- Kept `execute(RequestSpecification, String, String)` for the new service-call step.
- Restored existing top-level request configuration support used by `CallMap`:
  - `baseUri`, `basePath`, `port`
  - headers, cookies and parameters
  - body, content type and accept
  - authentication, proxy and URL encoding
- Retained nested `request.config` reflective configuration.
- Added the explicit `LogForwarder.logInfo` static import to `ServiceCallSteps`.

No changes to `CallMap.java` are required.
