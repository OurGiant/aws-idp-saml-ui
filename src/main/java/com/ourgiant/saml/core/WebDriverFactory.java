package com.ourgiant.saml.core;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
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
        return createWebDriver(browserType, showBrowser, loginUrl, false);
    }

    public static WebDriver createWebDriver(String browserType, boolean showBrowser, String loginUrl,
                                             boolean useOktaFastPass) {
        switch (browserType.toLowerCase()) {
            case "edge":
                return createEdgeDriver(showBrowser, loginUrl, useOktaFastPass);
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

    private static WebDriver createEdgeDriver(boolean showBrowser, String loginUrl, boolean useOktaFastPass) {
        EdgeOptions options = new EdgeOptions();
        System.setProperty("webdriver.manager.stats", "false");
        if (!showBrowser) {
            options.addArguments("--headless");
        }
        options.addArguments("--disable-dev-shm-usage");
        if (useOktaFastPass) {
            options.addArguments("--disable-popup-blocking");
        }
        options.setExperimentalOption("prefs", edgePreferences(loginUrl, useOktaFastPass));

        return new EdgeDriver(options);
    }

    static Map<String, Object> edgePreferences(String loginUrl, boolean useOktaFastPass) {
        Map<String, Object> preferences = new HashMap<>();
        if (!useOktaFastPass || loginUrl == null) {
            return preferences;
        }

        URI loginUri;
        try {
            loginUri = URI.create(loginUrl);
        } catch (IllegalArgumentException e) {
            return preferences;
        }
        if (!"https".equalsIgnoreCase(loginUri.getScheme()) || loginUri.getHost() == null
                || loginUri.getUserInfo() != null) {
            return preferences;
        }

        String host = loginUri.getHost().toLowerCase(Locale.ROOT);
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        int port = loginUri.getPort();
        String origin = "https://" + host + (port == -1 || port == 443 ? "" : ":" + port);
        Map<String, Object> protocolPermissions = Map.of(
            "allowed_origin_protocol_pairs", Map.of(
                origin, Map.of("com-okta-authenticator", true)
            )
        );
        preferences.put("protocol_handler", protocolPermissions);

        if (port == -1) {
            port = 443;
        }
        String originPattern = "https://" + host + ":" + port + ",*";
        preferences.put("profile.content_settings.exceptions.local_network_access",
            Map.of(originPattern, Map.of("setting", 1)));
        return preferences;
    }
}
