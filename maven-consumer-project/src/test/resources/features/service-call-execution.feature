@service-call @local-api
Feature: Service call orchestration with generic request mappings

  Scenario: Select by inline percent tag and save under the quoted object name
    When "inlineRead" SERVICE CALL: %inspect-get
      | endpoint                  | client      | traceId     | include   | mode |
      | http://127.0.0.1:8765     | caller-test | trace-get-1 | inventory | full |

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


  Scenario: Select with Run Tags and save under an exact Call Key header
    When SERVICE CALLS
      | Run Tags     | Call Key | endpoint                  | client     | traceId     | cookieValue | mode   | status | name   | quantity |
      | %inspect-post | tablePost | http://127.0.0.1:8765     | table-test | trace-post-1 | cookie-42   | create | 201    | Widget | 3        |

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


  Scenario: Call Key takes precedence over the quoted inline object name
    When "inlineMustLose" SERVICE CALL: %status-call
      | Call Key  | endpoint                  | status |
      | tableWins | http://127.0.0.1:8765     | 422    |

    Then , verify "<tableWins.REQUEST.queryParams.status>" equals "422"
    And , verify "<tableWins.RESPONSE.statusCode>" equals "422"
    And , verify "<tableWins.RESPONSE.body.status>" equals "422"
    And , verify "<tableWins.RESPONSE.body.method>" equals "GET"


  Scenario: Fall back to the resolved component scenario name when no object key is supplied
    When SERVICE CALL
      | Run Tags         | endpoint                  |
      | %health-full-url | http://127.0.0.1:8765     |

    Then , verify "<HealthCall.REQUEST.endpoint>" equals "http://127.0.0.1:8765/api/health"
    And , verify "<HealthCall.RESPONSE.method>" equals "GET"
    And , verify "<HealthCall.RESPONSE.statusCode>" equals "200"
    And , verify "<HealthCall.RESPONSE.body.status>" equals "UP"
    And , verify "<HealthCall.RESPONSE.body.service>" equals "pickleball-local"


  Scenario: Treat an HTTP 500 response as a normal service response
    When "serverFailure" SERVICE CALL
      | Run Tags    | endpoint                  | status |
      | %status-call | http://127.0.0.1:8765     | 500    |

    Then , verify "<serverFailure.RESPONSE.method>" equals "GET"
    And , verify "<serverFailure.RESPONSE.statusCode>" equals "500"
    And , verify "<serverFailure.RESPONSE.body.status>" equals "500"


  Scenario: Reusing a Call Key follows ordinary NodeMap replacement behavior
    When SERVICE CALL
      | Run Tags    | Call Key     | endpoint                  | status |
      | %status-call | latestStatus | http://127.0.0.1:8765     | 404    |
    And SERVICE CALL
      | Run Tags    | Call Key     | endpoint                  | status |
      | %status-call | latestStatus | http://127.0.0.1:8765     | 503    |

    Then , verify "<latestStatus.RESPONSE.statusCode>" equals "503"
    And , verify "<latestStatus.RESPONSE.body.status>" equals "503"


  Scenario: Preserve a no-content response and its response headers
    When "deletedItem" SERVICE CALL: %delete-call
      | endpoint                  | itemId |
      | http://127.0.0.1:8765     | 55     |

    Then , verify "<deletedItem.REQUEST.endpoint>" equals "http://127.0.0.1:8765/api/service-calls/no-content/55"
    And , verify "<deletedItem.REQUEST.method>" equals "DELETE"
    And , verify "<deletedItem.RESPONSE.method>" equals "DELETE"
    And , verify "<deletedItem.RESPONSE.statusCode>" equals "204"
    And , verify "<deletedItem.RESPONSE.headers.X-deleted-item>" equals "55"
    And , verify "<deletedItem.RESPONSE.body>" equals ""


  Scenario: Map a raw XML request body with the TEXT DocString mapper
    When "soapAdd" SERVICE CALL: %soap-add
      | endpoint                  | traceId       | left | right |
      | http://127.0.0.1:8765     | soap-map-test | 11   | 6     |

    Then , verify "<soapAdd.REQUEST.endpoint>" equals "http://127.0.0.1:8765/soap/calculator"
    And , verify "<soapAdd.REQUEST.method>" equals "POST"
    And , verify "<soapAdd.REQUEST.contentType>" equals "text/xml"
    And , verify "<soapAdd.REQUEST.headers.SOAPAction>" equals "urn:pickleball:calculator#Add"
    And , verify "<soapAdd.REQUEST.body>" contains "urn:pickleball:calculator"
    And , verify "<soapAdd.REQUEST.body>" contains "11"
    And , verify "<soapAdd.RESPONSE.method>" equals "POST"
    And , verify "<soapAdd.RESPONSE.statusCode>" equals "200"
    And , verify "<soapAdd.RESPONSE.body>" contains "17"


  Scenario: Finalize a component that ends before sending an HTTP request
    When "earlyExit" SERVICE CALL: %early-exit
      | endpoint                  |
      | http://127.0.0.1:8765     |

    Then , verify "<earlyExit.REQUEST.method>" equals "GET"
    And , verify "<earlyExit.REQUEST.queryParams.mode>" equals "must-not-run"
    And , verify "<earlyExit.RESPONSE>" equals "{}"
