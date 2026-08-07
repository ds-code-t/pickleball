@all @regression @data-elements @data-element-native @data-element-native-formats @newphases
Feature: Native Data Element structured format conversion

  Scenario: Convert typed marker DocStrings through Structured JSON YAML and XML Data
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Structured sources.jsonDocument>" Structured Data as "structuredJson"
    And , save "<data:Data element native fixtures.Structured sources.jsonDocument>" Data Object as "dataObject"
    And , save "<data:Data element native fixtures.Structured sources.jsonDocument>" JSON Data as "jsonData"
    Then , verify "<structuredJson.name>" equals "Ada"
    And , verify "<structuredJson.active>" equals "true"
    And , verify "<structuredJson.score>" equals 42
    And , verify "<structuredJson.address.city>" equals "Phoenix"
    And , verify "<structuredJson.roles[1]>" equals "author"
    And , verify "<dataObject.name>" equals "Ada"
    And , verify "<jsonData.name>" equals "Ada"

    When , save "<data:Data element native fixtures.Structured sources.yamlDocument>" Structured Data as "structuredYaml"
    And , save "<data:Data element native fixtures.Structured sources.yamlDocument>" YAML Data as "yamlData"
    Then , verify "<structuredYaml.name>" equals "Grace"
    And , verify "<structuredYaml.active>" equals "true"
    And , verify "<structuredYaml.address.city>" equals "Tempe"
    And , verify "<yamlData.roles[0]>" equals "reviewer"
    And , verify "<yamlData.roles[1]>" equals "editor"

    When , save "<data:Data element native fixtures.Structured sources.xmlDocument>" Structured Data as "structuredXml"
    And , save "<data:Data element native fixtures.Structured sources.xmlDocument>" XML Data as "xmlData"
    Then , verify "<structuredXml.id>" equals "7"
    And , verify "<structuredXml.name>" equals "Lin"
    And , verify "<xmlData.active>" equals "true"
    And , verify "<xmlData.score>" equals "31"

  Scenario: Preserve a native DocString then convert it to Jackson data
    Given CLEAR SAVED VALUES

    # Doc String is the established compatibility alias for the native Cucumber object.
    When , save "<data:Data element native fixtures.Structured sources.jsonDocument>" Doc String as "nativeDocString"
    And , save "<nativeDocString>" Structured Data as "docStringData"
    Then , verify "<docStringData.name>" equals "Ada"
    And , verify "<docStringData.address.city>" equals "Phoenix"
    And , verify "<docStringData.roles[0]>" equals "admin"

    # The legacy Data alias converts a native marker DocString to a JsonNode.
    When , save "<data:Data element native fixtures.Structured sources.jsonDocument>" Data as "legacyData"
    Then , verify "<legacyData.name>" equals "Ada"
    And , verify "<legacyData.score>" equals 42

  Scenario: Serialize and round trip JSON YAML XML and generic Data Strings
    Given CLEAR SAVED VALUES

    When , save "<data:Data element native fixtures.Structured sources.jsonDocument>" JSON Data as "sourceJson"

    And , save "<sourceJson>" JSON String as "jsonString"
    Then , verify "<jsonString>" contains '"name":"Ada"'
    And , verify "<jsonString>" contains '"active":true'

    When , save "<jsonString>" JSON Data as "jsonRoundTrip"
    Then , verify "<jsonRoundTrip.name>" equals "Ada"
    And , verify "<jsonRoundTrip.roles[1]>" equals "author"

    When , save "<sourceJson>" YAML String as "yamlString"
    Then , verify "<yamlString>" contains "name"
    And , verify "<yamlString>" contains "Ada"

    When , save "<yamlString>" YAML Data as "yamlRoundTrip"
    Then , verify "<yamlRoundTrip.name>" equals "Ada"
    And , verify "<yamlRoundTrip.address.city>" equals "Phoenix"

    When , save "<sourceJson>" XML String as "xmlString"
    Then , verify "~[~xmlString~]~" contains "<Data>"
    And , verify "~[~xmlString~]~" contains "<name>Ada</name>"

    When , save "<xmlString>" XML Data as "xmlRoundTrip"
    Then , verify "<xmlRoundTrip.name>" equals "Ada"
    And , verify "<xmlRoundTrip.address.city>" equals "Phoenix"

    When , save "<data:Data element native fixtures.Structured sources.jsonDocument>" Data String as "docStringText"
    Then , verify "<docStringText>" contains '"name": "Ada"'
    And , verify "<docStringText>" contains '"city": "Phoenix"'

  Scenario: Convert plural structured objects and plural strings as one terminal aggregate
    Given CLEAR SAVED VALUES

    When , save "<data:Data element native fixtures.Structured sources.mapCollection>" JSON Data as "objectArray"
    And , save "<objectArray>" Data Objects as "dataObjects"
    Then , verify "<dataObjects[0].id>" equals "one"
    And , verify "<dataObjects[1].code>" equals "two"
    And , verify "<dataObjects[2].status>" equals "complete"

    When , save "<objectArray>" JSON Strings as "jsonStrings"
    Then , verify "<jsonStrings[0]>" contains '"id":"one"'
    And , verify "<jsonStrings[1]>" contains '"code":"two"'
    And , verify "<jsonStrings[2]>" contains '"status":"complete"'

    When , save "<objectArray>" Data Strings as "dataStrings"
    Then , verify "<dataStrings[0]>" contains '"id":"one"'
    And , verify "<dataStrings[1]>" contains '"status":"pending"'

  Scenario: Convert a native DataTable to a Data String and back through JSON
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Cucumber sources.roundtrip>" Data String as "tableJsonString"
    Then , verify "<tableJsonString>" contains '"id":"a1"'
    And , verify "<tableJsonString>" contains '"owner":"Cara"'

    When , save "<tableJsonString>" JSON Data as "tableJsonFromString"
    Then , verify "<tableJsonFromString[0].id>" equals "a1"
    And , verify "<tableJsonFromString[2].score>" equals "33"

    When , in the "<tableJsonFromString>" Data Table, save Data Rows as "tableRowsFromString"
    Then , verify "<tableRowsFromString[0].owner>" equals "Ada"
    And , verify "<tableRowsFromString[2].active>" equals "true"

  Scenario: Unquoted Data prefers the first unnamed DocString below
    Given CLEAR SAVED VALUES

    * ------
      """json
      {
        "source": "above"
      }
      """

    When , save Data as "unnamedData"
    * ------
      """json
      {
        "source": "below",
        "nested": {
          "value": 7
        }
      }
      """

    Then , verify "<unnamedData.source>" equals "below"
    And , verify "<unnamedData.nested.value>" equals 7

  Scenario: Unquoted Data falls back to an unnamed DataTable above
    Given CLEAR SAVED VALUES

    * ------
      | captureKey | value          |
      | tableData  | selected above |

    When , save Data as "unnamedTableData"
    Then , verify "<unnamedTableData.captureKey>" equals "tableData"
    And , verify "<unnamedTableData.value>" equals "selected above"

  # Phase 5 copy-on-write is exercised through its Java API checks today.
  # Dynamic save always writes the primary RUN map, so there is currently no
  # native Gherkin mutation verb that writes a replacement into DataContextNodeMap.
