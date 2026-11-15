package tools.dscode.dalton.usage;

import io.cucumber.java.en.Then;
import tools.dscode.dalton.DaltonEntities;

/** Glue for the real-step usage test. One literal, no hand-set run context. */
public class LookupProbe {
    @Then("the account name is read")
    public void read() {
        DaltonEntities.lookup("entity:Account#ada.name");
    }
}
