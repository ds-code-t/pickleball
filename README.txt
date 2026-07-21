Files:

1. maven-consumer-project/src/test/resources/calls/service-call-definitions.feature
   - Removes responseKey from all examples.
   - Removes MAP SERVICE RESPONSE.
   - Leaves each reusable scenario ending with EXECUTE SERVICE CALL.

2. maven-consumer-project/src/test/resources/features/service-call-execution.feature
   - Removes responseKey from SERVICE CALL tables.
   - Reads results directly through <resolvedCallName.response...>.
   - Quotes template expressions in assertion phrases.
   - Includes a MAP VALUES example.

The MAP VALUES example is optional and may be removed without affecting service-call behavior.
