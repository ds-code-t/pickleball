package tools.dscode.dolt.usage;

import io.cucumber.java.en.Then;
import tools.dscode.dolt.DoltEntities;

/** Glue for the real-step usage test. One literal, no hand-set run context. */
public class LookupProbe {
    @Then("the account name is read")
    public void read() {
        DoltEntities.lookup("entity:Account#ada.name");
    }
}
