package com.example.pickleball.steps;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import tools.dscode.common.servicecalls.CallMap;

public final class ServiceCallSteps {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private CallMap serviceCall;
    private Response response;

    @Given("a service call configured as")
    public void configureServiceCall(String configurationJson) {
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(configurationJson);

            if (!(parsed instanceof ObjectNode requestConfiguration)) {
                throw new IllegalArgumentException(
                        "The service-call configuration must be a JSON object."
                );
            }

            serviceCall = new CallMap(requestConfiguration);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "The service-call configuration is not valid JSON.",
                    exception
            );
        }
    }

    @When("I run the service call")
    public void runServiceCall() {
        if (serviceCall == null) {
            throw new IllegalStateException("No service call has been configured.");
        }

        response = serviceCall.run();

        if (response == null) {
            throw new AssertionError("The service call did not return a response.");
        }
    }

    @Then("the service response status is {int}")
    public void verifyStatusCode(int expectedStatusCode) {
        requireResponse();

        int actualStatusCode = response.getStatusCode();
        if (actualStatusCode != expectedStatusCode) {
            throw new AssertionError(
                    "Expected status " + expectedStatusCode
                            + " but received " + actualStatusCode
                            + ". Response body: " + response.asString()
            );
        }
    }

    @Then("the service response JSON path {string} is {string}")
    public void verifyJsonPath(String jsonPath, String expectedValue) {
        requireResponse();

        Object actualValue = response.jsonPath().get(jsonPath);
        String actualText = String.valueOf(actualValue);

        if (!expectedValue.equals(actualText)) {
            throw new AssertionError(
                    "Expected JSON path '" + jsonPath + "' to be '"
                            + expectedValue + "' but was '" + actualText
                            + "'. Response body: " + response.asString()
            );
        }
    }

    @Then("the service response header {string} is {string}")
    public void verifyHeader(String headerName, String expectedValue) {
        requireResponse();

        String actualValue = response.getHeader(headerName);
        if (!expectedValue.equals(actualValue)) {
            throw new AssertionError(
                    "Expected header '" + headerName + "' to be '"
                            + expectedValue + "' but was '" + actualValue + "'."
            );
        }
    }

    private void requireResponse() {
        if (response == null) {
            throw new IllegalStateException("The service call has not been run.");
        }
    }
}
