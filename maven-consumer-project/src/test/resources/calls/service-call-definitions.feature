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
      | Scenario Tags | endpoint                  | client         | traceId    | include | mode    |
      | %inspect-get | http://127.0.0.1:8765     | default-client | get-default | none    | summary |


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
      | Scenario Tags | endpoint                  | client         | traceId     | cookieValue | mode   | status | name    | quantity |
      | %inspect-post | http://127.0.0.1:8765     | default-client | post-default | default     | create | 201    | default | 1        |


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
      | Scenario Tags | endpoint                  | status |
      | %status-call | http://127.0.0.1:8765     | 418    |


  # This component intentionally omits CONFIGURATION and uses SEND SERVICE CALL.
  # Its scenario name is used as the caller's fallback object key.
  Scenario Outline: HealthCall
    Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint | <endpoint>/api/health |
      | method   | GET                   |
      | accept   | application/json      |
    When SEND SERVICE CALL

    Examples:
      | Scenario Tags | endpoint                  |
      | %health-full-url | http://127.0.0.1:8765     |


  Scenario Outline: DeleteCall
    Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint | <endpoint>/api/service-calls/no-content/<itemId> |
      | method   | DELETE                                               |
    When EXECUTE SERVICE CALL

    Examples:
      | Scenario Tags | endpoint                  | itemId |
      | %delete-call | http://127.0.0.1:8765     | 1      |


  Scenario Outline: SoapAddCall
    Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint    | <endpoint>/soap/calculator |
      | method      | POST                       |
      | accept      | text/xml                   |
      | contentType | text/xml                   |
    And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
      | SOAPAction  | urn:pickleball:calculator#Add |
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
      | Scenario Tags | endpoint                  | traceId       | left | right |
      | %soap-add | http://127.0.0.1:8765     | soap-default  | 5    | 7     |


  # END SCENARIO must stop the remaining component steps while the synthetic
  # ALWAYS_RUN finalizer still saves the partial object with RESPONSE: {}.
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
      | Scenario Tags | endpoint                  |
      | %early-exit | http://127.0.0.1:8765     |
