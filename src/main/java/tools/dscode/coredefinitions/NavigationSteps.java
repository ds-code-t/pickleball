package tools.dscode.coredefinitions;

import io.cucumber.java.en.When;
import org.openqa.selenium.WebDriver;
import tools.dscode.parallelutilities.Stagger;

import java.util.Locale;

import static tools.dscode.common.evaluations.AviatorUtil.isTruthy;
import static tools.dscode.common.mappings.ParsingMap.configsRoot;
import static tools.dscode.common.mappings.ParsingMap.getFromRunningParsingMapCaseInsensitive;
import static tools.dscode.common.reporting.logging.LogForwarder.logInfo;
import static tools.dscode.common.variables.RunVars.resolveFromVarsOrDefault;

public class NavigationSteps {


    @When("^navigate to: (.*)$")
    public void i_navigate_to(String text) {
        Object obj = getFromRunningParsingMapCaseInsensitive(configsRoot + "." + text);
        String address = addressAfterConfigLookup(text, obj);
        if (address != null) {
            logInfo("Attempting to navigate to: " + address + "");
            WebDriver driver = BrowserSteps.getCurrentDriver();
            if (isTruthy(resolveFromVarsOrDefault("pkb_staggerParallelURLCalls", false))) {
                Stagger.runUrlHost(
                        address,
                        () -> driver.get(address)
                );
            } else {
                driver.get(address);
            }
        }
        else {
            throw new RuntimeException("failed to navigate to: '" + text + "' , URL resolved to '" +  obj + "'"   );
        }
    }

    /**
     * A config-key string still wins. A missing key may be a literal http(s) URL,
     * or a placeholder that resolves to one. Anything else stays unresolved.
     */
    static String addressAfterConfigLookup(String text, Object lookedUp) {
        if (lookedUp instanceof String address && !address.isBlank()) {
            return address;
        }
        String candidate = text == null ? "" : text.trim();
        if (isHttpUrl(candidate)) return candidate;
        if (candidate.indexOf('<') >= 0 && candidate.indexOf('>') > candidate.indexOf('<')) {
            try {
                String resolved = tools.dscode.common.mappings.ParsingMap.resolveToStringWithRunningParsingMap(candidate);
                if (resolved != null && isHttpUrl(resolved.trim())) return resolved.trim();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean isHttpUrl(String value) {
        if (value == null || value.length() < 8) return false;
        String head = value.substring(0, 8).toLowerCase(Locale.ROOT);
        return head.startsWith("https://") || head.startsWith("http://");
    }
}
