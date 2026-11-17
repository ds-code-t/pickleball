package tools.dscode.store.usage;

import io.cucumber.java.en.Then;
import tools.dscode.store.StoreEntities;

/** Glue for the real-step usage test. One literal, no hand-set run context. */
public class LookupProbe {
    @Then("the account name is read")
    public void read() {
        StoreEntities.lookup("entity:Account#ada.name");
    }
}
