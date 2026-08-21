package tools.dscode.control.protocol;

/** Structured service-call evidence represented only as bounded wire data. */
public record ControlBridgeServiceCallEvidence(
        String selector,
        ControlBridgeBoundedJsonEvidence request,
        ControlBridgeBoundedJsonEvidence configuration,
        ControlBridgeBoundedJsonEvidence response,
        Integer statusCode
) {
}
