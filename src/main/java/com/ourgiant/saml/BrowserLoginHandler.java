package com.ourgiant.saml;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.time.Duration;

/**
 * Handles browser automation for SAML login
 */
public class BrowserLoginHandler {
    private static final Logger logger = LoggerFactory.getLogger(BrowserLoginHandler.class);

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final boolean useOktaFastPass;
    private final PasswordManager passwordManager;

    public BrowserLoginHandler(WebDriver driver, boolean useOktaFastPass, PasswordManager passwordManager) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        this.useOktaFastPass = useOktaFastPass;
        this.passwordManager = passwordManager;
    }

    /**
     * Perform login and capture SAML response
     */
    public String performLogin(String loginUrl, String loginTitle, String username) throws Exception {
        logger.info("Navigating to login page: {}", loginUrl);

        try {
            // Navigate to login page
            driver.get(loginUrl);

            // Wait for login page to load
            wait.until(ExpectedConditions.titleContains(loginTitle));

            // Handle Okta login (since user specified Okta)
            return handleOktaLogin(username);

        } catch (Exception e) {
            logger.error("Login failed", e);
            throw new RuntimeException("Login process failed: " + e.getMessage(), e);
        }
    }

    /**
     * Handle Okta-specific login flow
     */
    private String handleOktaLogin(String username) throws Exception {
        logger.info("Handling Okta login for user: {}", username);

        // Detect managed-device Okta flow with pre-authenticated MFA screen first
        if (isPreAuthenticatedOktaMfaScreen()) {
            logger.info("Detected pre-authenticated Okta MFA screen; skipping username/password entry");
            clickOktaSelection();
            return waitForSamlResponse();
        }

        // Wait for and fill username field
        try {
            WebElement usernameField = wait.until(ExpectedConditions.elementToBeClickable(By.name("identifier")));
            usernameField.clear();
            usernameField.sendKeys(username);

            // Click next/sign in
            // <input class="button button-primary" type="submit" value="Next" data-type="save">
            WebElement nextButton = wait.until(ExpectedConditions.elementToBeClickable(By.className("button-primary")));
            nextButton.click();
        } catch (TimeoutException e) {
            logger.info("Okta username field not found; checking for managed-device MFA flow");
            if (isPreAuthenticatedOktaMfaScreen()) {
                logger.info("Detected managed-device Okta MFA flow after missing username field");
                clickOktaSelection();
                return waitForSamlResponse();
            }
            throw e;
        }
        // Check for interstitial page that may require clicking through before password entry

        logger.info("Checking for intermediate verification page");
        if (isIntermediateVerifificationPage()) {
            logger.info("Handling intermediate verification page");
            clickUsePassword();
        }

        try {
            WebElement passwordField = wait.until(ExpectedConditions.elementToBeClickable(By.name("credentials.passcode")));
            String password = promptForPassword();
            passwordField.clear();
            passwordField.sendKeys(password);

            // Submit password
            WebElement signInButton = wait.until(ExpectedConditions.elementToBeClickable(By.className("button-primary")));
            signInButton.click();

        } catch (TimeoutException e) {
            logger.warn("Password field not found on intermediate verification page; checking for managed-device MFA flow");
            throw e;
        }

        if (useOktaFastPass) {
            clickOktaFastPassSelection();
        } else {
            clickOktaMfaSelection();
        }

        // Wait for SAML response or AWS sign-in page
        return waitForSamlResponse();
    }

    private boolean isIntermediateVerifificationPage() { 
        try {
            WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(10));
            shortWait.until(ExpectedConditions.presenceOfElementLocated(By.linkText("Back to sign in")));
        } catch (TimeoutException e) {
            logger.info("Not on intermediate verification page");
            return false;
        }
        logger.info("Detected intermediate verification page");
        try { 
            Thread.sleep(2000); // Wait for potential redirect to occur
            String pageSource = driver.getPageSource();
            boolean hasVerifyIndicator = pageSource.contains("class=\"button select-factor link-button\"");
            return true;
        }
        catch (Exception e) {
            logger.warn("Error while checking for intermediate verification page", e);
            return false;
        }
    }

    private void clickUsePassword() {
        try {
            By usePasswordLocator = By.xpath(
                "//a[@aria-label='Select Password.']" 
            );
            WebElement usePasswordOption = wait.until(ExpectedConditions.elementToBeClickable(usePasswordLocator));
            usePasswordOption.click();
        } catch (TimeoutException e) {
            logger.warn("Could not find option to switch to password entry on intermediate verification page", e);
        }
    }

    private boolean isPreAuthenticatedOktaMfaScreen() {
        try {
            WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(10));
            shortWait.until(ExpectedConditions.presenceOfElementLocated(By.linkText("Back to sign in")));
            String pageSource = driver.getPageSource();
            boolean hasMfaIndicator = pageSource.contains("class=\"button select-factor link-button\"");
            return hasMfaIndicator;
        } catch (TimeoutException e) {
            return false;
        }
    }

    private void clickOktaSelection() {
        if (useOktaFastPass) {
            clickOktaFastPassSelection();
        } else {
            clickOktaMfaSelection();
        }
    }

    private void clickOktaFastPassSelection() {
        try {
            By fastPassLocator = By.xpath(
                "//a[@aria-label='Select Okta Verify.'] | //a[contains(@aria-label,'Okta Verify')]"
            );
            WebElement fastPassOption = wait.until(ExpectedConditions.elementToBeClickable(fastPassLocator));
            fastPassOption.click();
        } catch (TimeoutException e) {
            throw new RuntimeException("Could not find Okta FastPass selection button on managed-device login screen", e);
        }
    }

    private void clickOktaMfaSelection() {
        By selectionLocator = By.xpath(
            "//a[@aria-label='Select to get a push notification to the Okta Verify app.']"
            + " | //input[@class='button button-primary' and @type='submit' and @value='Send push' and @data-type='save']"
        );

        // First check if autoChallenge is present is checked, which would indicate we can skip clicking the button
        try {
            WebElement autoChallengeElement = wait.until(ExpectedConditions.presenceOfElementLocated(By.name("autoChallenge")));
            if (autoChallengeElement.isSelected()) {
                logger.info("Okta auto-challenge is enabled, skipping click");
                return;
            } 
        } catch (NoSuchElementException | TimeoutException e) {
            logger.info("Okta auto-challenge element not found or not enabled, proceeding to click selection");
        }

        logger.info("Clicking Okta MFA push selection");

        try {
            WebElement mfaOption = wait.until(ExpectedConditions.elementToBeClickable(selectionLocator));
            mfaOption.click();
        } catch (TimeoutException e) {
            throw new RuntimeException("Could not find Okta MFA push selection button on managed-device login screen", e);
        }

        // For some reason, on Windows we end up going from choose to get a push notification to a page that actually sends the notification. This does happen on Linux
        // To account for this after we push the aria-lable Select button, we need to then push the Send Push with have the class button-primary and type submit
        try {
            WebElement sendPushButton = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//input[@class='button button-primary' and @type='submit' and @value='Send push' and @data-type='save']")));
            sendPushButton.click();
        } catch (TimeoutException e) {
            logger.info("Send Push button not found after clicking Okta Verify selection, which may be expected on some platforms");
        }
    }

    /**
     * Prompt user for password using Swing dialog or retrieve from storage
     */
    private String promptForPassword() {
        // Try to use stored password if enabled
        if (passwordManager.isPasswordStorageEnabled()) {
            String storedPassword = passwordManager.retrievePassword();
            if (storedPassword != null && !storedPassword.isEmpty()) {
                logger.info("Using stored Okta password");
                return storedPassword;
            }
        }

        // No stored password or storage disabled, prompt user
        JPasswordField passwordField = new JPasswordField();
        passwordField.setEchoChar('*');

        Object[] message = {
            "Enter your IdP password:",
            passwordField
        };

        int option = JOptionPane.showConfirmDialog(
            null,
            message,
            "IdP Authentication",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE
        );

        if (option == JOptionPane.OK_OPTION) {
            String password = new String(passwordField.getPassword());

            // Store password if storage is enabled
            if (passwordManager.isPasswordStorageEnabled()) {
                try {
                    passwordManager.storePassword(password);
                    logger.info("Okta password stored for future use");
                } catch (Exception e) {
                    logger.warn("Failed to store password, but continuing with authentication", e);
                }
            }

            return password;
        } else {
            throw new RuntimeException("Password entry cancelled by user");
        }
    }

    /**
     * Wait for SAML response to be available
     */
    private String waitForSamlResponse() throws Exception {
        logger.info("Waiting for SAML response...");

        // Wait for redirect to AWS sign-in page
        wait.until(ExpectedConditions.urlContains("signin.aws.amazon.com"));

        // Try to find SAML response in form
        try {
            WebElement samlResponseElement = wait.until(
                ExpectedConditions.presenceOfElementLocated(By.name("SAMLResponse"))
            );

            String samlResponse = samlResponseElement.getAttribute("value");
            if (samlResponse != null && !samlResponse.isEmpty()) {
                logger.info("SAML response captured successfully");
                return samlResponse;
            }
        } catch (TimeoutException e) {
            logger.warn("SAML response element not found, checking page source");
        }

        // Fallback: check page source for SAML response
        String pageSource = driver.getPageSource();
        if (pageSource.contains("SAMLResponse")) {
            // Extract SAML response from page source
            int startIndex = pageSource.indexOf("name=\"SAMLResponse\"");
            if (startIndex > 0) {
                int valueStart = pageSource.indexOf("value=\"", startIndex);
                if (valueStart > 0) {
                    valueStart += 7; // length of 'value="'
                    int valueEnd = pageSource.indexOf("\"", valueStart);
                    if (valueEnd > 0) {
                        String samlResponse = pageSource.substring(valueStart, valueEnd);
                        logger.info("SAML response extracted from page source");
                        return samlResponse;
                    }
                }
            }
        }

        throw new RuntimeException("SAML response not found in AWS sign-in page");
    }
}