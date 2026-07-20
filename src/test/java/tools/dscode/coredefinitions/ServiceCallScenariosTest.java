package tools.dscode.coredefinitions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServiceCallScenariosTest {

    @Test
    void extractsMultiplePercentCallIdentifiersInOrder() {
        assertEquals(
                List.of("%create-user", "%get-user"),
                ServiceCallScenarios.extractCallTags(
                        "CALLS: %create-user, then %get-user"
                )
        );
    }

    @Test
    void addsPercentPrefixToASinglePlainIdentifier() {
        assertEquals(
                List.of("%health"),
                ServiceCallScenarios.extractCallTags("health")
        );
    }

    @Test
    void ignoresBlankCallText() {
        assertEquals(List.of(), ServiceCallScenarios.extractCallTags("   "));
    }
}
