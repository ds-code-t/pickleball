# Consumer service-call feature update

Replace these two files in the consumer project:

- `maven-consumer-project/src/test/resources/calls/service-call-definitions.feature`
- `maven-consumer-project/src/test/resources/features/service-call-execution.feature`

## What changed

The reusable component scenarios now build their service-call data entirely with:

```gherkin
MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
MAP "REQUEST.cookies" TABLE VALUES TO SCENARIO MAP
MAP "CONFIGURATION" TABLE VALUES TO SCENARIO MAP
MAP "REQUEST.body" OBJECT VALUE TO SCENARIO MAP
MAP "REQUEST.body" TEXT VALUE TO SCENARIO MAP
```

Every `REQUEST.endpoint` contains the complete URL, including scheme, host, port,
and path. `CONFIGURATION` is therefore reserved for REST Assured behavior such
as URL encoding and relaxed HTTPS validation.

## Coverage

The calling feature exercises:

- inline `%` tag selection;
- `Run Tags` selection;
- quoted object names;
- exact `Call Key` precedence;
- scenario-name fallback;
- nested header, query-parameter, and cookie mappings;
- JSON bodies through the `OBJECT` DocString mapper;
- XML bodies through the `TEXT` DocString mapper;
- general REST Assured configuration;
- normal handling of HTTP 4xx/5xx responses;
- repeated-key replacement;
- HTTP 204/no-content response capture;
- `END SCENARIO` followed by the synthetic finalizer.

## Prerequisite

These tests expect the consumer project's local test server to expose the
existing service-call test endpoints:

- `/api/service-calls/inspect`
- `/api/service-calls/no-content/{itemId}`
- `/api/health`
- `/soap/calculator`

No Java file is included in this archive.
