@all @regression @scenario-selection
Feature: Reusable scenario selection

  Scenario: Selection fixture A
    * , verify "A" equals "A"

  Scenario: Selection fixture B
    * , verify "B" equals "B"

  Scenario: Select one regular scenario by inline scenario name
    * RUN SCENARIO: SCENARIO: Selection fixture A

  Scenario: Select one regular scenario by inline feature and scenario name
    * RUN SCENARIO: FEATURE: Reusable scenario selection SCENARIO: Selection fixture B

  Scenario: Select a component scenario from its configured path
    * RUN COMPONENT SCENARIO: SCENARIO: Selection fixture A
      | pkb_componentpath                 |
      | src/test/resources/features       |

  Scenario: Apply ordering and limit before singular scenario validation
    * RUN SCENARIO
      | pkb_featurename             | pkb_name                 | pkb_order | pkb_limit |
      | Reusable scenario selection | ^Selection fixture [AB]$ | lexical   | 1         |

  Scenario: Execute multiple regular scenario matches in returned order
    * RUN SCENARIOS
      | pkb_featurename             | pkb_name                 | pkb_order |
      | Reusable scenario selection | ^Selection fixture [AB]$ | reverse   |

  Scenario: Return silently when no regular scenario selector is supplied
    * RUN SCENARIO
      | pkb_features |
      |              |
    * , verify "no scenario was required" equals "no scenario was required"

  Scenario: Execute the singular scenario convenience form with RunKey
    * SCENARIO: SCENARIO: Selection fixture A
      | RunKey        |
      | inlineScenario |
    * , verify "<inlineScenario.SCENARIO NAME>" equals "Selection fixture A"

  Scenario: Execute the singular component convenience form with RunKey
    * COMPONENT: SCENARIO: Selection fixture B
      | pkb_componentpath           | RunKey         |
      | src/test/resources/features | inlineComponent |
    * , verify "<inlineComponent.SCENARIO NAME>" equals "Selection fixture B"

  @service-call @local-api
  Scenario: Select one service call by inline scenario name
    * RUN "healthByName" SERVICE CALL: SCENARIO: HealthCall
      | endpoint              |
      | http://127.0.0.1:8765 |
    * , verify "<healthByName.RESPONSE.statusCode>" equals "200"
    * , verify "<healthByName.RESPONSE.body.status>" equals "UP"

  @service-call @local-api
  Scenario: RunKey overrides the quoted service-call key
    * RUN "quotedMustLose" SERVICE CALL: SCENARIO: HealthCall
      | RunKey         | endpoint              |
      | qualifiedHealth | http://127.0.0.1:8765 |
    * , verify "<qualifiedHealth.RESPONSE.statusCode>" equals "200"
    * , verify "<qualifiedHealth.RESPONSE.body.status>" equals "UP"

  @service-call @local-api
  Scenario: Apply ordering and limit before singular service-call validation
    * RUN "limitedCall" SERVICE CALL
      | pkb_featurename                   | pkb_name                  | pkb_order | pkb_limit | endpoint              | status |
      | Reusable service call definitions | ^(HealthCall\|StatusCall)$ | lexical   | 1         | http://127.0.0.1:8765 | 200    |
    * , verify "<limitedCall.RESPONSE.statusCode>" equals "200"

  @service-call @local-api
  Scenario: Execute multiple service-call matches in returned order
    * RUN SERVICE CALLS
      | pkb_featurename                   | pkb_name      | RunKey      | endpoint              | status |
      | Reusable service call definitions | ^HealthCall$  | pluralHealth | http://127.0.0.1:8765 | 200    |
      | Reusable service call definitions | ^StatusCall$  | pluralStatus | http://127.0.0.1:8765 | 418    |
    * , verify "<pluralHealth.RESPONSE.statusCode>" equals "200"
    * , verify "<pluralHealth.RESPONSE.body.status>" equals "UP"
    * , verify "<pluralStatus.RESPONSE.statusCode>" equals "418"
    * , verify "<pluralStatus.RESPONSE.body.status>" equals "418"

  @service-call @local-api
  Scenario: Execute the singular CALL convenience form with RunKey
    * CALL: SCENARIO: HealthCall
      | RunKey      | endpoint              |
      | inlineHealth | http://127.0.0.1:8765 |
    * , verify "<inlineHealth.RESPONSE.statusCode>" equals "200"
    * , verify "<inlineHealth.RESPONSE.body.status>" equals "UP"

  Scenario: Return silently when no service-call selector is supplied
    * RUN SERVICE CALL
      | pkb_callpath |
      |              |
    * , verify "no service call was required" equals "no service call was required"
