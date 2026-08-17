@all @regression @run-scenario-parameters @local-api
Feature: RUN step parameter variations

  @run-parameter-fixture-a
  Scenario: RUN parameter fixture A
    * , verify "fixture A" equals "fixture A"

  @run-parameter-fixture-b
  Scenario: RUN parameter fixture B
    * , verify "fixture B" equals "fixture B"

  # Inline run type and path/tag selectors remain supported as shorthand.

  Scenario: Inline SCENARIO type with scenario selector
    * RUN "inlineScenario" SCENARIO: RUN parameter fixture A
    * , verify "<inlineScenario.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Inline COMPONENT SCENARIO type with scenario selector
    * RUN "inlineComponent" COMPONENT SCENARIO: RUN parameter fixture A
      | pkb_componentpath           |
      | src/test/resources/features |
    * , verify "<inlineComponent.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Inline SERVICE CALL type with scenario selector
    * RUN "inlineService" SERVICE CALL: HealthCall
    * , verify "<inlineService.RESPONSE.statusCode>" equals "200"
    * , verify "<inlineService.RESPONSE.body.status>" equals "UP"

  Scenario: Inline tag selector still works
    * RUN "tagSelectedService" SERVICE CALL: %health-full-url
    * , verify "<tagSelectedService.RESPONSE.statusCode>" equals "200"

  Scenario: Inline feature and scenario selector
    * RUN "featureAndScenario" SCENARIO: RUN step parameter variations.RUN parameter fixture A
    * , verify "<featureAndScenario.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Inline feature scenario and marker selector
    * RUN SCENARIO: Scenario step markers.Custom marker component.component start

  # Selectors supplied entirely by DataTable columns.

  Scenario: Inline SCENARIO type with table selector
    * RUN "tableScenario" SCENARIO
      | pkb_featurename               | pkb_name                    |
      | RUN step parameter variations | ^RUN parameter fixture A$ |
    * , verify "<tableScenario.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Inline COMPONENT SCENARIO type with table selector
    * RUN "tableComponent" COMPONENT SCENARIO
      | pkb_componentpath           | pkb_featurename               | pkb_name                    |
      | src/test/resources/features | RUN step parameter variations | ^RUN parameter fixture B$ |
    * , verify "<tableComponent.SCENARIO NAME>" equals "RUN parameter fixture B"

  Scenario: Inline SERVICE CALL type with table selector
    * RUN "tableService" SERVICE CALL
      | pkb_featurename                   | pkb_name     | endpoint              |
      | Reusable service call definitions | ^HealthCall$ | http://127.0.0.1:8765 |
    * , verify "<tableService.RESPONSE.statusCode>" equals "200"

  # Canonical RUN form: each DataTable row is an independent invocation.

  Scenario: Bare RUN mixes singular RunTypes in one table
    * RUN
      | RunType            | RunKey              | Run Tags                 | pkb_componentpath           | endpoint              |
      | SCENARIO           | mixedTableScenario  | @run-parameter-fixture-a |                             |                       |
      | COMPONENT SCENARIO | mixedTableComponent | @run-parameter-fixture-b | src/test/resources/features |                       |
      | SERVICE CALL       | mixedTableService   | %health-full-url          |                             | http://127.0.0.1:8765 |
    * , verify "<mixedTableScenario.SCENARIO NAME>" equals "RUN parameter fixture A"
    * , verify "<mixedTableComponent.SCENARIO NAME>" equals "RUN parameter fixture B"
    * , verify "<mixedTableService.RESPONSE.statusCode>" equals "200"

  Scenario: Bare RUN with SCENARIO RunType supplied by table
    * RUN
      | RunType  | RunKey       | pkb_featurename               | pkb_name                    |
      | SCENARIO | bareScenario | RUN step parameter variations | ^RUN parameter fixture A$ |
    * , verify "<bareScenario.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Bare RUN with COMPONENT SCENARIO RunType supplied by table
    * RUN
      | RunType            | RunKey        | pkb_componentpath           | pkb_featurename               | pkb_name                    |
      | COMPONENT SCENARIO | bareComponent | src/test/resources/features | RUN step parameter variations | ^RUN parameter fixture B$ |
    * , verify "<bareComponent.SCENARIO NAME>" equals "RUN parameter fixture B"

  Scenario: Bare RUN with SERVICE CALL RunType supplied by table
    * RUN
      | RunType      | RunKey      | pkb_featurename                   | pkb_name     | endpoint              |
      | SERVICE CALL | bareService | Reusable service call definitions | ^HealthCall$ | http://127.0.0.1:8765 |
    * , verify "<bareService.RESPONSE.statusCode>" equals "200"
    * , verify "<bareService.RESPONSE.body.status>" equals "UP"

  Scenario: Table plural RunType allows multiple matches for only that row
    * RUN
      | RunType   | RunKey        | pkb_featurename               | pkb_name                       | pkb_order |
      | SCENARIOS | pluralByTable | RUN step parameter variations | ^RUN parameter fixture [AB]$ | lexical   |
    * , verify "<pluralByTable[][0].SCENARIO NAME>" equals "RUN parameter fixture A"
    * , verify "<pluralByTable[][1].SCENARIO NAME>" equals "RUN parameter fixture B"
    * , verify "<pluralByTable.SCENARIO NAME>" equals "RUN parameter fixture B"

  Scenario: Table plural RunType overrides inline singular RunType
    * RUN SCENARIO
      | RunType   | RunKey             | pkb_featurename               | pkb_name                       | pkb_order |
      | SCENARIOS | tablePluralOverride | RUN step parameter variations | ^RUN parameter fixture [AB]$ | lexical   |
    * , verify "<tablePluralOverride[][0].SCENARIO NAME>" equals "RUN parameter fixture A"
    * , verify "<tablePluralOverride[][1].SCENARIO NAME>" equals "RUN parameter fixture B"

  Scenario: Deferred RUN saves explicit RETURN after the selected scenario completes
    * MAP TABLE VALUES TO SCENARIO MAP
      | url    | http://127.0.0.1:8765 |
      | client | deferred-run-client   |
      | scope  | inventory.read        |
    * RUN
      | RunType      | RunKey         | Run Tags        |
      | SERVICE CALL | deferredReturn | %TokenValueCall |
    * , verify "<deferredReturn>" equals "inline-deferred-run-client-inventory-read"

  Scenario: Table SCENARIO RunType with inline path selector
    * RUN: RUN parameter fixture A
      | RunType  | RunKey                  |
      | SCENARIO | tableTypeInlineScenario |
    * , verify "<tableTypeInlineScenario.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Table COMPONENT SCENARIO RunType with inline path selector
    * RUN: RUN parameter fixture B
      | RunType            | RunKey                   | pkb_componentpath           |
      | COMPONENT SCENARIO | tableTypeInlineComponent | src/test/resources/features |
    * , verify "<tableTypeInlineComponent.SCENARIO NAME>" equals "RUN parameter fixture B"

  Scenario: Table SERVICE CALL RunType with inline path selector
    * RUN: HealthCall
      | RunType      | RunKey                 | endpoint              |
      | SERVICE CALL | tableTypeInlineService | http://127.0.0.1:8765 |
    * , verify "<tableTypeInlineService.RESPONSE.statusCode>" equals "200"

  # RunKey precedence.

  Scenario: Table RunKey overrides inline RunKey
    * RUN "quotedKeyMustLose" SCENARIO: RUN parameter fixture A
      | RunKey       |
      | tableKeyWins |
    * , verify "<tableKeyWins.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Blank table RunKey falls back to inline RunKey
    * RUN "inlineKeyFallback" SCENARIO: RUN parameter fixture B
      | RunKey |
      |        |
    * , verify "<inlineKeyFallback.SCENARIO NAME>" equals "RUN parameter fixture B"

  # RunType precedence. A nonblank table RunType overrides the inline type and multiplicity.

  Scenario: Table COMPONENT SCENARIO overrides inline SCENARIO
    * RUN SCENARIO: RUN parameter fixture A
      | RunType            | RunKey            | pkb_componentpath           |
      | COMPONENT SCENARIO | componentOverride | src/test/resources/features |
    * , verify "<componentOverride.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Table SERVICE CALL overrides inline SCENARIO
    * RUN SCENARIO: HealthCall
      | RunType      | RunKey          | endpoint              |
      | SERVICE CALL | serviceOverride | http://127.0.0.1:8765 |
    * , verify "<serviceOverride.RESPONSE.statusCode>" equals "200"

  Scenario: Table SCENARIO overrides inline COMPONENT SCENARIO
    * RUN COMPONENT SCENARIO: RUN parameter fixture B
      | RunType  | RunKey           |
      | SCENARIO | scenarioOverride |
    * , verify "<scenarioOverride.SCENARIO NAME>" equals "RUN parameter fixture B"

  Scenario: Blank table RunType falls back to inline run type
    * RUN "inlineTypeFallback" SERVICE CALL: HealthCall
      | RunType | endpoint              |
      |         | http://127.0.0.1:8765 |
    * , verify "<inlineTypeFallback.RESPONSE.statusCode>" equals "200"

  # Inline selectors overwrite their equivalent table selector columns.

  Scenario: Inline scenario path overrides table pkb_name
    * RUN "inlineNameWins" SCENARIO: RUN parameter fixture A
      | pkb_name                  |
      | ^RUN parameter fixture B$ |
    * , verify "<inlineNameWins.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Inline feature path overrides table pkb_featurename
    * RUN "inlineFeatureWins" SCENARIO: RUN step parameter variations.RUN parameter fixture A
      | pkb_featurename             |
      | This feature does not exist |
    * , verify "<inlineFeatureWins.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Inline marker overrides table Step_Marker
    * RUN SCENARIO: Scenario step markers.Custom marker component.component start
      | Step_Marker        |
      | marker that misses |

  Scenario: Table Step_Marker applies when inline marker is absent
    * RUN SCENARIO: Scenario step markers.Custom marker component
      | Step_Marker     |
      | component start |

  # Run Tags and pkb_tags table selectors remain supported.

  Scenario: Run Tags selects a regular scenario
    * RUN "runTagsScenario" SCENARIO
      | Run Tags                 |
      | @run-parameter-fixture-a |
    * , verify "<runTagsScenario.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: pkb_tags selects a regular scenario
    * RUN "pkbTagsScenario" SCENARIO
      | pkb_tags                 |
      | @run-parameter-fixture-b |
    * , verify "<pkbTagsScenario.SCENARIO NAME>" equals "RUN parameter fixture B"

  # Ordering, limits, and inline plural shorthand remain supported.

  Scenario: Ordering and limit are applied before singular validation
    * RUN "limitedScenario" SCENARIO
      | pkb_featurename               | pkb_name                       | pkb_order | pkb_limit |
      | RUN step parameter variations | ^RUN parameter fixture [AB]$ | lexical   | 1         |
    * , verify "<limitedScenario.SCENARIO NAME>" equals "RUN parameter fixture A"

  Scenario: Plural regular scenarios inline shorthand
    * RUN SCENARIOS
      | pkb_featurename               | pkb_name                       | pkb_order |
      | RUN step parameter variations | ^RUN parameter fixture [AB]$ | lexical   |

  Scenario: Plural component scenarios inline shorthand
    * RUN COMPONENT SCENARIOS
      | pkb_componentpath           | pkb_featurename               | pkb_name                       | pkb_order |
      | src/test/resources/features | RUN step parameter variations | ^RUN parameter fixture [AB]$ | lexical   |

  Scenario: Plural service calls inline shorthand
    * RUN SERVICE CALLS
      | pkb_featurename                   | pkb_name     | RunKey       | endpoint              | status |
      | Reusable service call definitions | ^HealthCall$ | pluralHealth | http://127.0.0.1:8765 | 200    |
      | Reusable service call definitions | ^StatusCall$ | pluralStatus | http://127.0.0.1:8765 | 418    |
    * , verify "<pluralHealth.RESPONSE.statusCode>" equals "200"
    * , verify "<pluralStatus.RESPONSE.statusCode>" equals "418"
