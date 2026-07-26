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
