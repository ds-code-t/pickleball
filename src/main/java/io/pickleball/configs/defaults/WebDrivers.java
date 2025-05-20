package io.pickleball.configs.defaults;


import io.pickleball.cacheandstate.GlobalRegistry;
import io.pickleball.drivers.UniversalWebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

class WebDrivers extends GlobalRegistry {

    public static UniversalWebDriver Chrome() {
        ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.addArguments("--private");
        UniversalWebDriver driver = UniversalWebDriver.createChromeDriver(chromeOptions);
            driver.navigate().to("https://example.org");
        return driver;
    }
}