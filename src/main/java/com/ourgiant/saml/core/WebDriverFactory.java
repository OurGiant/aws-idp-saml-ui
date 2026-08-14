package com.ourgiant.saml.core;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;

/**
 * Creates Selenium WebDriver instances for the configured browser type. Shared by the SAML
 * login flow (SamlAuthenticator) and ephemeral "Open Console" browser windows (SwingMain) —
 * each caller gets its own driver instance, so each gets its own isolated cookie jar rather
 * than colliding in the OS's shared default browser session.
 */
public class WebDriverFactory {

    private WebDriverFactory() {
    }

    public static WebDriver createWebDriver(String browserType, boolean showBrowser) {
        switch (browserType.toLowerCase()) {
            case "firefox":
                return createFirefoxDriver(showBrowser);
            case "chrome":
            default:
                return createChromeDriver(showBrowser);
        }
    }

    private static WebDriver createChromeDriver(boolean showBrowser) {
        ChromeOptions options = new ChromeOptions();
        System.setProperty("webdriver.manager.stats", "false");
        if (!showBrowser) {
            options.addArguments("--headless");
        }
        options.addArguments("--disable-dev-shm-usage");

        return new ChromeDriver(options);
    }

    private static WebDriver createFirefoxDriver(boolean showBrowser) {
        FirefoxOptions options = new FirefoxOptions();
        if (!showBrowser) {
            options.addArguments("--headless");
        }
        System.setProperty("webdriver.manager.stats", "false");

        return new FirefoxDriver(options);
    }
}
