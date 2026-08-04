@service-call @local-api
Feature: Service call orchestration with generic request mappings

  Scenario: Select by inline percent tag and save under the quoted object name
    When RUN "inlineRead" SERVICE CALL: %inspect-get
      | endpoint              | client      | traceId     | include   | mode |
      | http://127.0.0.1:8765 | caller-test | trace-get-1 | inventory | full |
    Then , verify "<inlineRead.REQUEST.endpoint>" equals "http://127.0.0.1:8765/api/service-calls/inspect"
    And , verify "<inlineRead.REQUEST.method>" equals "GET"
    And , verify "<inlineRead.REQUEST.headers.X-Test-Client>" equals "caller-test"
    And , verify "<inlineRead.REQUEST.queryParams.include>" equals "inventory"
    And , verify "<inlineRead.CONFIGURATION.urlEncodingEnabled>" equals "true"
    And , verify "<inlineRead.RESPONSE.method>" equals "GET"
    And , verify "<inlineRead.RESPONSE.statusCode>" equals "200"
    And , verify "<inlineRead.RESPONSE.body.client>" equals "caller-test"
    And , verify "<inlineRead.RESPONSE.body.traceId>" equals "trace-get-1"
    And , verify "<inlineRead.RESPONSE.body.include>" equals "inventory"
    And , verify "<inlineRead.RESPONSE.body.mode>" equals "full"

  Scenario: Select with Run Tags and save under an exact RunKey header
    When RUN SERVICE CALLS
      | Run Tags     | RunKey   | endpoint              | client     | traceId     | cookieValue | mode   | status | name   | quantity |
      | %inspect-post | tablePost | http://127.0.0.1:8765 | table-test | trace-post-1 | cookie-42   | create | 201    | Widget | 3        |
    Then , verify "<tablePost.REQUEST.endpoint>" equals "http://127.0.0.1:8765/api/service-calls/inspect"
    And , verify "<tablePost.REQUEST.method>" equals "POST"
    And , verify "<tablePost.REQUEST.headers.X-Test-Trace>" equals "trace-post-1"
    And , verify "<tablePost.REQUEST.cookies.serviceCookie>" equals "cookie-42"
    And , verify "<tablePost.REQUEST.queryParams.status>" equals "201"
    And , verify "<tablePost.REQUEST.body.name>" equals "Widget"
    And , verify "<tablePost.REQUEST.body.quantity>" equals "3"
    And , verify "<tablePost.REQUEST.body.active>" equals "true"
    And , verify "<tablePost.RESPONSE.method>" equals "POST"
    And , verify "<tablePost.RESPONSE.statusCode>" equals "201"
    And , verify "<tablePost.RESPONSE.body.client>" equals "table-test"
    And , verify "<tablePost.RESPONSE.body.traceId>" equals "trace-post-1"
    And , verify "<tablePost.RESPONSE.body.cookie>" equals "serviceCookie=cookie-42"
    And , verify "<tablePost.RESPONSE.body.body.name>" equals "Widget"
    And , verify "<tablePost.RESPONSE.body.body.quantity>" equals "3"
    And , verify "<tablePost.RESPONSE.body.body.active>" equals "true"

  Scenario: Resolve nested scenario-map values inside a JSON service request body
    Given MAP "jsonTemplate" OBJECT VALUE TO SCENARIO MAP
      """json
      {
        "client": "json-template-client",
        "item": {
          "name": "Mapped Widget",
          "quantity": 4,
          "active": true
        },
        "metadata": {
          "requestId": "json-template-42",
          "source": "scenario-map"
        }
      }
      """
    When RUN "mappedJson" SERVICE CALL: %mapped-json-body
      | endpoint              |
      | http://127.0.0.1:8765 |
    Then , verify "<mappedJson.REQUEST.endpoint>" equals "http://127.0.0.1:8765/api/service-calls/inspect"
    And , verify "<mappedJson.REQUEST.headers.X-Test-Client>" equals "json-template-client"
    And , verify "<mappedJson.REQUEST.headers.X-Test-Trace>" equals "json-template-42"
    And , verify "<mappedJson.REQUEST.queryParams.mode>" equals "mapped-json"
    And , verify "<mappedJson.REQUEST.body.name>" equals "Mapped Widget"
    And , verify "<mappedJson.REQUEST.body.quantity>" equals "4"
    And , verify "<mappedJson.REQUEST.body.active>" equals "true"
    And , verify "<mappedJson.REQUEST.body.description>" equals "Mapped Widget from scenario-map"
    And , verify "<mappedJson.REQUEST.body.metadata.requestId>" equals "json-template-42"
    And , verify "<mappedJson.REQUEST.body.metadata.source>" equals "scenario-map"
    And , verify "<mappedJson.RESPONSE.statusCode>" equals "202"
    And , verify "<mappedJson.RESPONSE.body.client>" equals "json-template-client"
    And , verify "<mappedJson.RESPONSE.body.traceId>" equals "json-template-42"
    And , verify "<mappedJson.RESPONSE.body.body.name>" equals "Mapped Widget"
    And , verify "<mappedJson.RESPONSE.body.body.quantity>" equals "4"
    And , verify "<mappedJson.RESPONSE.body.body.active>" equals "true"
    And , verify "<mappedJson.RESPONSE.body.body.description>" equals "Mapped Widget from scenario-map"
    And , verify "<mappedJson.RESPONSE.body.body.metadata.requestId>" equals "json-template-42"
    And , verify "<mappedJson.RESPONSE.body.body.metadata.source>" equals "scenario-map"

  Scenario: Preserve JSON scalar and container types with quoted ~unquote references
    Given MAP "unquoteTemplate" OBJECT VALUE TO SCENARIO MAP
      """json
      {
        "client": "unquote-client",
        "requestId": "unquote-73",
        "name": "Unquoted Widget",
        "quantity": 12,
        "active": false,
        "unitPrice": 19.95,
        "metadata": {
          "source": "scenario-map",
          "priority": 3
        },
        "items": [
          {
            "sku": "A-1",
            "count": 2
          },
          {
            "sku": "B-2",
            "count": 5
          }
        ]
      }
      """
    When RUN "unquotedJson" SERVICE CALL: %unquoted-json-body
      | endpoint              |
      | http://127.0.0.1:8765 |

    Then , verify "<unquotedJson.REQUEST.endpoint>" equals "http://127.0.0.1:8765/api/service-calls/inspect"
    And , verify "<unquotedJson.REQUEST.headers.X-Test-Client>" equals "unquote-client"
    And , verify "<unquotedJson.REQUEST.headers.X-Test-Trace>" equals "unquote-73"
    And , verify "<unquotedJson.REQUEST.queryParams.mode>" equals "unquote-json"
    And , verify "<unquotedJson.REQUEST.body.name>" equals "Unquoted Widget"
    And , verify "<unquotedJson.REQUEST.body.quantity>" equals "12"
    And , verify "<unquotedJson.REQUEST.body.active>" equals "false"
    And , verify "<unquotedJson.REQUEST.body.unitPrice>" equals "19.95"
    And , verify "<unquotedJson.REQUEST.body.metadata.source>" equals "scenario-map"
    And , verify "<unquotedJson.REQUEST.body.metadata.priority>" equals "3"
    And , verify "<unquotedJson.REQUEST.body.items[0].sku>" equals "A-1"
    And , verify "<unquotedJson.REQUEST.body.items[0].count>" equals "2"
    And , verify "<unquotedJson.REQUEST.body.items[1].sku>" equals "B-2"
    And , verify "<unquotedJson.REQUEST.body.items[1].count>" equals "5"
    And , verify "<unquotedJson.REQUEST.body.description>" equals "Unquoted Widget has 2 first-item units"
    And , verify "<unquotedJson.RESPONSE.statusCode>" equals "200"
    And , verify "<unquotedJson.RESPONSE.body.body.name>" equals "Unquoted Widget"
    And , verify "<unquotedJson.RESPONSE.body.body.quantity>" equals "12"
    And , verify "<unquotedJson.RESPONSE.body.body.active>" equals "false"
    And , verify "<unquotedJson.RESPONSE.body.body.unitPrice>" equals "19.95"
    And , verify "<unquotedJson.RESPONSE.body.body.metadata.source>" equals "scenario-map"
    And , verify "<unquotedJson.RESPONSE.body.body.metadata.priority>" equals "3"
    And , verify "<unquotedJson.RESPONSE.body.body.items[0].sku>" equals "A-1"
    And , verify "<unquotedJson.RESPONSE.body.body.items[0].count>" equals "2"
    And , verify "<unquotedJson.RESPONSE.body.body.items[1].sku>" equals "B-2"
    And , verify "<unquotedJson.RESPONSE.body.body.items[1].count>" equals "5"
    And , verify "<unquotedJson.RESPONSE.body.body.description>" equals "Unquoted Widget has 2 first-item units"

  Scenario: Resolve values from a previous service response inside a JSON request body
    Given MAP "jsonChain" OBJECT VALUE TO SCENARIO MAP
      """json
      {
        "client": "json-chain-client",
        "requestId": "json-chain-9"
      }
      """
    And RUN "seedJson" SERVICE CALL: %inspect-post
      | endpoint              | client      | traceId        | cookieValue | mode | status | name        | quantity |
      | http://127.0.0.1:8765 | seed-client | seed-json-call | seed-cookie | seed | 201    | Seed Widget | 8        |

    When RUN "chainedJson" SERVICE CALL: %previous-response-json-body
      | endpoint              |
      | http://127.0.0.1:8765 |
    Then , verify "<seedJson.RESPONSE.statusCode>" equals "201"
    And , verify "<seedJson.RESPONSE.body.body.name>" equals "Seed Widget"
    And , verify "<seedJson.RESPONSE.body.body.quantity>" equals "8"
    And , verify "<chainedJson.REQUEST.headers.X-Test-Client>" equals "json-chain-client"
    And , verify "<chainedJson.REQUEST.headers.X-Test-Trace>" equals "json-chain-9"
    And , verify "<chainedJson.REQUEST.queryParams.mode>" equals "previous-response-json"
    And , verify "<chainedJson.REQUEST.body.name>" equals "Seed Widget"
    And , verify "<chainedJson.REQUEST.body.quantity>" equals "8"
    And , verify "<chainedJson.REQUEST.body.active>" equals "true"
    And , verify "<chainedJson.REQUEST.body.description>" equals "copied Seed Widget at quantity 8"
    And , verify "<chainedJson.REQUEST.body.original.name>" equals "Seed Widget"
    And , verify "<chainedJson.REQUEST.body.original.quantity>" equals "8"
    And , verify "<chainedJson.REQUEST.body.original.active>" equals "true"
    And , verify "<chainedJson.REQUEST.body.metadata.requestId>" equals "json-chain-9"
    And , verify "<chainedJson.REQUEST.body.metadata.sourceStatus>" equals "201"
    And , verify "<chainedJson.RESPONSE.statusCode>" equals "203"
    And , verify "<chainedJson.RESPONSE.body.body.name>" equals "Seed Widget"
    And , verify "<chainedJson.RESPONSE.body.body.quantity>" equals "8"
    And , verify "<chainedJson.RESPONSE.body.body.description>" equals "copied Seed Widget at quantity 8"
    And , verify "<chainedJson.RESPONSE.body.body.original.name>" equals "Seed Widget"
    And , verify "<chainedJson.RESPONSE.body.body.metadata.sourceStatus>" equals "201"

  Scenario: Resolve mapped and previous-response values in XML with XML-safe bookends
    Given MAP "soapTemplate" OBJECT VALUE TO SCENARIO MAP
      """json
      {
        "left": 11,
        "traceId": "soap-template-17"
      }
      """
    And RUN "soapSeed" SERVICE CALL: %inspect-post
      | endpoint              | client      | traceId        | cookieValue | mode      | status | name          | quantity |
      | http://127.0.0.1:8765 | soap-client | soap-seed-call | soap-cookie | soap-seed | 200    | Right Operand | 6        |

    When RUN "mappedSoap" SERVICE CALL: %mapped-soap-body
      | endpoint              |
      | http://127.0.0.1:8765 |
    Then , verify "<soapSeed.RESPONSE.statusCode>" equals "200"
    And , verify "<soapSeed.RESPONSE.body.body.quantity>" equals "6"
    And , verify "<mappedSoap.REQUEST.endpoint>" equals "http://127.0.0.1:8765/soap/calculator"
    And , verify "<mappedSoap.REQUEST.method>" equals "POST"
    And , verify "<mappedSoap.REQUEST.contentType>" equals "text/xml"
    And , verify "<mappedSoap.REQUEST.headers.SOAPAction>" equals "urn:pickleball:calculator#Add"
    And , verify "<mappedSoap.REQUEST.headers.X-Test-Trace>" equals "soap-template-17"
    And , verify "<mappedSoap.REQUEST.body>" contains "<soapenv:Header/>"
    And , verify "<mappedSoap.REQUEST.body>" contains "<calc:left>11</calc:left>"
    And , verify "<mappedSoap.REQUEST.body>" contains "<calc:right>6</calc:right>"
    And , verify "<mappedSoap.RESPONSE.method>" equals "POST"
    And , verify "<mappedSoap.RESPONSE.statusCode>" equals "200"
    And , verify "<mappedSoap.RESPONSE.body>" contains "17"

  Scenario: RunKey takes precedence over the quoted inline object name
    When RUN "inlineMustLose" SERVICE CALL: %status-call
      | RunKey   | endpoint              | status |
      | tableWins | http://127.0.0.1:8765 | 422    |

    Then , verify "<tableWins.REQUEST.queryParams.status>" equals "422"
    And , verify "<tableWins.RESPONSE.statusCode>" equals "422"
    And , verify "<tableWins.RESPONSE.body.status>" equals "422"
    And , verify "<tableWins.RESPONSE.body.method>" equals "GET"

  Scenario: A new RUN call without a key does not save by scenario name
    When RUN SERVICE CALL
      | Run Tags         | endpoint              |
      | %health-full-url | http://127.0.0.1:8765 |
    Then , verify "completed without an implicit run key" equals "completed without an implicit run key"

  Scenario: Treat an HTTP 500 response as a normal service response
    When RUN "serverFailure" SERVICE CALL
      | Run Tags    | endpoint              | status |
      | %status-call | http://127.0.0.1:8765 | 500    |

    Then , verify "<serverFailure.RESPONSE.method>" equals "GET"
    And , verify "<serverFailure.RESPONSE.statusCode>" equals "500"
    And , verify "<serverFailure.RESPONSE.body.status>" equals "500"

  Scenario: Reusing a RunKey follows ordinary NodeMap replacement behavior
    When RUN SERVICE CALL
      | Run Tags    | RunKey      | endpoint              | status |
      | %status-call | latestStatus | http://127.0.0.1:8765 | 404    |
    And RUN SERVICE CALL
      | Run Tags    | RunKey      | endpoint              | status |
      | %status-call | latestStatus | http://127.0.0.1:8765 | 503    |
    Then , verify "<latestStatus.RESPONSE.statusCode>" equals "503"
    And , verify "<latestStatus.RESPONSE.body.status>" equals "503"

  Scenario: Preserve a no-content response and its response headers
    When RUN "deletedItem" SERVICE CALL: %delete-call
      | endpoint              | itemId |
      | http://127.0.0.1:8765 | 55     |
    Then , verify "<deletedItem.REQUEST.endpoint>" equals "http://127.0.0.1:8765/api/service-calls/no-content/55"
    And , verify "<deletedItem.REQUEST.method>" equals "DELETE"
    And , verify "<deletedItem.RESPONSE.method>" equals "DELETE"
    And , verify "<deletedItem.RESPONSE.statusCode>" equals "204"
    And , verify "<deletedItem.RESPONSE.headers.X-deleted-item>" equals "55"
    And , verify "<deletedItem.RESPONSE.body>" equals ""

  Scenario: Map a raw XML request body with the TEXT DocString mapper
    When RUN "soapAdd" SERVICE CALL: %soap-add
      | endpoint              | traceId       | left | right |
      | http://127.0.0.1:8765 | soap-map-test | 11   | 6     |
    Then , verify "<soapAdd.REQUEST.endpoint>" equals "http://127.0.0.1:8765/soap/calculator"
    And , verify "<soapAdd.REQUEST.method>" equals "POST"
    And , verify "<soapAdd.REQUEST.contentType>" equals "text/xml"
    And , verify "<soapAdd.REQUEST.headers.SOAPAction>" equals "urn:pickleball:calculator#Add"
    And , verify "<soapAdd.REQUEST.body>" contains "urn:pickleball:calculator"
    And , verify "<soapAdd.REQUEST.body>" contains "11"
    And , verify "<soapAdd.RESPONSE.method>" equals "POST"
    And , verify "<soapAdd.RESPONSE.statusCode>" equals "200"
    And , verify "<soapAdd.RESPONSE.body>" contains "17"

  Scenario: Preserve a component reference that ends before sending an HTTP request
    When RUN "earlyExit" SERVICE CALL: %early-exit
      | endpoint              |
      | http://127.0.0.1:8765 |

    Then , verify "<earlyExit.REQUEST.method>" equals "GET"
    And , verify "<earlyExit.REQUEST.queryParams.mode>" equals "must-not-run"

  Scenario: Use a regular nested service call through the shared RunMap
    When RUN "nestedCall" SERVICE CALL: %nestedComponent
      | url                   | client        | scope          |
      | http://127.0.0.1:8765 | nested-client | inventory.read |
    Then , verify "<TOKEN.REQUEST.endpoint>" equals "http://127.0.0.1:8765/api/service-calls/token"
    And , verify "<TOKEN.REQUEST.method>" equals "POST"
    And , verify "<TOKEN.REQUEST.headers.X-Test-Client>" equals "nested-client"
    And , verify "<TOKEN.REQUEST.queryParams.scope>" equals "inventory.read"
    And , verify "<TOKEN.REQUEST.body.grantType>" equals "client_credentials"
    And , verify "<TOKEN.RESPONSE.method>" equals "POST"
    And , verify "<TOKEN.RESPONSE.statusCode>" equals "200"
    And , verify "<TOKEN.RESPONSE.body.accessToken>" equals "inline-nested-client-inventory-read"
    And , verify "<TOKEN.RESPONSE.body.tokenType>" equals "Bearer"
    And , verify "<TOKEN.RESPONSE.body.scope>" equals "inventory.read"
    And , verify "<TOKEN.RESPONSE.body.client>" equals "nested-client"
    And , verify "<TOKEN.RESPONSE.body.request.grantType>" equals "client_credentials"
    And , verify "<nestedCall.REQUEST.endpoint>" equals "http://127.0.0.1:8765/api/service-calls/protected"
    And , verify "<nestedCall.REQUEST.method>" equals "GET"
    And , verify "<nestedCall.REQUEST.headers.X-Test-Client>" equals "nested-client"
    And , verify "<nestedCall.REQUEST.headers.Authorization>" equals "Bearer inline-nested-client-inventory-read"
    And , verify "<nestedCall.REQUEST.queryParams.scope>" equals "inventory.read"
    And , verify "<nestedCall.RESPONSE.method>" equals "GET"
    And , verify "<nestedCall.RESPONSE.statusCode>" equals "200"
    And , verify "<nestedCall.RESPONSE.body.authorized>" equals "true"
    And , verify "<nestedCall.RESPONSE.body.client>" equals "nested-client"
    And , verify "<nestedCall.RESPONSE.body.scope>" equals "inventory.read"
    And , verify "<nestedCall.RESPONSE.body.token>" equals "inline-nested-client-inventory-read"

  Scenario: Use inline CALL without RETURN to receive the completed child root
    When CALL: %serviceCallA
      | RunKey    | url                   | client        | scope        |
      | inlineCall | http://127.0.0.1:8765 | inline-client | catalog.read |
    Then , verify "<inlineCall.TOKEN.REQUEST.endpoint>" equals "http://127.0.0.1:8765/api/service-calls/token"
    And , verify "<inlineCall.TOKEN.REQUEST.method>" equals "POST"
    And , verify "<inlineCall.TOKEN.REQUEST.headers.X-Test-Client>" equals "inline-client"
    And , verify "<inlineCall.TOKEN.REQUEST.queryParams.scope>" equals "catalog.read"
    And , verify "<inlineCall.TOKEN.REQUEST.body.grantType>" equals "client_credentials"
    And , verify "<inlineCall.TOKEN.RESPONSE.method>" equals "POST"
    And , verify "<inlineCall.TOKEN.RESPONSE.statusCode>" equals "200"
    And , verify "<inlineCall.TOKEN.RESPONSE.body.accessToken>" equals "inline-inline-client-catalog-read"
    And , verify "<inlineCall.TOKEN.RESPONSE.body.tokenType>" equals "Bearer"
    And , verify "<inlineCall.TOKEN.RESPONSE.body.scope>" equals "catalog.read"
    And , verify "<inlineCall.TOKEN.RESPONSE.body.client>" equals "inline-client"
    And , verify "<inlineCall.TOKEN.RESPONSE.body.request.grantType>" equals "client_credentials"
    And , verify "<inlineCall.REQUEST.endpoint>" equals "http://127.0.0.1:8765/api/service-calls/protected"
    And , verify "<inlineCall.REQUEST.method>" equals "GET"
    And , verify "<inlineCall.REQUEST.headers.X-Test-Client>" equals "inline-client"
    And , verify "<inlineCall.REQUEST.headers.Authorization>" equals "Bearer inline-inline-client-catalog-read"
    And , verify "<inlineCall.REQUEST.queryParams.scope>" equals "catalog.read"
    And , verify "<inlineCall.RESPONSE.method>" equals "GET"
    And , verify "<inlineCall.RESPONSE.statusCode>" equals "200"
    And , verify "<inlineCall.RESPONSE.body.authorized>" equals "true"
    And , verify "<inlineCall.RESPONSE.body.client>" equals "inline-client"
    And , verify "<inlineCall.RESPONSE.body.scope>" equals "catalog.read"
    And , verify "<inlineCall.RESPONSE.body.token>" equals "inline-inline-client-catalog-read"

  Scenario: Use inline CALL with a nested RETURN value
    When CALL: %serviceCallB
      | RunKey       | url                   | client        | scope        |
      | inlineReturn | http://127.0.0.1:8765 | return-client | orders.write |
    Then , verify "<inlineReturn.TOKEN>" equals "inline-return-client-orders-write"
    And , verify "<inlineReturn.REQUEST.endpoint>" equals "http://127.0.0.1:8765/api/service-calls/protected"
    And , verify "<inlineReturn.REQUEST.method>" equals "GET"
    And , verify "<inlineReturn.REQUEST.headers.X-Test-Client>" equals "return-client"
    And , verify "<inlineReturn.REQUEST.headers.Authorization>" equals "Bearer inline-return-client-orders-write"
    And , verify "<inlineReturn.REQUEST.queryParams.scope>" equals "orders.write"
    And , verify "<inlineReturn.RESPONSE.method>" equals "GET"
    And , verify "<inlineReturn.RESPONSE.statusCode>" equals "200"
    And , verify "<inlineReturn.RESPONSE.body.authorized>" equals "true"
    And , verify "<inlineReturn.RESPONSE.body.client>" equals "return-client"
    And , verify "<inlineReturn.RESPONSE.body.scope>" equals "orders.write"
    And , verify "<inlineReturn.RESPONSE.body.token>" equals "inline-return-client-orders-write"

  Scenario: Preserve an explicit null RETURN from CALL
    When MAP TABLE VALUES
      | explicitNullResult | <$CALL:SCENARIO: ExplicitNullReturnCall> | fallback |
    Then , verify "<explicitNullResult>" equals "fallback"
