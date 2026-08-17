package tools.dscode.studio.runtime;

public record RuntimeServiceCallEvidence(
        String selector,
        RuntimeBoundedJsonEvidence request,
        RuntimeBoundedJsonEvidence configuration,
        RuntimeBoundedJsonEvidence response,
        Integer statusCode
) { }
