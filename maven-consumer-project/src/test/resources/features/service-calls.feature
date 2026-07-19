@all @servicecalls
Feature: Local service calls

  Scenario: Call a local health endpoint
    Given a service call configured as
      """
      {
        "baseUri": "http://127.0.0.1",
        "port": 8765,
        "method": "GET",
        "endpoint": "/api/health",
        "accept": "application/json"
      }
      """
    When I run the service call
    Then the service response status is 200
    And the service response JSON path "status" is "UP"
    And the service response JSON path "service" is "pickleball-local"

  Scenario: Send path parameters, query parameters, and headers
    Given a service call configured as
      """
      {
        "baseUri": "http://127.0.0.1",
        "port": 8765,
        "method": "GET",
        "endpoint": "/api/users/{id}",
        "pathParams": {
          "id": "42"
        },
        "queryParams": {
          "include": "details"
        },
        "headers": {
          "X-Test-Client": "pickleball"
        },
        "accept": "application/json"
      }
      """
    When I run the service call
    Then the service response status is 200
    And the service response JSON path "id" is "42"
    And the service response JSON path "include" is "details"
    And the service response JSON path "client" is "pickleball"

  Scenario: Post a JSON request body
    Given a service call configured as
      """
      {
        "baseUri": "http://127.0.0.1",
        "port": 8765,
        "method": "POST",
        "endpoint": "/api/echo",
        "contentType": "application/json",
        "accept": "application/json",
        "body": {
          "name": "Pickleball",
          "enabled": true
        }
      }
      """
    When I run the service call
    Then the service response status is 200
    And the service response JSON path "method" is "POST"
    And the service response JSON path "body.name" is "Pickleball"
    And the service response JSON path "body.enabled" is "true"

  Scenario: Test a non-success status
    Given a service call configured as
      """
      {
        "baseUri": "http://127.0.0.1",
        "port": 8765,
        "method": "GET",
        "endpoint": "/api/status/404",
        "accept": "application/json"
      }
      """
    When I run the service call
    Then the service response status is 404
    And the service response JSON path "status" is "404"
