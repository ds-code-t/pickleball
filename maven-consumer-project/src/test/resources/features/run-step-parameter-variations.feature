@all @regression @run-scenario-parameters @local-api
Feature: RUN step parameter variations

  # Local fixtures used by the regular-scenario and component-scenario tests.
  # COMPONENT SCENARIO tests point pkb_componentpath back at the normal features directory.

  @run-parameter-fixture-a
  Scenario: RUN parameter fixture A
    * , verify "fixture A" equals "fixture A"

  @run-parameter-fixture-b
  Scenario: RUN parameter fixture B
    * , verify "fixture B" equals "fixture B"


  # ---------------------------------------------------------------------------
  # runTypeText supplied inline
  # ---------------------------------------------------------------------------

  Scenario: Inline SCENARIO type with inline arguments
    * RUN "inlineScenario" SCENARIO: SCENARIO: RUN parameter fixture A
    * , verify "<inlineScenario.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Inline COMPONENT SCENARIO type with inline arguments
    * RUN "inlineComponent" COMPONENT SCENARIO: SCENARIO: RUN parameter fixture A
      | pkb_componentpath           |
      | src/test/resources/features |
    * , verify "<inlineComponent.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Inline SERVICE CALL type with inline arguments
    * RUN "inlineService" SERVICE CALL: SCENARIO: HealthCall
    * , verify "<inlineService.RESPONSE.statusCode>" equals "200"
    * , verify "<inlineService.RESPONSE.body.status>" equals "UP"


  # ---------------------------------------------------------------------------
  # No inlineArgs: selectors supplied entirely by the DataTable
  # No colon is required when inlineArgs is absent.
  # ---------------------------------------------------------------------------

  Scenario: Inline SCENARIO type with table selector and no colon
    * RUN "tableScenario" SCENARIO
      | pkb_featurename              | pkb_name                    |
      | RUN step parameter variations | ^RUN parameter fixture A$ |
    * , verify "<tableScenario.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Inline COMPONENT SCENARIO type with table selector and no colon
    * RUN "tableComponent" COMPONENT SCENARIO
      | pkb_componentpath           | pkb_featurename              | pkb_name                    |
      | src/test/resources/features | RUN step parameter variations | ^RUN parameter fixture B$ |
    * , verify "<tableComponent.SCENARIO NAME>" equals "RUN parameter fixture B"

  Scenario: Inline SERVICE CALL type with table selector and no colon
    * RUN "tableService" SERVICE CALL
      | pkb_featurename                   | pkb_name      | endpoint              |
      | Reusable service call definitions | ^HealthCall$  | http://127.0.0.1:8765 |
    * , verify "<tableService.RESPONSE.statusCode>" equals "200"


  # ---------------------------------------------------------------------------
  # runTypeText omitted: RunType supplied by the DataTable
  # ---------------------------------------------------------------------------

  Scenario: Bare RUN with SCENARIO RunType supplied by the table
    * RUN
      | RunType  | RunKey       | pkb_featurename              | pkb_name                    |
      | SCENARIO | bareScenario | RUN step parameter variations | ^RUN parameter fixture A$ |
    * , verify "<bareScenario.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Bare RUN with COMPONENT SCENARIO RunType supplied by the table
    * RUN
      | RunType            | RunKey        | pkb_componentpath           | pkb_featurename              | pkb_name                    |
      | COMPONENT SCENARIO | bareComponent | src/test/resources/features | RUN step parameter variations | ^RUN parameter fixture B$ |
    * , verify "<bareComponent.SCENARIO NAME>" equals "RUN parameter fixture B"

  Scenario: Bare RUN with SERVICE CALL RunType supplied by the table
    * RUN
      | RunType      | RunKey      | pkb_featurename                   | pkb_name     | endpoint              |
      | SERVICE CALL | bareService | Reusable service call definitions | ^HealthCall$ | http://127.0.0.1:8765 |
    * , verify "<bareService.RESPONSE.statusCode>" equals "200"
    * , verify "<bareService.RESPONSE.body.status>" equals "UP"

  Scenario: Bare RUN with an empty colon and all parameters supplied by the table
    * RUN:
      | RunType  | RunKey            | pkb_featurename              | pkb_name                    |
      | SCENARIO | bareScenarioColon | RUN step parameter variations | ^RUN parameter fixture B$ |
    * , verify "<bareScenarioColon.SCENARIO NAME>" equals "RUN parameter fixture B"


  # ---------------------------------------------------------------------------
  # runTypeText omitted but inlineArgs present.
  # The colon is mandatory because inlineArgs is present.
  # ---------------------------------------------------------------------------

  Scenario: Table SCENARIO RunType with inline scenario selector
    * RUN: SCENARIO: RUN parameter fixture A
      | RunType  | RunKey                 |
      | SCENARIO | tableTypeInlineScenario |
    * , verify "<tableTypeInlineScenario.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Table COMPONENT SCENARIO RunType with inline scenario selector
    * RUN: SCENARIO: RUN parameter fixture B
      | RunType            | RunKey                  | pkb_componentpath           |
      | COMPONENT SCENARIO | tableTypeInlineComponent | src/test/resources/features |
    * , verify "<tableTypeInlineComponent.SCENARIO NAME>" equals "RUN parameter fixture B"

  Scenario: Table SERVICE CALL RunType with inline scenario selector
    * RUN: SCENARIO: HealthCall
      | RunType      | RunKey                 | endpoint              |
      | SERVICE CALL | tableTypeInlineService | http://127.0.0.1:8765 |
    * , verify "<tableTypeInlineService.RESPONSE.statusCode>" equals "200"


  # ---------------------------------------------------------------------------
  # inlineRunKey supplied while RunType comes from the DataTable.
  # With the current regex the space before ':' satisfies the whitespace
  # following the quoted key.
  # ---------------------------------------------------------------------------

  Scenario: Inline RunKey with table RunType
    * RUN "inlineKeyTableType" :
      | RunType  | pkb_featurename              | pkb_name                    |
      | SCENARIO | RUN step parameter variations | ^RUN parameter fixture A$ |
    * , verify "<inlineKeyTableType.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Table RunKey overrides inline RunKey
    * RUN "quotedKeyMustLose" SCENARIO: SCENARIO: RUN parameter fixture A
      | RunKey       |
      | tableKeyWins |
    * , verify "<tableKeyWins.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Blank table RunKey falls back to inline RunKey
    * RUN "inlineKeyFallback" SCENARIO: SCENARIO: RUN parameter fixture B
      | RunKey |
      |        |
    * , verify "<inlineKeyFallback.SCENARIO NAME>" equals "RUN parameter fixture B"


  # ---------------------------------------------------------------------------
  # RunType precedence
  # Nonblank DataTable RunType overrides the inline runTypeText.
  # ---------------------------------------------------------------------------

  Scenario: Table COMPONENT SCENARIO overrides inline SCENARIO
    * RUN SCENARIO: SCENARIO: RUN parameter fixture A
      | RunType            | RunKey               | pkb_componentpath           |
      | COMPONENT SCENARIO | componentOverride    | src/test/resources/features |
    * , verify "<componentOverride.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Table SERVICE CALL overrides inline SCENARIO
    * RUN SCENARIO: SCENARIO: HealthCall
      | RunType      | RunKey          | endpoint              |
      | SERVICE CALL | serviceOverride | http://127.0.0.1:8765 |
    * , verify "<serviceOverride.RESPONSE.statusCode>" equals "200"

  Scenario: Table SCENARIO overrides inline COMPONENT SCENARIO
    * RUN COMPONENT SCENARIO: SCENARIO: RUN parameter fixture B
      | RunType  | RunKey           |
      | SCENARIO | scenarioOverride |
    * , verify "<scenarioOverride.SCENARIO NAME>" equals "RUN parameter fixture B"

  Scenario: Blank table RunType falls back to inline runTypeText
    * RUN "inlineTypeFallback" SERVICE CALL: SCENARIO: HealthCall
      | RunType | endpoint              |
      |         | http://127.0.0.1:8765 |
    * , verify "<inlineTypeFallback.RESPONSE.statusCode>" equals "200"


  # ---------------------------------------------------------------------------
  # inlineArgs forms
  # ---------------------------------------------------------------------------

  Scenario: Inline tag selector
    * RUN "tagSelectedService" SERVICE CALL: %health-full-url
    * , verify "<tagSelectedService.RESPONSE.statusCode>" equals "200"

  Scenario: Inline feature and scenario selectors
    * RUN "featureAndScenario" SCENARIO: FEATURE: RUN step parameter variations SCENARIO: RUN parameter fixture A
    * , verify "<featureAndScenario.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Inline START selector
    * RUN SCENARIO: FEATURE: Scenario step markers SCENARIO: Custom marker component START: component start


  # ---------------------------------------------------------------------------
  # pluralFlag supplied inline by SCENARIOS / COMPONENT SCENARIOS / SERVICE CALLS
  # ---------------------------------------------------------------------------

  Scenario: Plural regular scenarios
    * RUN SCENARIOS
      | pkb_featurename              | pkb_name                       | pkb_order |
      | RUN step parameter variations | ^RUN parameter fixture [AB]$ | lexical   |

  Scenario: Plural component scenarios
    * RUN COMPONENT SCENARIOS
      | pkb_componentpath           | pkb_featurename              | pkb_name                       | pkb_order |
      | src/test/resources/features | RUN step parameter variations | ^RUN parameter fixture [AB]$ | lexical   |

  Scenario: Plural service calls
    * RUN SERVICE CALLS
      | pkb_featurename                   | pkb_name      | RunKey       | endpoint              | status |
      | Reusable service call definitions | ^HealthCall$  | pluralHealth | http://127.0.0.1:8765 | 200    |
      | Reusable service call definitions | ^StatusCall$  | pluralStatus | http://127.0.0.1:8765 | 418    |
    * , verify "<pluralHealth.RESPONSE.statusCode>" equals "200"
    * , verify "<pluralStatus.RESPONSE.statusCode>" equals "418"


  # ---------------------------------------------------------------------------
  # Per-row RunType override under an existing plural RUN form.
  # This verifies RunType is resolved independently for each invocation row.
  # ---------------------------------------------------------------------------

  Scenario: Plural RUN can resolve a different RunType per table row
    * RUN SCENARIOS
      | RunType      | pkb_featurename                   | pkb_name                    | RunKey       | endpoint              |
      | SCENARIO     | RUN step parameter variations     | ^RUN parameter fixture A$   | mixedScenario |                       |
      | SERVICE CALL | Reusable service call definitions | ^HealthCall$                | mixedService  | http://127.0.0.1:8765 |
    * , verify "<mixedScenario.SCENARIO NAME>" equals "RUN parameter fixture A"
    * , verify "<mixedService.RESPONSE.statusCode>" equals "200"
