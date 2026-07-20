# Pickleball service-call endpoint fix

This package contains the complete current service-call implementation and consumer-project tests.

## Main correction

The previous request log showed:

```text
Request URI: http://localhost:8080/
```

That is REST Assured's default URI and showed that the stored request endpoint was not being read during execution.

The corrected implementation:

1. Reads the service-call request with `NodeMap.get("<call name>.request")`.
2. Uses one absolute endpoint value such as `http://127.0.0.1:8765/api/items/73`.
3. Fails the call immediately into its response error object when no endpoint was defined, instead of contacting `localhost:8080`.
4. Keeps `REQUEST CONFIGURATION` only as an optional step for other REST Assured request-specification settings.
5. Logs the stored request object before invoking REST Assured.

## Included files

- `src/main/java/tools/dscode/coredefinitions/ModularScenarios.java`
- `src/main/java/tools/dscode/coredefinitions/ServiceCallScenarios.java`
- `src/main/java/tools/dscode/coredefinitions/ServiceCallSteps.java`
- `src/main/java/tools/dscode/common/servicecalls/RestAssuredUtil.java`
- `maven-consumer-project/src/test/java/com/example/pickleball/support/LocalTestSite.java`
- `maven-consumer-project/src/test/resources/calls/service-call-definitions.feature`
- `maven-consumer-project/src/test/resources/features/service-call-execution.feature`
- `docs/service-call-scenarios.md`
- `docs/README.md`

Extract the archive over the repository root and run the consumer-project tests normally.
