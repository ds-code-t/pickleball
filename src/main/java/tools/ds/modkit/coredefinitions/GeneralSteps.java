package tools.ds.modkit.coredefinitions;

import com.google.common.collect.LinkedListMultimap;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import tools.ds.modkit.state.ScenarioState;

import java.util.ArrayList;
import java.util.List;

import static tools.ds.modkit.state.ScenarioState.getScenarioState;
import static tools.ds.modkit.util.TableUtils.*;

public class GeneralSteps {

    @Given("MESSAGE:{string}")
    public static void setValues(String message) {
        System.out.println("MESSAGE: " + message);
        Throwable t = getScenarioState().getCurrentStep().storedThrowable;
        if (t != null) {
            getScenarioState().getCurrentStep().storedThrowable = null;
            throw new RuntimeException(t);
        }
    }

}
