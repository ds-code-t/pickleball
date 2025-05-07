package io.pickleball.metafunctionalities;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.pickleball.mapandStateutilities.LinkedMultiMap;

import static io.pickleball.cacheandstate.ScenarioContext.getRunMaps;
import static io.pickleball.cacheandstate.StepWrapper.TABLE_ROW_LOOP;
import static io.pickleball.configs.Constants.sFlag2;


public class CoreSteps {


    @Given("^\"(.*)\" TABLE$")
    public static void saveTableWithKey(String key, DataTable dataTable) {
        getRunMaps().addMapsWithKey(key, dataTable.asLinkedMultiMaps());
    }

    @Given("^REPEAT FOR \"(.*)\" TABLE ROWS$")
    public static void runInTable(String key) {

    }

    @Given("^"+TABLE_ROW_LOOP+ "$")
    public void placeHolder_TABLE_ROW_LOOP(DataTable dataTable){

    }


//    @Given("^-TABLE ROW LOOP-$")
//    public void loopTable(DataTable dataTable) {
//        for (LinkedMultiMap<String, String> map : dataTable.asLinkedMultiMaps(String.class, String.class)) {
//            {
//                getRunMaps().addMap(map);
//            }
//        }
//    }

}
