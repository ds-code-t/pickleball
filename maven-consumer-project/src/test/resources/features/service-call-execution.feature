@all @service-calls
Feature: Execute reusable REST and SOAP service calls

  Scenario: Read an item through REST
    When SERVICE CALL: %rest-get-item
      | name        | itemId | include   | traceId          | responseKey |
      | readItemCall | 73     | inventory | get-feature-test | getResult   |
    Then , ensure <getResult.statusCode> equals 200
    And , ensure <getResult.method> equals "GET"
    And , ensure <getResult.body.id> equals "73"
    And , ensure <getResult.body.include> equals "inventory"
    And , ensure <getResult.body.traceId> equals "get-feature-test"

  Scenario: Create an item with a JSON body
    When SERVICE CALL: %rest-create-item
      | name           | itemName      | quantity | traceId             | responseKey  |
      | createItemCall | Consumer item | 4        | create-feature-test | createResult |
    Then , ensure <createResult.statusCode> equals 201
    And , ensure <createResult.method> equals "POST"
    And , ensure <createResult.body.id> equals "created-100"
    And , ensure <createResult.body.name> equals "Consumer item"
    And , ensure <createResult.body.quantity> equals 4

  Scenario: Patch an item with a JSON body
    When SERVICE CALL: %rest-update-item
      | name           | itemId | itemName              | traceId             | responseKey  |
      | updateItemCall | 88     | Consumer patched item | update-feature-test | updateResult |
    Then , ensure <updateResult.statusCode> equals 200
    And , ensure <updateResult.method> equals "PATCH"
    And , ensure <updateResult.body.id> equals "88"
    And , ensure <updateResult.body.name> equals "Consumer patched item"

  Scenario: Delete an item
    When SERVICE CALL: %rest-delete-item
      | name           | itemId | traceId             | responseKey  |
      | deleteItemCall | 91     | delete-feature-test | deleteResult |
    Then , ensure <deleteResult.statusCode> equals 204
    And , ensure <deleteResult.method> equals "DELETE"
    And , ensure <deleteResult.headers.X-Deleted-Item> equals "91"

  Scenario: Add two values through SOAP using Run Tags
    When SERVICE CALL
      | Run Tags | name        | left | right | traceId           | responseKey |
      | %soap-add | soapAddCall | 11   | 6     | soap-feature-test | soapResult  |
    Then , ensure <soapResult.statusCode> equals 200
    And , ensure <soapResult.method> equals "POST"
    And , ensure <soapResult.headers.X-SOAP-Operation> equals "Add"
    And , ensure <soapResult.body> contains "17"
