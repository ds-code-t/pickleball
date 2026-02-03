package tools.dscode.common.servicecalls;

import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import tools.dscode.common.mappings.NodeMap;

import static tools.dscode.common.assertions.ConditionRepeater.repeatUntil;
import static tools.dscode.common.assertions.ConditionRepeater.repeatUntilAny;
import static tools.dscode.common.servicecalls.RestAssuredUtil.buildRequest;
import static tools.dscode.common.servicecalls.RestAssuredUtil.extractResponse;

public class CallMap extends NodeMap {

    public RequestSpecification request;
    public Response response;

    public CallMap(JsonNode requestConfig) {
        request = buildRequest(requestConfig);
        root.set("request", requestConfig);
    }

    public Response runUntil(String... conditions) {
        return (Response) repeatUntil(this::runServiceCall, conditions);
    }

    public Response runUntilAny(String... conditions) {
        return (Response) repeatUntilAny(this::runServiceCall, conditions);
    }

    public Response runServiceCall() {
        response = request.get();
        root.set("response", extractResponse(response));
        return response;
    }

}
