package tools.dscode.control.api;

/** Structured evidence from one existing Pickleball service-call definition. */
public record ServiceCallEvidence(
        String selector,
        BoundedJsonEvidence request,
        BoundedJsonEvidence configuration,
        BoundedJsonEvidence response,
        Integer statusCode
) {
}
