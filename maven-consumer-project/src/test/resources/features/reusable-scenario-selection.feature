@all @regression @scenario-selection
Feature: Reusable scenario selection

  Scenario: Selection fixture A
    * , verify "A" equals "A"

  Scenario: Selection fixture B
    * , verify "B" equals "B"

  Scenario: Select one component by inline scenario name
    * RUN SCENARIO: Selection fixture A

  Scenario: Select one component by inline feature and scenario name
    * RUN SCENARIO: Reusable scenario selection.Selection fixture B

  Scenario: Apply ordering and limit before singular component validation
    * RUN SCENARIO
      | pkb_featurename             | pkb_name                    | pkb_order | pkb_limit |
      | Reusable scenario selection | ^Selection fixture [AB]$    | lexical   | 1         |

  Scenario: Execute multiple component matches in returned order
    * RUN SCENARIOS
      | pkb_featurename             | pkb_name                    | pkb_order |
      | Reusable scenario selection | ^Selection fixture [AB]$    | reverse   |

  Scenario: Return silently when no component selector is supplied
    * RUN SCENARIO
      | pkb_features |
      |              |
    * , verify "no component was required" equals "no component was required"

  @service-call @local-api
  Scenario: Select one service call by inline scenario name
    * "healthByName" SERVICE CALL: HealthCall
      | endpoint              |
      | http://127.0.0.1:8765 |
    * , verify "<healthByName.RESPONSE.statusCode>" equals "200"
    * , verify "<healthByName.RESPONSE.body.status>" equals "UP"

  @service-call @local-api
  Scenario: Select one service call by inline feature and scenario name
    * "qualifiedHealth" SERVICE CALL: Reusable service call definitions.HealthCall
      | endpoint              |
      | http://127.0.0.1:8765 |
    * , verify "<qualifiedHealth.RESPONSE.statusCode>" equals "200"
    * , verify "<qualifiedHealth.RESPONSE.body.status>" equals "UP"

  @service-call @local-api
  Scenario: Apply ordering and limit before singular service-call validation
    * "limitedCall" SERVICE CALL
      | pkb_featurename                  | pkb_name                    | pkb_order | pkb_limit | endpoint              | status |
      | Reusable service call definitions | ^(HealthCall\|StatusCall)$   | lexical   | 1         | http://127.0.0.1:8765 | 200    |
    * , verify "<limitedCall.RESPONSE.statusCode>" equals "200"

  @service-call @local-api
  Scenario: Execute multiple service-call matches in returned order
    * SERVICE CALLS
      | pkb_featurename                  | pkb_name                    | pkb_order | pkb_limit | endpoint              | status |
      | Reusable service call definitions | ^(HealthCall\|StatusCall)$   | lexical   | 2         | http://127.0.0.1:8765 | 418    |
    * , verify "<HealthCall.RESPONSE.statusCode>" equals "200"
    * , verify "<HealthCall.RESPONSE.body.status>" equals "UP"
    * , verify "<StatusCall.RESPONSE.statusCode>" equals "418"
    * , verify "<StatusCall.RESPONSE.body.status>" equals "418"

  Scenario: Return silently when no service-call selector is supplied
    * SERVICE CALL
      | pkb_features |
      |              |
    * , verify "no service call was required" equals "no service call was required"
