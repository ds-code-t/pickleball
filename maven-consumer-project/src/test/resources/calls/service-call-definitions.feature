Feature: Reusable REST and SOAP service calls

  Scenario Outline: Read an item through REST
    Given ENDPOINT:<endpoint>/api/items/<itemId>?include=<include>
    And METHOD:<method>
    And HEADERS
      | Accept   | X-Test-Trace |
      | <accept> | <traceId>    |
    When EXECUTE SERVICE CALL
    And MAP SERVICE RESPONSE
      | <SERVICE CALL NAME>.response | <responseKey> |

    Examples:
      | Scenario Tags  | ?endpoint             | ?itemId | ?include | ?method | ?accept          | ?traceId          | ?responseKey |
      | %rest-get-item | http://127.0.0.1:8765 | 42      | details  | GET     | application/json | get-default-trace | getResponse  |

  Scenario Outline: Create an item through REST
    Given ENDPOINT:<endpoint>/api/items
    And METHOD:<method>
    And HEADERS
      | Accept   | X-Test-Trace |
      | <accept> | <traceId>    |
    And BODY:json
      """
      {
        "name": "<itemName>",
        "quantity": <quantity>
      }
      """
    When EXECUTE SERVICE CALL
    And MAP SERVICE RESPONSE
      | <SERVICE CALL NAME>.response | <responseKey> |

    Examples:
      | Scenario Tags     | ?endpoint             | ?method | ?accept          | ?traceId             | ?itemName    | ?quantity | ?responseKey  |
      | %rest-create-item | http://127.0.0.1:8765 | POST    | application/json | create-default-trace | Default item | 1         | createResponse |

  Scenario Outline: Update an item through REST
    Given ENDPOINT:<endpoint>/api/items/<itemId>
    And METHOD:<method>
    And HEADERS
      | Accept   | X-Test-Trace |
      | <accept> | <traceId>    |
    And BODY:json
      """
      {
        "name": "<itemName>"
      }
      """
    When EXECUTE SERVICE CALL
    And MAP SERVICE RESPONSE
      | <SERVICE CALL NAME>.response | <responseKey> |

    Examples:
      | Scenario Tags     | ?endpoint             | ?itemId | ?method | ?accept          | ?traceId             | ?itemName            | ?responseKey  |
      | %rest-update-item | http://127.0.0.1:8765 | 42      | PATCH   | application/json | update-default-trace | Default updated item | updateResponse |

  Scenario Outline: Delete an item through REST
    Given ENDPOINT:<endpoint>/api/items/<itemId>
    And METHOD:<method>
    And HEADERS
      | Accept   | X-Test-Trace |
      | <accept> | <traceId>    |
    When EXECUTE SERVICE CALL
    And MAP SERVICE RESPONSE
      | <SERVICE CALL NAME>.response | <responseKey> |

    Examples:
      | Scenario Tags     | ?endpoint             | ?itemId | ?method | ?accept          | ?traceId             | ?responseKey  |
      | %rest-delete-item | http://127.0.0.1:8765 | 42      | DELETE  | application/json | delete-default-trace | deleteResponse |

  Scenario Outline: Add values through SOAP
    Given ENDPOINT:<endpoint>/soap/calculator
    And METHOD:<method>
    And HEADERS
      | Accept   | Content-Type | SOAPAction   | X-Test-Trace |
      | <accept> | <contentType> | <soapAction> | <traceId>    |
    And BODY:xml
      """
      ~[~soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                          xmlns:calc="urn:pickleball:calculator"~]~
        ~[~soapenv:Header/~]~
        ~[~soapenv:Body~]~
          ~[~calc:<operation>~]~
            ~[~calc:left~]~<left>~[~/calc:left~]~
            ~[~calc:right~]~<right>~[~/calc:right~]~
          ~[~/calc:<operation>~]~
        ~[~/soapenv:Body~]~
      ~[~/soapenv:Envelope~]~
      """
    When EXECUTE SERVICE CALL
    And MAP SERVICE RESPONSE
      | <SERVICE CALL NAME>.response | <responseKey> |

    Examples:
      | Scenario Tags | ?endpoint             | ?method | ?accept | ?contentType | ?soapAction                       | ?traceId           | ?operation | ?left | ?right | ?responseKey |
      | %soap-add     | http://127.0.0.1:8765 | POST    | text/xml | text/xml     | urn:pickleball:calculator#Add     | soap-default-trace | Add        | 5     | 7      | soapResponse |
