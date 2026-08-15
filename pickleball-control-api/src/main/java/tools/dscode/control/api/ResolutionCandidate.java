package tools.dscode.control.api;

/** One NodeMap consulted by a mapping explanation. */
public record ResolutionCandidate(
        int order,
        String mapType,
        boolean matched,
        Object value
) {
}
