Feature: Use supported mapping steps and comma save actions

  @all @regression @data @mapping @mapping-steps @table-values @failed
  Scenario: Map prefixed and unprefixed table values
    Given CLEAR SAVED VALUES
    And MAP "customer" TABLE VALUES TO RUN MAP
      | name         | Ava     |
      | address.city | Phoenix |
      | tier         | Premium |
    And MAP TABLE VALUES TO RUN MAP
      | status  | ready |
      | retries | 2     |
    And MAP "scenarioSettings" TABLE VALUES TO SCENARIO MAP
      | enabled | true |
    Then , ensure "<customer.name>" equals "Ava"
    And , ensure "<customer.address.city>" equals "Phoenix"
    And , ensure "<customer.tier>" equals "Premium"
    And , ensure "<status>" equals "ready"
    And , ensure "<retries>" equals "2"
    And , ensure "<scenarioSettings.enabled>" equals "true"
    And CLEAR SAVED VALUES

  @all @regression @data @mapping @mapping-steps @docstring-values
  Scenario: Map parsed JSON YAML XML and raw text DocStrings
    Given CLEAR SAVED VALUES
    And MAP "jsonCustomer" OBJECT VALUE TO RUN MAP
      """json
      {
        "name": "Ava",
        "active": true,
        "orders": [
          { "id": "A-100" },
          { "id": "A-200" }
        ]
      }
      """
    And MAP "yamlCustomer" OBJECT VALUE TO RUN MAP
      """yaml
      name: Ben
      address:
        city: Tempe
      """
    And MAP "xmlCustomer" OBJECT VALUE TO RUN MAP
      """xml
      <customer>
        <name>Cara</name>
        <city>Mesa</city>
      </customer>
      """
    And MAP "rawPayload" TEXT VALUE TO RUN MAP
      """text
      raw mapping text
      """
    Then , ensure "<jsonCustomer.name>" equals "Ava"
    And , ensure "<jsonCustomer.active>" equals "true"
    And , ensure "<jsonCustomer.orders #1.id>" equals "A-100"
    And , ensure "<jsonCustomer.orders #2.id>" equals "A-200"
    And , ensure "<yamlCustomer.name>" equals "Ben"
    And , ensure "<yamlCustomer.address.city>" equals "Tempe"
    And , ensure "<xmlCustomer.name>" equals "Cara"
    And , ensure "<xmlCustomer.city>" equals "Mesa"
    And , ensure "<rawPayload>" equals "raw mapping text"
    And CLEAR SAVED VALUES

  @all @regression @data @mapping @mapping-directives
  Scenario: Resolve mapping directives masks pipelines and conversion keys
    Given CLEAR SAVED VALUES
    And MAP TABLE VALUES TO RUN MAP
      | jsonPayload          | {"name":"Ava","items":[{"name":"first"},{"name":"second"}]} |
      | idx                  | 1                                                                    |
      | lateValue                         | resolved                                      |
      | deferredSource                    | <value:~^^{"later":"<lateValue>"}^^~>       |
      | deferred~JSON;~unresolved;        | <deferredSource>                              |
      | tableJson~JSON;                   | {"nested":{"count":3}}                      |
    And MAP "directiveObject" OBJECT VALUE TO RUN MAP
      """json
      {
        "payload~JSON;": "{\"active\":true}"
      }
      """
    And MAP "wrappedObject" OBJECT VALUE TO RUN MAP
      """json
      {
        "~JSON;": "{\"score\":4}"
      }
      """
    And MAP "sourceObject" OBJECT VALUE TO RUN MAP
      """json
      {
        "name": "Ava",
        "active": true
      }
      """
    And MAP "rawJson" TEXT VALUE TO RUN MAP
      """text
      {"customer":"<sourceObject~unquoted;>"}
      """
    Then , ensure "<jsonPayload~JSON;::name>" equals "Ava"
    And , ensure "<jsonPayload~JSON;::items[<idx>].name>" equals "second"
    And , ensure "<tableJson.nested.count>" equals "3"
    And , ensure "<directiveObject.payload.active>" equals "true"
    And , ensure "<wrappedObject.score>" equals "4"
    And , ensure "<rawJson~JSON;::customer.name>" equals "Ava"
    And , ensure "<deferred.later~unresolved;>" equals "~^^<lateValue>^^~"
    And , ensure "<value:hello>" equals "hello"
    And , ensure "<value:{\"text\":\"a::b\"}~JSON;::text>" equals "a::b"
    And , ensure "<value:~^^hello <lateValue> :: ~JSON;^^~>" equals "~^^hello <lateValue> :: ~JSON;^^~"
    And , ensure "<^~EMPTY~^>" equals ""
    And CLEAR SAVED VALUES

  @all @regression @data @mapping @config-reference
  Scenario: Resolve recommended and legacy configuration references from the same mapping
    Then , ensure "<config:TEST_DATA.siteName>" equals "Pickleball Test Lab"
    And , ensure "<configs.TEST_DATA.siteName>" equals "Pickleball Test Lab"
    And , ensure "<config:TEST_DATA.expected.catalogCount>" equals "3"

  @all @regression @data @mapping @dynamic-steps @save-values
  Scenario: Save literal numeric mapped and replacement values
    Given CLEAR SAVED VALUES
    And MAP "sourceCustomer" TABLE VALUES TO RUN MAP
      | name | Ava     |
      | city | Phoenix |
    When , save "literal value" as "savedLiteral"
    And , save 3 as "savedNumber"
    And , save "<sourceCustomer.name>" as "savedName"
    And , save "<sourceCustomer.city>" as "savedCity"
    And , save "draft" as "savedStatus"
    And , save "ready" as "savedStatus"
    Then , ensure "<savedLiteral>" equals "literal value"
    And , ensure "<savedNumber>" equals "3"
    And , ensure "<savedName>" equals "Ava"
    And , ensure "<savedCity>" equals "Phoenix"
    And , ensure "<savedStatus>" equals "ready"
    And CLEAR SAVED VALUES

  @all @regression @data @mapping @mapping-steps @clear-values @failed
  Scenario: Clear selected values without clearing retained values
    Given CLEAR SAVED VALUES
    And MAP TABLE VALUES TO RUN MAP
      | retainedValue  | retained  |
      | temporaryValue | temporary |
    When CLEAR SAVED VALUES:temporaryValue
    Then , ensure "<retainedValue>" equals "retained"
    And , save "replacement" as "temporaryValue"
    And , ensure "<temporaryValue>" equals "replacement"
    And CLEAR SAVED VALUES

  @all @regression @data @mapping @mapping-merge
  Scenario: Merge mapping destination keys using normal NodeMap get selection
    Given CLEAR SAVED VALUES
    And MAP "mergeCustomer" OBJECT VALUE TO RUN MAP
      """json
      {
        "name": "Ada",
        "settings": {
          "theme": "dark",
          "retries": 2
        },
        "tags": ["alpha"]
      }
      """
    And MAP "mergeCustomer~merge;" OBJECT VALUE TO RUN MAP
      """json
      {
        "active": true,
        "settings": {
          "retries": 4,
          "enabled": true
        },
        "tags": ["beta"],
        "nullable": null
      }
      """
    And MAP "mergeItems" OBJECT VALUE TO RUN MAP
      """json
      [1, 2]
      """
    And MAP "mergeItems~merge;" OBJECT VALUE TO RUN MAP
      """json
      [3, 4]
      """
    And MAP TABLE VALUES TO RUN MAP
      | tableMerge~JSON; | {"nested":{"first":1},"items":[1]} |
    And MAP TABLE VALUES TO RUN MAP
      | tableMerge~JSON;~merge; | {"nested":{"second":2},"items":[2]} |
    And MAP TABLE VALUES TO RUN MAP
      | mergeCustomer~merge; | <^~NULL~^> |
    And MAP "mergeCreated~merge;" OBJECT VALUE TO RUN MAP
      """json
      {
        "created": true
      }
      """
    And MAP "mergeHistory" OBJECT VALUE TO RUN MAP
      """json
      {
        "version": "first"
      }
      """
    And MAP "mergeHistory" OBJECT VALUE TO RUN MAP
      """json
      {
        "version": "second"
      }
      """
    And MAP "mergeHistory[][0]~merge;" OBJECT VALUE TO RUN MAP
      """json
      {
        "changed": true
      }
      """
    Then , ensure "<mergeCustomer.name>" equals "Ada"
    And , ensure "<mergeCustomer.active>" equals "true"
    And , ensure "<mergeCustomer.settings.theme>" equals "dark"
    And , ensure "<mergeCustomer.settings.retries>" equals "4"
    And , ensure "<mergeCustomer.settings.enabled>" equals "true"
    And , ensure "<mergeCustomer.tags[0]>" equals "alpha"
    And , ensure "<mergeCustomer.tags[1]>" equals "beta"
    And , ensure "<mergeItems[0]>" equals "1"
    And , ensure "<mergeItems[1]>" equals "2"
    And , ensure "<mergeItems[2]>" equals "3"
    And , ensure "<mergeItems[3]>" equals "4"
    And , ensure "<tableMerge.nested.first>" equals "1"
    And , ensure "<tableMerge.nested.second>" equals "2"
    And , ensure "<tableMerge.items[0]>" equals "1"
    And , ensure "<tableMerge.items[1]>" equals "2"
    And , ensure "<mergeCreated.created>" equals "true"
    And , ensure "<mergeHistory.version>" equals "second"
    And , ensure "<mergeHistory[][0].version>" equals "first"
    And , ensure "<mergeHistory[][0].changed>" equals "true"
    And CLEAR SAVED VALUES
