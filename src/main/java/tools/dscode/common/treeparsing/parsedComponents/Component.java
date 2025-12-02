package tools.dscode.common.treeparsing.parsedComponents;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.treeparsing.MatchNode;

public abstract class Component {
    public final int position;
    public final String name;

    public Component(MatchNode matchNode) {
        this.name = matchNode.name();
        this.position = matchNode.position;
    }

    public Object getValue(WebDriver driver) {
        if (this instanceof ElementMatch elementMatch1) {
            WebElement element = driver.findElement(elementMatch1.xPathy.getLocator());
            return element.getTagName().equals("input") ? element.getAttribute("value") : element.getText();
        } else {
            return ((ValueMatch) this).value;
        }
    }
}