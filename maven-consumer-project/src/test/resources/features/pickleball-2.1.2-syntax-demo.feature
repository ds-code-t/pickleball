@demo @pickleball-2-1-2
Feature: Pickleball 2.1.2 syntax demo

  # 2.1.2 migration notes:
  #
  # Old inline selector labels:
  #   FEATURE:
  #   SCENARIO:
  #   START:
  #
  # are replaced by:
  #   scenario
  #   feature.scenario
  #   feature.scenario.marker
  #
  # Literal periods and backslashes can be escaped with \. and \\.
  #
  # Service-call invocation now uses the common RunKey convention.
  # Use pkb_callpath rather than the old pkb_callspath property.


  Scenario: Unified RUN selector paths
    * RUN "byScenario" SCENARIO: Selection fixture A

    * RUN "byFeature" SCENARIO: Reusable scenario selection.Selection fixture B

    * RUN "byMarker" SCENARIO: Scenario step markers.Custom marker component.component start

    * RUN "escapedComponent" COMPONENT SCENARIO: Data\.reference\.records.Escaped\.selector fixture.start\.marker
      | pkb_componentpath       |
      | src/test/resources/data |

    * , verify "<byScenario.SCENARIO NAME>" equals "Selection fixture A"
    * , verify "<byFeature.SCENARIO NAME>" equals "Selection fixture B"
    * , verify "<byMarker.SCENARIO NAME>" equals "Custom marker component"
    * , verify "<escapedComponent.SCENARIO NAME>" equals "Escaped.selector fixture"


  Scenario: RUN table options and plural forms
    # RunType can be supplied by the invocation table.
    * RUN: RUN parameter fixture A
      | RunType  | RunKey   |
      | SCENARIO | tableRun |

    # Table RunKey overrides the quoted inline key.
    * RUN "inlineKeyMustLose" SCENARIO: RUN parameter fixture B
      | RunKey       |
      | tableKeyWins |

    # Run Tags and pkb_tags remain valid table selectors.
    * RUN "tagSelected" SCENARIO
      | Run Tags                 |
      | @run-parameter-fixture-a |

    # Normal scenario plural form.
    * RUN SCENARIOS
      | pkb_featurename               | pkb_name                       | pkb_order | pkb_limit |
      | RUN step parameter variations | ^RUN parameter fixture [AB]$ | lexical   | 2         |

    # Component plural form with a path override.
    * RUN COMPONENT SCENARIOS
      | pkb_componentpath           | pkb_featurename               | pkb_name                       | pkb_order |
      | src/test/resources/features | RUN step parameter variations | ^RUN parameter fixture [AB]$ | lexical   |

    * , verify "<tableRun.SCENARIO NAME>" equals "RUN parameter fixture A"
    * , verify "<tableKeyWins.SCENARIO NAME>" equals "RUN parameter fixture B"
    * , verify "<tagSelected.SCENARIO NAME>" equals "RUN parameter fixture A"


  Scenario: Synchronous scenario convenience forms
    * SCENARIO: Selection fixture A
      | RunKey         |
      | directScenario |

    * COMPONENT: Selection fixture B
      | pkb_componentpath           | RunKey          |
      | src/test/resources/features | directComponent |

    * , verify "<directScenario.SCENARIO NAME>" equals "Selection fixture A"
    * , verify "<directComponent.SCENARIO NAME>" equals "Selection fixture B"


  @local-api
  Scenario: Service-call RUN and CALL forms
    # Tag selectors still begin with % or @.
    * RUN "healthByTag" SERVICE CALL: %health-full-url
      | pkb_callpath            |
      | src/test/resources/calls |

    # Named and feature-qualified selectors use the same path grammar.
    # RunKey takes precedence over the quoted key.
    * RUN "quotedKeyMustLose" SERVICE CALL: Reusable service call definitions.HealthCall
      | RunKey         | endpoint              |
      | healthByRunKey | http://127.0.0.1:8765 |

    # Plural service-call form.
    * RUN SERVICE CALLS
      | pkb_featurename                   | pkb_name     | RunKey       | endpoint              | status |
      | Reusable service call definitions | ^HealthCall$ | pluralHealth | http://127.0.0.1:8765 | 200    |
      | Reusable service call definitions | ^StatusCall$ | pluralStatus | http://127.0.0.1:8765 | 418    |

    # Synchronous service-call convenience form.
    * CALL: Reusable service call definitions.HealthCall
      | RunKey         | endpoint              |
      | healthFromCall | http://127.0.0.1:8765 |

    * , verify "<healthByTag.RESPONSE.statusCode>" equals 200
    * , verify "<healthByRunKey.RESPONSE.statusCode>" equals 200
    * , verify "<pluralHealth.RESPONSE.statusCode>" equals 200
    * , verify "<pluralStatus.RESPONSE.statusCode>" equals 418
    * , verify "<healthFromCall.RESPONSE.statusCode>" equals 200

    # Embedded $CALL uses the same selector grammar, for example:
    #
    #   <$CALL:ExplicitNullReturnCall>
    #   <$CALL:Reusable service call definitions.ExplicitNullReturnCall>


  Scenario: Scenario-marker and data-file references
    Given CLEAR SAVED VALUES

    # One-component data: addresses the current scenario marker.
    * ---local.marker
      """json
      {
        "source": "local",
        "value": 7
      }
      """

    When , save "<data:local\.marker>" JSON Data as "localMarker"

    # Two components are scenario.marker.
    And , in the "<data:Customer record.payload>" Data Table, save Data Rows as "customerRows"

    # Three components are feature.scenario.marker.
    And , save "<data:Data reference records.Customer record.message>" Data String as "messageText"

    # Periods inside authored names are escaped.
    And , save "<data:Data\.reference\.records.Customer\.record.payload\.marker>" JSON Data as "escapedMarker"

    # data:/ switches from marker lookup to files beneath pkb_datapath.
    And MAP "fileData" TABLE VALUES TO RUN MAP
      | whole      | <data:/files/customerPayload>                       |
      | firstOrder | <data:/files/customerPayload.customer.orders[0]>   |
      | secondId   | <data:/files/customerPayload.customer.orders[1].id> |

    Then , verify "<localMarker.source>" equals "local"
    And , verify "<customerRows[0].Key1>" equals "qq"
    And , verify "<customerRows[0].Key2>" equals "ww"
    And , verify "<messageText>" contains "marker doc string"
    And , verify "<escapedMarker.source>" equals "escaped-marker"
    And , verify "<escapedMarker.nested.value>" equals 9
    And , verify "<fileData.whole.customer.name>" equals "Ava"
    And , verify "<fileData.firstOrder.id>" equals "A-100"
    And , verify "<fileData.secondId>" equals "A-200"


  Scenario: Data Element query syntax
    Given CLEAR SAVED VALUES

    # Native DataTable projections.
    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save 2nd Data Row as "secondRow"

    And , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save value of Data Entry with key equaling "score" as "firstScore"

    # Java collection candidates use explicit comparison attributes.
    And , save "<data:Data element native fixtures.Structured sources.mapCollection>" JSON Data as "maps"

    And , save "<maps>" Map with key equaling "id" as "mapByKey"

    # Comparison and return projections are independent.
    And , save key of "<maps>" Map with value equaling "pending" as "pendingKeys"

    # Cardinality and positional modifiers apply after filtering.
    And , save "<data:Data element native fixtures.Structured sources.listCollection>" JSON Data as "lists"

    And , for every 2nd "<lists>" List:
  : * , save "<value[0]>" as "lastEverySecondList"

    # "any" permits zero matches.
    And , save "unchanged" as "optionalSentinel"

    And , for any "<maps>" Map with key equaling "missing":
  : * , save "changed" as "optionalSentinel"

    # Multimap is another supported native collection category.
    And , save "<data:Data element native fixtures.Structured sources.multimapSource>" JSON Data as "multimap"

    And , save values of "<multimap>" Multimap as "multimapValues"

    Then , verify "<secondRow.id>" equals "r2"
    And , verify "<firstScore>" equals "10"
    And , verify "<mapByKey.id>" equals "one"
    And , verify "<pendingKeys[0]>" equals "code"
    And , verify "<lastEverySecondList>" equals "delta"
    And , verify "<optionalSentinel>" equals "unchanged"
    And , verify "<multimapValues[2]>" equals "pending"


  Scenario: Structured Data Element formats
    Given CLEAR SAVED VALUES

    # Structured Data auto-detects supported structured formats.
    When , save "<data:Data element native fixtures.Structured sources.jsonDocument>" Structured Data as "structured"

    # Explicit format categories are also available.
    And , save "<data:Data element native fixtures.Structured sources.jsonDocument>" JSON Data as "json"
    And , save "<data:Data element native fixtures.Structured sources.yamlDocument>" YAML Data as "yaml"
    And , save "<data:Data element native fixtures.Structured sources.xmlDocument>" XML Data as "xml"

    # Structured values can also be materialized as strings.
    And , save "<json>" JSON String as "jsonText"

    Then , verify "<structured.name>" equals "Ada"
    And , verify "<json.roles[1]>" equals "author"
    And , verify "<yaml.name>" equals "Grace"
    And , verify "<xml.name>" equals "Lin"
    And , verify "<jsonText>" contains '"name":"Ada"'

    # The same runtime also provides the related plural/string/projection forms,
    # including Data Object(s), Data String(s), JSON/YAML/XML String(s),
    # Data Cell/Header/Value/Entry, Map(s), List(s), Set(s), and Multimap(s).


  Scenario: Query and iterate Data Table rows cells entries and values
    Given CLEAR SAVED VALUES

    * ------
      | key1  | key2  | group | note        |
      | valA1 | valA1 | alpha | alpha-start |
      | valA2 | valA2 | alpha | has-needle  |
      | valB1 | valB1 | beta  | beta-tail   |
      | misc1 | misc1 | beta  | other       |

  # Data Rows
    Then , in the Data Table, for Data Row starting with "valA":
  : And , verify "<key1>" equals "<key2>"
  : And , verify "<key1>" equals "valA1"

    Then , in the Data Table, for Data Row with value containing "needle":
  : And , verify "<key1>" equals "valA2"
  : And , verify "<note>" equals "has-needle"

    When , in the Data Table, save last Data Row starting with "valA" as "lastValARow"
    And , in the Data Table, save Data Row ending with "B1" as "rowEndingB1"

    Then , verify "<lastValARow.key1>" equals "valA2"
    And , verify "<rowEndingB1.group>" equals "beta"

    When , in the Data Table, save Data Rows starting with "valA" as "valARows"

    Then , verify "<valARows[0].key1>" equals "valA1"
    And , verify "<valARows[1].key1>" equals "valA2"

    When , in the Data Table, for every Data Row starting with "valA":
  : * , verify "<key1>" starts with "valA"
  : * , verify "<key1>" equals "<key2>"
  : * , save "<key1>" as "lastValARowSeen"

    Then , verify "<lastValARowSeen>" equals "valA2"

    When , in the Data Table, save Data Rows with value equaling "alpha" as "alphaRows"

    Then , verify "<alphaRows[0].key1>" equals "valA1"
    And , verify "<alphaRows[0].group>" equals "alpha"
    And , verify "<alphaRows[1].key1>" equals "valA2"
    And , verify "<alphaRows[1].group>" equals "alpha"

    Then , in the Data Table, for every Data Row with value equaling "alpha":
  : And , verify "<group>" equals "alpha"
  : And , verify "<key1>" starts with "valA"

  # Data Cells
    When , in the Data Table, save Data Cells starting with "valA" as "valACells"
    And , in the Data Table, save Data Cell ending with "tail" as "tailCell"

    Then , verify "<valACells[0]>" equals "valA1"
    And , verify "<valACells[1]>" equals "valA1"
    And , verify "<valACells[2]>" equals "valA2"
    And , verify "<valACells[3]>" equals "valA2"
    And , verify "<tailCell>" equals "beta-tail"

    When , in the Data Table, for every Data Cell containing "val":
  : * , verify "<value>" contains "val"
  : * , save "<value>" as "lastMatchingCell"

    Then , verify "<lastMatchingCell>" equals "valB1"

  # Data Entries
    When , in the Data Table, save value of Data Entries starting with "key" as "keyValues"
    And , in the Data Table, save value of Data Entries ending with "2" as "key2Values"
    And , in the Data Table, save value of Data Entries equaling "group" as "groupValues"
    And , in the Data Table, save value of Data Entries containing "ote" as "noteValues"

    Then , verify "<keyValues[0]>" equals "valA1"
    And , verify "<keyValues[1]>" equals "valA1"
    And , verify "<key2Values[0]>" equals "valA1"
    And , verify "<key2Values[3]>" equals "misc1"
    And , verify "<groupValues[0]>" equals "alpha"
    And , verify "<groupValues[1]>" equals "alpha"
    And , verify "<groupValues[2]>" equals "beta"
    And , verify "<groupValues[3]>" equals "beta"
    And , verify "<noteValues[1]>" equals "has-needle"

    When , in the Data Table, for every Data Entry equaling "group":
  : * , verify "<Data Header>" equals "group"
  : * , save "<Data Value>" as "lastGroupValue"

    Then , verify "<lastGroupValue>" equals "beta"

  # Data Values
    When , in the Data Table, save Data Values starting with "valA" as "valAValues"
    And , in the Data Table, save Data Values containing "alpha" as "alphaValues"
    And , in the Data Table, save Data Value ending with "tail" as "tailValue"

    Then , verify "<valAValues[0]>" equals "valA1"
    And , verify "<valAValues[3]>" equals "valA2"
    And , verify "<alphaValues[0]>" equals "alpha"
    And , verify "<alphaValues[1]>" equals "alpha-start"
    And , verify "<alphaValues[2]>" equals "alpha"
    And , verify "<tailValue>" equals "beta-tail"

    When , in the Data Table, for every Data Value starting with "valA":
  : * , verify "<value>" starts with "valA"
  : * , save "<value>" as "lastValAValue"

    Then , verify "<lastValAValue>" equals "valA2"