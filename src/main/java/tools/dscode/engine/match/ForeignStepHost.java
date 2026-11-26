package tools.dscode.engine.match;

import java.util.List;

/**
 * One language adapter for a stored step definition.
 * {@link tools.dscode.engine.PickleballEngine} loads these once per run and does not branch on language.
 */
public interface ForeignStepHost {
    String language();

    boolean supports(String language);

    Object invoke(String definitionId, String stepText, List<String> argsJson);
}
