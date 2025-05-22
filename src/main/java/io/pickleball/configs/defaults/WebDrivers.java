package io.pickleball.configs.defaults;


import io.pickleball.cacheandstate.GlobalRegistry;
import io.pickleball.drivers.UniversalWebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import static io.pickleball.cacheandstate.StateUtilities.getFirstString;
import static io.pickleball.cacheandstate.StateUtilities.getOrDefaultString;

class WebDrivers extends GlobalRegistry {

    public static UniversalWebDriver Chrome() {
        ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.addArguments("--private");
        UniversalWebDriver driver = UniversalWebDriver.createChromeDriver(chromeOptions);
        System.out.println("@@getOrDefaultString(\"url\", \"https://example.org\"): " + getOrDefaultString("url", "https://example.org"));
            driver.navigate().to(getOrDefaultString("url", "https://example.org"));
        return driver;
    }
}