@all @service-calls
Feature: Execute reusable REST and SOAP service calls

  Scenario: Read an item through REST
    When SERVICE CALL: %rest-get-item
      | name         | itemId | include   | traceId          |
      | readItemCall | 73     | inventory | get-feature-test |

    Then , ensure "<readItemCall.response.statusCode>" equals 200
    And , ensure "<readItemCall.response.method>" equals "GET"
    And , ensure "<readItemCall.response.body.id>" equals "73"
    And , ensure "<readItemCall.response.body.include>" equals "inventory"
    And , ensure "<readItemCall.response.body.traceId>" equals "get-feature-test"

  Scenario: Create an item with a JSON body
    When SERVICE CALL: %rest-create-item
      | name           | itemName     | quantity | traceId             |
      | createItemCall | Consumer item | 4        | create-feature-test |

    Then , ensure "<createItemCall.response.statusCode>" equals 201
    And , ensure "<createItemCall.response.method>" equals "POST"
    And , ensure "<createItemCall.response.body.id>" equals "created-100"
    And , ensure "<createItemCall.response.body.name>" equals "Consumer item"
    And , ensure "<createItemCall.response.body.quantity>" equals 4

  Scenario: Patch an item with a JSON body
    When SERVICE CALL: %rest-update-item
      | name           | itemId | itemName              | traceId             |
      | updateItemCall | 88     | Consumer patched item | update-feature-test |

    Then , ensure "<updateItemCall.response.statusCode>" equals 200
    And , ensure "<updateItemCall.response.method>" equals "PATCH"
    And , ensure "<updateItemCall.response.body.id>" equals "88"
    And , ensure "<updateItemCall.response.body.name>" equals "Consumer patched item"

  Scenario: Delete an item
    When SERVICE CALL: %rest-delete-item
      | name           | itemId | traceId             |
      | deleteItemCall | 91     | delete-feature-test |

    Then , ensure "<deleteItemCall.response.statusCode>" equals 204
    And , ensure "<deleteItemCall.response.method>" equals "DELETE"
    And , ensure "<deleteItemCall.response.headers.X-deleted-item>" equals "91"
    And , ensure "<deleteItemCall.response.headers.X-test-trace>" equals "delete-feature-test"

  Scenario: Add two values through SOAP using Run Tags
    When SERVICE CALL
      | Run Tags | name        | left | right | traceId          |
      | %soap-add | soapAddCall | 11   | 6     | soap-feature-test |

    Then , ensure "<soapAddCall.response.statusCode>" equals 200
    And , ensure "<soapAddCall.response.method>" equals "POST"
    And , ensure "<soapAddCall.response.body>" contains "Add"
    And , ensure "<soapAddCall.response.body>" contains "17"

  Scenario: Map literal and resolved values
    When SERVICE CALL: %rest-get-item
      | name          | itemId | include | traceId         |
      | mappedGetCall | 25     | details | map-values-test |

    And MAP VALUES
      | copiedStatus     | <mappedGetCall.response.statusCode> |
      | expectedStatus   | 200                                 |
      | expectedText     | "200"                               |
      | enabled          | true                                |
      | description      | item lookup                         |

    Then , ensure "<copiedStatus>" equals 200
    And , ensure "<expectedStatus>" equals 200
    And , ensure "<expectedText>" equals "200"
    And , ensure "<enabled>" equals "true"
    And , ensure "<description>" equals "item lookup"
