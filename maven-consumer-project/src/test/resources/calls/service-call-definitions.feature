Feature: Reusable service call definitions

  # These component scenarios use only the generic mapping steps.
  # REQUEST contains the complete endpoint, including scheme, host, port, and path.
  # EXECUTE SERVICE CALL initializes RESPONSE before the HTTP request is attempted.
  Scenario Outline: InspectGetCall
    Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint | <endpoint>/api/service-calls/inspect |
      | method   | GET                                  |
      | accept   | application/json                     |
    And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
      | Accept        | application/json |
      | X-Test-Client | <client>         |
      | X-Test-Trace  | <traceId>        |
    And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
      | include | <include> |
      | mode    | <mode>    |
    And MAP "CONFIGURATION" TABLE VALUES TO SCENARIO MAP
      | urlEncodingEnabled     | true |
      | relaxedHTTPSValidation |      |
    When EXECUTE SERVICE CALL
    Examples:
      | Scenario Tags | endpoint              | client         | traceId     | include | mode    |
      | %inspect-get  | http://127.0.0.1:8765 | default-client | get-default | none    | summary |

  Scenario Outline: InspectPostCall
    Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint    | <endpoint>/api/service-calls/inspect |
      | method      | POST                                 |
      | accept      | application/json                     |
      | contentType | application/json                     |
    And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
      | X-Test-Client | <client>  |
      | X-Test-Trace  | <traceId> |
    And MAP "REQUEST.cookies" TABLE VALUES TO SCENARIO MAP
      | serviceCookie | <cookieValue> |
    And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
      | mode   | <mode>   |
      | status | <status> |
    And MAP "REQUEST.body" OBJECT VALUE TO SCENARIO MAP
      """json
      {
        "name": "<name>",
        "quantity": <quantity>,
        "active": true
      }
      """
    And MAP "CONFIGURATION" TABLE VALUES TO SCENARIO MAP
      | urlEncodingEnabled | true |
    When EXECUTE SERVICE CALL
    Examples:
      | Scenario Tags | endpoint              | client         | traceId      | cookieValue | mode   | status | name    | quantity |
      | %inspect-post | http://127.0.0.1:8765 | default-client | post-default | default     | create | 201    | default | 1        |

  # These components validate runtime template resolution rather than only
  # Cucumber Scenario Outline substitution. JSON can use the ordinary <...>
  # reference bookends. Unquoted numeric/boolean references preserve JSON types,
  # embedded references build a larger string, and nested object fields are
  # resolved individually before the completed JSON text is parsed.
  Scenario Outline: MappedJsonBodyCall
    Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint    | <endpoint>/api/service-calls/inspect |
      | method      | POST                                 |
      | accept      | application/json                     |
      | contentType | application/json                     |
    And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
      | X-Test-Client | <PARENT.SCENARIO:jsonTemplate.client>             |
      | X-Test-Trace  | <PARENT.SCENARIO:jsonTemplate.metadata.requestId> |
    And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
      | mode   | mapped-json |
      | status | 202         |
    And MAP "REQUEST.body" OBJECT VALUE TO SCENARIO MAP
      """json
      {
        "name": "<PARENT.SCENARIO:jsonTemplate.item.name>",
        "quantity": <PARENT.SCENARIO:jsonTemplate.item.quantity>,
        "active": <PARENT.SCENARIO:jsonTemplate.item.active>,
        "description": "<PARENT.SCENARIO:jsonTemplate.item.name> from <PARENT.SCENARIO:jsonTemplate.metadata.source>",
        "metadata": {
          "requestId": "<PARENT.SCENARIO:jsonTemplate.metadata.requestId>",
          "source": "<PARENT.SCENARIO:jsonTemplate.metadata.source>"
        }
      }
      """
    And MAP "CONFIGURATION" TABLE VALUES TO SCENARIO MAP
      | urlEncodingEnabled | true |
    When EXECUTE SERVICE CALL
    Examples:
      | Scenario Tags     | endpoint              |
      | %mapped-json-body | http://127.0.0.1:8765 |

  # ~unquoted; allows the template itself to remain valid JSON while inserting
  # resolved numbers, booleans, objects, or arrays as raw JSON values. String
  # references remain normally quoted. The suffix is part of the Pickleball
  # runtime reference and is removed before the lookup is performed.
  Scenario Outline: UnquotedJsonBodyCall
    Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint    | <endpoint>/api/service-calls/inspect |
      | method      | POST                                 |
      | accept      | application/json                     |
      | contentType | application/json                     |
    And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
      | X-Test-Client | <PARENT.SCENARIO:unquoteTemplate.client>    |
      | X-Test-Trace  | <PARENT.SCENARIO:unquoteTemplate.requestId> |
    And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
      | mode   | unquote-json |
      | status | 200          |
    And MAP "REQUEST.body" OBJECT VALUE TO SCENARIO MAP
      """json
      {
        "name": "<PARENT.SCENARIO:unquoteTemplate.name>",
        "quantity": "<PARENT.SCENARIO:unquoteTemplate.quantity~unquoted;>",
        "active": "<PARENT.SCENARIO:unquoteTemplate.active~unquoted;>",
        "unitPrice": "<PARENT.SCENARIO:unquoteTemplate.unitPrice~unquoted;>",
        "metadata": "<PARENT.SCENARIO:unquoteTemplate.metadata~unquoted;>",
        "items": "<PARENT.SCENARIO:unquoteTemplate.items[]~unquoted;>",
        "description": "<PARENT.SCENARIO:unquoteTemplate.name> has <PARENT.SCENARIO:unquoteTemplate.items[0].count> first-item units"
      }
      """
    And MAP "CONFIGURATION" TABLE VALUES TO SCENARIO MAP
      | urlEncodingEnabled | true |
    When EXECUTE SERVICE CALL
    Examples:
      | Scenario Tags       | endpoint              |
      | %unquoted-json-body | http://127.0.0.1:8765 |

  # This component resolves values that were produced and mapped by an earlier
  # service call in the caller. Completed service calls are registered in the
  # shared RunMap, so those references are intentionally not PARENT.SCENARIO
  # references. The body covers scalar, embedded, nested-object, and status values.
  Scenario Outline: PreviousResponseJsonBodyCall
    Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint    | <endpoint>/api/service-calls/inspect |
      | method      | POST                                 |
      | accept      | application/json                     |
      | contentType | application/json                     |
    And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
      | X-Test-Client | <PARENT.SCENARIO:jsonChain.client>    |
      | X-Test-Trace  | <PARENT.SCENARIO:jsonChain.requestId> |
    And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
      | mode   | previous-response-json |
      | status | 203                    |
    And MAP "REQUEST.body" OBJECT VALUE TO SCENARIO MAP
      """json
      {
        "name": "<seedJson.RESPONSE.body.body.name>",
        "quantity": <seedJson.RESPONSE.body.body.quantity>,
        "active": <seedJson.RESPONSE.body.body.active>,
        "description": "copied <seedJson.RESPONSE.body.body.name> at quantity <seedJson.RESPONSE.body.body.quantity>",
        "original": {
          "name": "<seedJson.RESPONSE.body.body.name>",
          "quantity": <seedJson.RESPONSE.body.body.quantity>,
          "active": <seedJson.RESPONSE.body.body.active>
        },
        "metadata": {
          "requestId": "<PARENT.SCENARIO:jsonChain.requestId>",
          "sourceStatus": <seedJson.RESPONSE.statusCode>
        }
      }
      """
    And MAP "CONFIGURATION" TABLE VALUES TO SCENARIO MAP
      | urlEncodingEnabled | true |
    When EXECUTE SERVICE CALL
    Examples:
      | Scenario Tags                | endpoint              |
      | %previous-response-json-body | http://127.0.0.1:8765 |

  Scenario Outline: StatusCall
    Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint | <endpoint>/api/service-calls/inspect |
      | method   | GET                                  |
      | accept   | application/json                     |
    And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
      | status | <status>    |
      | mode   | status-test |
    When EXECUTE SERVICE CALL
    Examples:
      | Scenario Tags | endpoint              | status |
      | %status-call  | http://127.0.0.1:8765 | 418    |

  # This component intentionally omits CONFIGURATION and uses EXECUTE SERVICE CALL.
  # Its scenario name is used by the deprecated compatibility wrapper only.
  Scenario Outline: HealthCall
    Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint | <endpoint>/api/health |
      | method   | GET                   |
      | accept   | application/json      |
    When EXECUTE SERVICE CALL
    Examples:
      | Scenario Tags    | endpoint              |
      | %health-full-url | http://127.0.0.1:8765 |

  Scenario Outline: DeleteCall
    Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint | <endpoint>/api/service-calls/no-content/<itemId> |
      | method   | DELETE                                           |
    When EXECUTE SERVICE CALL
    Examples:
      | Scenario Tags | endpoint              | itemId |
      | %delete-call  | http://127.0.0.1:8765 | 1      |

  Scenario Outline: SoapAddCall
    Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint    | <endpoint>/soap/calculator |
      | method      | POST                       |
      | accept      | text/xml                   |
      | contentType | text/xml                   |
    And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
      | SOAPAction   | urn:pickleball:calculator#Add |
      | X-Test-Trace | <traceId>                     |
    And MAP "REQUEST.body" TEXT VALUE TO SCENARIO MAP
      """xml
      <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                        xmlns:calc="urn:pickleball:calculator">
        <soapenv:Header/>
        <soapenv:Body>
          <calc:Add>
            <calc:left><left></calc:left>
            <calc:right><right></calc:right>
          </calc:Add>
        </soapenv:Body>
      </soapenv:Envelope>
      """
    And MAP "CONFIGURATION" TABLE VALUES TO SCENARIO MAP
      | urlEncodingEnabled | true |
    When EXECUTE SERVICE CALL
    Examples:
      | Scenario Tags | endpoint              | traceId      | left | right |
      | %soap-add     | http://127.0.0.1:8765 | soap-default | 5    | 7     |

  # XML-looking input deliberately uses only the XML-safe ~[~...~]~ reference
  # bookends. The left operand comes from the parent scenario map. The completed
  # earlier service call is registered in the shared RunMap, so the right operand
  # uses an unqualified RunMap reference rather than PARENT.SCENARIO.
  Scenario Outline: MappedSoapAddCall
    Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint    | <endpoint>/soap/calculator |
      | method      | POST                       |
      | accept      | text/xml                   |
      | contentType | text/xml                   |
    And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
      | SOAPAction   | urn:pickleball:calculator#Add          |
      | X-Test-Trace | <PARENT.SCENARIO:soapTemplate.traceId> |
    And MAP "REQUEST.body" TEXT VALUE TO SCENARIO MAP
      """xml
      <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                        xmlns:calc="urn:pickleball:calculator">
        <soapenv:Header/>
        <soapenv:Body>
          <calc:Add>
            <calc:left>~[~PARENT.SCENARIO:soapTemplate.left~]~</calc:left>
            <calc:right>~[~soapSeed.RESPONSE.body.body.quantity~]~</calc:right>
          </calc:Add>
        </soapenv:Body>
      </soapenv:Envelope>
      """
    And MAP "CONFIGURATION" TABLE VALUES TO SCENARIO MAP
      | urlEncodingEnabled | true |
    When EXECUTE SERVICE CALL
    Examples:
      | Scenario Tags     | endpoint              |
      | %mapped-soap-body | http://127.0.0.1:8765 |

  # END SCENARIO stops the remaining component steps. The caller already
  # holds the component root by reference, so its partial REQUEST remains visible.
  Scenario Outline: EarlyExitCall
    Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint | <endpoint>/api/service-calls/inspect |
      | method   | GET                                  |
    And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
      | mode | must-not-run |
    And MAP "CONFIGURATION" TABLE VALUES TO SCENARIO MAP
      | urlEncodingEnabled | true |
    And END SCENARIO
    When EXECUTE SERVICE CALL
    Examples:
      | Scenario Tags | endpoint              |
      | %early-exit   | http://127.0.0.1:8765 |

  # CALL executes this component synchronously and, because RETURN is not set,
  # returns the completed default scenario-map root. Parent values are resolved
  # through scenario ancestry rather than an explicitly inserted PARENT object.
  Scenario Outline: TokenCall
    Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint    | <PARENT.SCENARIO:url>/api/service-calls/token |
      | method      | POST                                         |
      | accept      | application/json                             |
      | contentType | application/json                             |
    And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
      | X-Test-Client | <PARENT.SCENARIO:client> |
    And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
      | scope | <PARENT.SCENARIO:scope> |
    And MAP "REQUEST.body" OBJECT VALUE TO SCENARIO MAP
      """json
      {
        "grantType": "client_credentials"
      }
      """
    When EXECUTE SERVICE CALL
    Examples:
      | Scenario Tags |
      | %TokenCall    |

  # This owner uses the inline CALL fallback result: TOKEN receives the complete
  # child scenario-map root, including REQUEST and RESPONSE.
  Scenario Outline: ServiceCallA
    Given MAP TABLE VALUES TO SCENARIO MAP
      | url    | <url>    |
      | client | <client> |
      | scope  | <scope>  |
    And MAP TABLE VALUES TO SCENARIO MAP
      | TOKEN | <$CALL:%TokenCall> |
    And MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint | <url>/api/service-calls/protected |
      | method   | GET                                |
      | accept   | application/json                   |
    And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
      | X-Test-Client | <client>                                 |
      | Authorization | Bearer <TOKEN.RESPONSE.body.accessToken> |
    And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
      | scope | <scope> |
    When EXECUTE SERVICE CALL
    Examples:
      | Scenario Tags | url                   | client         | scope        |
      | %serviceCallA | http://127.0.0.1:8765 | default-client | catalog.read |

  # This inline component uses the special RETURN key. CALL returns only the
  # access-token string instead of the complete child scenario-map root.
  Scenario Outline: TokenValueCall
    Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint    | <PARENT.SCENARIO:url>/api/service-calls/token |
      | method      | POST                                         |
      | accept      | application/json                             |
      | contentType | application/json                             |
    And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
      | X-Test-Client | <PARENT.SCENARIO:client> |
    And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
      | scope | <PARENT.SCENARIO:scope> |
    And MAP "REQUEST.body" OBJECT VALUE TO SCENARIO MAP
      """json
      {
        "grantType": "client_credentials"
      }
      """
    When EXECUTE SERVICE CALL
    And MAP TABLE VALUES TO SCENARIO MAP
      | RETURN | <SCENARIO:RESPONSE.body.accessToken> |
    Examples:
      | Scenario Tags   |
      | %TokenValueCall |

  Scenario: ExplicitNullReturnCall
    Given MAP TABLE VALUES TO SCENARIO MAP
      | RETURN | <^~NULL~^> |

  # This owner receives only TokenValueCall's explicit RETURN value under TOKEN.
  Scenario Outline: ServiceCallB
    Given MAP TABLE VALUES TO SCENARIO MAP
      | url    | <url>    |
      | client | <client> |
      | scope  | <scope>  |
    And MAP TABLE VALUES TO SCENARIO MAP
      | TOKEN | <$CALL:%TokenValueCall> |
    And MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint | <url>/api/service-calls/protected |
      | method   | GET                                |
      | accept   | application/json                   |
    And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
      | X-Test-Client | <client>       |
      | Authorization | Bearer <TOKEN> |
    And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
      | scope | <scope> |
    When EXECUTE SERVICE CALL
    Examples:
      | Scenario Tags | url                   | client         | scope        |
      | %serviceCallB | http://127.0.0.1:8765 | default-client | catalog.read |

  # This ordinary nested component also resolves its inputs from its parent
  # scenario map instead of receiving duplicate values through an invocation table.
  Scenario Outline: NestedTokenComponent
    Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint    | <PARENT.SCENARIO:url>/api/service-calls/token |
      | method      | POST                                         |
      | accept      | application/json                             |
      | contentType | application/json                             |
    And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
      | X-Test-Client | <PARENT.SCENARIO:client> |
    And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
      | scope | <PARENT.SCENARIO:scope> |
    And MAP "REQUEST.body" OBJECT VALUE TO SCENARIO MAP
      """json
      {
        "grantType": "client_credentials"
      }
      """
    When EXECUTE SERVICE CALL
    Examples:
      | Scenario Tags         |
      | %nestedTokenComponent |

  # This component performs an ordinary nested RUN SERVICE CALL. Its child resolves
  # url, client, and scope from this component's scenario map through ancestry.
  # The child root is still registered by reference under TOKEN in the shared RunMap.
  Scenario Outline: NestedComponent
    Given MAP TABLE VALUES TO SCENARIO MAP
      | url    | <url>    |
      | client | <client> |
      | scope  | <scope>  |
    And RUN "TOKEN" SERVICE CALL: %nestedTokenComponent
    And MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint | <url>/api/service-calls/protected |
      | method   | GET                                |
      | accept   | application/json                   |
    And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
      | X-Test-Client | <client>                                 |
      | Authorization | Bearer <TOKEN.RESPONSE.body.accessToken> |
    And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
      | scope | <scope> |
    When EXECUTE SERVICE CALL
    Examples:
      | Scenario Tags    | url                   | client         | scope          |
      | %nestedComponent | http://127.0.0.1:8765 | default-client | inventory.read |
