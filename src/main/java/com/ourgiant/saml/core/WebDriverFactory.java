package com.ourgiant.saml.core;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;

import java.net.URI;
import java.util.Map;

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
        return createWebDriver(browserType, showBrowser, null);
    }

    public static WebDriver createWebDriver(String browserType, boolean showBrowser, String loginUrl) {
        switch (browserType.toLowerCase()) {
            case "edge":
                return createEdgeDriver(showBrowser, loginUrl);
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

    private static WebDriver createEdgeDriver(boolean showBrowser, String loginUrl) {
        EdgeOptions options = new EdgeOptions();
        System.setProperty("webdriver.manager.stats", "false");
        if (!showBrowser) {
            options.addArguments("--headless");
        }
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-popup-blocking");
        configureOktaAuthenticatorProtocol(options, loginUrl);

        return new EdgeDriver(options);
    }

    private static void configureOktaAuthenticatorProtocol(EdgeOptions options, String loginUrl) {
        if (loginUrl == null) {
            return;
        }

        URI loginUri;
        try {
            loginUri = URI.create(loginUrl);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (loginUri.getScheme() == null || loginUri.getRawAuthority() == null) {
            return;
        }

        String origin = loginUri.getScheme() + "://" + loginUri.getRawAuthority();
        Map<String, Object> protocolPermissions = Map.of(
            "allowed_origin_protocol_pairs", Map.of(
                origin, Map.of("com-okta-authenticator", true)
            )
        );
        String localNetworkAccessPermission =
            "profile.content_settings.exceptions.local_network_access." + origin + ",*.setting";
        options.setExperimentalOption("prefs", Map.of(
            "protocol_handler", protocolPermissions,
            localNetworkAccessPermission, 1
        ));
    }
}
