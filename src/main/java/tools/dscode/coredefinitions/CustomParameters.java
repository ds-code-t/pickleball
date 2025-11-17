package tools.dscode.coredefinitions;

import io.cucumber.java.ParameterType;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.edge.EdgeDriver;

public class CustomParameters {



    @ParameterType("(?i)chrome|edge")
    public Object browser(String raw) {

        // Normalize to upper-case to simplify matching
        String name = raw.toUpperCase();

        return switch (name) {
            case "CHROME" -> new ChromeDriver();  // launches Chrome
            case "EDGE"   -> new EdgeDriver();    // launches Edge
            default -> throw new IllegalArgumentException("Unknown browser: " + raw);
        };
    }


//    @Given("^,(.*)$")
//    public void dynamicStep(String stepText) {
//        System.out.println("dynamicStep: " + stepText);
//        DictionaryA dict = new DictionaryA();
//        LineExecution lineData = dict.getLineExecutionData(stepText);
//        lineData.execute(BrowserSteps.getDriver());
//    }
}
