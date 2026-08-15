package com.ourgiant.saml.core;

import com.ourgiant.saml.util.FilePermissions;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Handles browser automation for SAML login
 */
@SuppressWarnings("null") // Selenium's ExpectedConditions/@NonNull annotations are inconsistent with WebDriverWait.until()
public class BrowserLoginHandler {
    private static final Logger logger = LoggerFactory.getLogger(BrowserLoginHandler.class);

    private static final Duration MAIN_WAIT_TIMEOUT = Duration.ofSeconds(60);

    private static final String MFA_SCREEN_INDICATOR = "class=\"button select-factor link-button\"";
    private static final Duration MFA_SCREEN_POLL_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration BACKOFF_INITIAL_DELAY = Duration.ofSeconds(1);
    private static final Duration BACKOFF_MAX_DELAY = Duration.ofSeconds(4);
    private static final double BACKOFF_FACTOR = 2.0;

    // Okta's i18n key for this screen's heading (login.reset.password.headline / similar) renders
    // as this exact string on both the default hosted widget and custom SIW-based embedded flows
    // (#172), so it's a more stable signal than guessing at form/field markup we haven't observed
    // in this app's own driver yet. If this ever needs replacing, a real occurrence should now get
    // captured by captureFailureScreenshot() before the login times out below.
    private static final String FORCED_PASSWORD_RESET_INDICATOR = "Reset your Okta password";
    private static final Duration FORCED_PASSWORD_RESET_POLL_TIMEOUT = Duration.ofSeconds(5);

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final boolean useOktaFastPass;
    private final PasswordManager passwordManager;
    private final boolean showBrowser;
    private final String accountNumber;
    private final String iamRole;
    private final Consumer<String> statusCallback;
    private final BooleanSupplier cancelled;

    public BrowserLoginHandler(WebDriver driver, boolean useOktaFastPass, PasswordManager passwordManager,
                                boolean showBrowser, String accountNumber, String iamRole,
                                Consumer<String> statusCallback, BooleanSupplier cancelled) {
        this.driver = driver;
        // 30s was too tight for a slow/cold first navigation against at least one real-world
        // corporate IdP (#132): a login attempt timed out waiting for the login page's title,
        // then the very next attempt against the identical URL reached the next step in ~4s.
        // This is the wait behind the critical-path title/element checks in
        // performLogin()/handleOktaLogin()/waitForSamlResponse() - the various short "probe"
        // waits elsewhere in this class (3s/5s/10s, used to detect *optional* page states) are
        // deliberately short by design and untouched here.
        this.wait = new WebDriverWait(driver, MAIN_WAIT_TIMEOUT);
        this.useOktaFastPass = useOktaFastPass;
        this.passwordManager = passwordManager;
        this.showBrowser = showBrowser;
        this.accountNumber = accountNumber;
        this.iamRole = iamRole;
        this.statusCallback = statusCallback;
        this.cancelled = cancelled;
    }

    // Every wait.until(...) call in this class should go through here instead of calling it
    // directly. Checking the cancellation flag before evaluating the real condition lets a
    // cancelled request fail within one poll interval, without needing Thread.interrupt() — which
    // was tried first and left the browser process orphaned (see SamlAuthenticator.cancel()).
    private <V> V until(WebDriverWait waitInstance, Function<WebDriver, V> condition) {
        return waitInstance.until(driver -> {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Login cancelled");
            }
            return condition.apply(driver);
        });
    }

    /**
     * Same as {@link #until}, but reports a countdown against {@code timeout} on every poll so a
     * long, otherwise-silent wait (MFA approval) doesn't look indistinguishable from a hung app.
     */
    private <V> V untilWithCountdown(WebDriverWait waitInstance, Function<WebDriver, V> condition,
                                      Duration timeout, String waitingMessage) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        return waitInstance.until(driver -> {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Login cancelled");
            }
            long remainingSeconds = Math.max(0, (deadline - System.currentTimeMillis()) / 1000);
            statusCallback.accept(waitingMessage + " (" + remainingSeconds + "s remaining)...");
            return condition.apply(driver);
        });
    }

    /**
     * Perform login and capture SAML response
     */
    public String performLogin(String loginUrl, String loginTitle, String username) throws Exception {
        logger.info("Navigating to login page: {}", loginUrl);

        try {
            statusCallback.accept("Navigating to login page: " + loginUrl + "...");
            driver.get(loginUrl);

            statusCallback.accept("Waiting for login page to load...");
            until(wait, ExpectedConditions.titleContains(loginTitle));

            return handleOktaLogin(username);

        } catch (Exception e) {
            logger.error("Login failed", e);
            logPageStateOnFailure();
            throw new RuntimeException("Login process failed: " + summarize(e.getMessage()), e);
        }
    }

    /**
     * Captures the page Selenium was actually looking at when a login step failed, so a
     * timeout can be diagnosed as "page loaded but the expected condition never matched"
     * (wrong page — an unexpected redirect hop, device-trust check, bot-check on a fresh
     * headless fingerprint, etc.) versus "the right page just loaded slowly" instead of
     * guessing (#134). Best-effort: driver state can itself be unusable after some failures
     * (a crashed browser, a closed window), so this must never throw past a log warning.
     */
    private void logPageStateOnFailure() {
        try {
            logger.error("Page state at failure — URL: {}, Title: {}", driver.getCurrentUrl(), driver.getTitle());
        } catch (Exception e) {
            logger.warn("Could not capture page state after login failure", e);
        }
        captureFailureScreenshot();
    }

    /**
     * Best-effort screenshot of the page Selenium was looking at when a login step failed —
     * the same technique (capture-on-failure, never block/throw past a log warning) the
     * sibling Python app's ScreenshotRecorder uses. A URL/title alone can't show a device-trust
     * check, bot-check, or other unexpected page a user can only make sense of by looking at
     * it. Works fine headless: Selenium's screenshot goes through the browser's own rendering
     * via CDP, not the host display server (unlike java.awt.Robot elsewhere in this app).
     */
    private void captureFailureScreenshot() {
        if (!(driver instanceof TakesScreenshot)) {
            return;
        }
        try {
            Path dir = Paths.get(System.getProperty("user.home"), ".aws", "login-failure-screenshots");
            Files.createDirectories(dir);
            String filename = "login-failure-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").format(LocalDateTime.now()) + ".png";
            Path target = dir.resolve(filename);
            File screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            Files.copy(screenshot.toPath(), target);
            FilePermissions.restrictToOwner(target);
            logger.error("Saved failure screenshot: {}", target);
        } catch (Exception e) {
            logger.warn("Could not capture failure screenshot", e);
        }
    }

    /**
     * Selenium's own exceptions (TimeoutException in particular) pack a multi-line dump —
     * build info, full System/Driver info, the entire Capabilities map, session ID — into
     * getMessage() for debugging purposes. That's exactly what the full exception (preserved
     * as this RuntimeException's cause, and already logged in full above) is for; the
     * *message* propagated up to the user-facing error dialog only needs the actual failure
     * summary, which is everything before that dump begins ("Build info: ..." is a reliable
     * marker for where it starts, per WebDriverException's own message-building convention).
     */
    private static String summarize(String message) {
        if (message == null) {
            return "unknown error";
        }
        int buildInfoStart = message.indexOf("\nBuild info:");
        String summary = buildInfoStart >= 0 ? message.substring(0, buildInfoStart) : message;
        return summary.strip();
    }

    /**
     * Handle Okta-specific login flow
     */
    private String handleOktaLogin(String username) throws Exception {
        logger.info("Handling Okta login for user: {}", username);

        // Detect managed-device Okta flow with pre-authenticated MFA screen first
        statusCallback.accept("Checking for pre-authenticated Okta MFA screen...");
        if (isPreAuthenticatedOktaMfaScreen()) {
            logger.info("Detected pre-authenticated Okta MFA screen; skipping username/password entry");
            statusCallback.accept("Pre-authenticated MFA screen detected; selecting MFA method...");
            clickOktaSelection();
            return waitForSamlResponse();
        }

        // Wait for and fill username field
        try {
            statusCallback.accept("Entering username: " + username + "...");
            WebElement usernameField = until(wait, ExpectedConditions.elementToBeClickable(By.name("identifier")));
            usernameField.clear();
            usernameField.sendKeys(username);

            WebElement nextButton = until(wait, ExpectedConditions.elementToBeClickable(By.className("button-primary")));
            nextButton.click();
        } catch (TimeoutException e) {
            logger.info("Okta username field not found; checking for managed-device MFA flow");
            statusCallback.accept("Username field not found; checking for managed-device MFA flow...");
            if (isPreAuthenticatedOktaMfaScreen()) {
                logger.info("Detected managed-device Okta MFA flow after missing username field");
                statusCallback.accept("Managed-device MFA flow detected; selecting MFA method...");
                clickOktaSelection();
                return waitForSamlResponse();
            }
            throw e;
        }

        statusCallback.accept("Checking for intermediate verification page...");
        if (isIntermediateVerifificationPage()) {
            logger.info("Handling intermediate verification page");
            statusCallback.accept("Intermediate verification page detected; switching to password entry...");
            clickUsePassword();
        }

        try {
            WebElement passwordField = until(wait, ExpectedConditions.elementToBeClickable(By.name("credentials.passcode")));
            statusCallback.accept("Entering password...");
            String password = promptForPassword();
            passwordField.clear();
            passwordField.sendKeys(password);

            WebElement signInButton = until(wait, ExpectedConditions.elementToBeClickable(By.className("button-primary")));
            signInButton.click();

        } catch (TimeoutException e) {
            logger.warn("Password field not found on intermediate verification page; checking for managed-device MFA flow");
            throw e;
        }

        statusCallback.accept("Checking for forced Okta password-reset screen...");
        if (isForcedPasswordResetScreen()) {
            logger.warn("Detected forced Okta password-reset screen after primary authentication");
            throw new RuntimeException("Your Okta password needs to be reset before you can sign in. "
                + "Please log in to Okta via your browser to reset it, then try again.");
        }

        if (useOktaFastPass) {
            statusCallback.accept("Selecting Okta FastPass...");
            clickOktaFastPassSelection();
        } else {
            statusCallback.accept("Sending Okta MFA push — check your device for an approval request...");
            clickOktaMfaSelection();
        }

        return waitForSamlResponse();
    }

    private boolean isIntermediateVerifificationPage() {
        try {
            WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(10));
            until(shortWait, ExpectedConditions.presenceOfElementLocated(By.linkText("Back to sign in")));
        } catch (TimeoutException e) {
            logger.info("Not on intermediate verification page");
            return false;
        }
        logger.info("Detected intermediate verification page");
        try {
            // Wait for the page to stabilise after detecting the intermediate screen
            WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(3));
            until(shortWait, ExpectedConditions.presenceOfElementLocated(By.className("select-factor")));
            return true;
        } catch (TimeoutException e) {
            return true; // "Back to sign in" was found; treat as intermediate page regardless
        } catch (Exception e) {
            logger.warn("Error while checking for intermediate verification page", e);
            return false;
        }
    }

    /**
     * Detects Okta's forced "Reset your Okta password" interstitial, which some password
     * policies substitute for the MFA screen right after primary authentication succeeds (#172).
     * Unlike {@link #isPreAuthenticatedOktaMfaScreen()}, there's no known gating element to check
     * first, so this polls the page source directly for the screen's heading text.
     */
    private boolean isForcedPasswordResetScreen() {
        return pollPageSourceWithBackoff(FORCED_PASSWORD_RESET_INDICATOR, FORCED_PASSWORD_RESET_POLL_TIMEOUT);
    }

    private void clickUsePassword() {
        try {
            By usePasswordLocator = By.xpath(
                "//a[@aria-label='Select Password.']" 
            );
            WebElement usePasswordOption = until(wait, ExpectedConditions.elementToBeClickable(usePasswordLocator));
            usePasswordOption.click();
        } catch (TimeoutException e) {
            logger.warn("Could not find option to switch to password entry on intermediate verification page", e);
        }
    }

    private boolean isPreAuthenticatedOktaMfaScreen() {
        try {
            WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(10));
            until(shortWait, ExpectedConditions.presenceOfElementLocated(By.linkText("Back to sign in")));
        } catch (TimeoutException e) {
            return false;
        }
        return pollPageSourceWithBackoff(MFA_SCREEN_INDICATOR, MFA_SCREEN_POLL_TIMEOUT);
    }

    /**
     * Polls the page source for a substring, backing off exponentially between attempts instead of
     * checking once immediately. Okta's factor-selection markup can render after the "Back to sign
     * in" link is already present in the DOM — especially on Firefox — so a one-shot check right
     * after that wait resolves can miss it, while a slow-rendering page still finishes well within
     * this method's own deadline. Ported from the Python sibling's
     * {@code SeleniumHelper.poll_page_source_with_backoff} (aws-idp-saml #83).
     */
    private boolean pollPageSourceWithBackoff(String needle, Duration maxTotalDuration) {
        return pollPageSourceWithBackoff(needle, maxTotalDuration, BACKOFF_INITIAL_DELAY, BACKOFF_MAX_DELAY, BACKOFF_FACTOR);
    }

    /** Package-private so the backoff timing itself can be unit tested with millisecond-scale durations. */
    boolean pollPageSourceWithBackoff(String needle, Duration maxTotalDuration,
                                       Duration initialDelay, Duration maxDelay, double backoffFactor) {
        long deadline = System.currentTimeMillis() + maxTotalDuration.toMillis();
        long delayMillis = initialDelay.toMillis();
        int attempt = 0;
        while (true) {
            attempt++;
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Login cancelled");
            }
            if (driver.getPageSource().contains(needle)) {
                logger.debug("Found '{}' in page source on attempt {}", needle, attempt);
                return true;
            }
            long remainingMillis = deadline - System.currentTimeMillis();
            if (remainingMillis <= 0) {
                logger.info("Timed out waiting for '{}' in page source after {} attempts", needle, attempt);
                return false;
            }
            try {
                Thread.sleep(Math.min(delayMillis, remainingMillis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            delayMillis = Math.min((long) (delayMillis * backoffFactor), maxDelay.toMillis());
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
            WebElement fastPassOption = until(wait, ExpectedConditions.elementToBeClickable(fastPassLocator));
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

        // First check if autoChallenge is present and checked, which would indicate we can skip clicking the button
        try {
            WebDriverWait probeWait = new WebDriverWait(driver, Duration.ofSeconds(3));
            WebElement autoChallengeElement = until(probeWait, ExpectedConditions.presenceOfElementLocated(By.name("autoChallenge")));
            if (autoChallengeElement.isSelected()) {
                logger.info("Okta auto-challenge is enabled, skipping click");
                return;
            }
        } catch (NoSuchElementException | TimeoutException e) {
            logger.info("Okta auto-challenge element not found or not enabled, proceeding to click selection");
        }

        logger.info("Clicking Okta MFA push selection");

        try {
            WebElement mfaOption = until(wait, ExpectedConditions.elementToBeClickable(selectionLocator));
            mfaOption.click();
        } catch (TimeoutException e) {
            throw new RuntimeException("Could not find Okta MFA push selection button on managed-device login screen", e);
        }

        // On Windows an extra "Send Push" confirmation page appears after selecting the MFA method; not present on Linux
        try {
            WebDriverWait probeWait = new WebDriverWait(driver, Duration.ofSeconds(5));
            WebElement sendPushButton = until(probeWait, ExpectedConditions.elementToBeClickable(By.xpath("//input[@class='button button-primary' and @type='submit' and @value='Send push' and @data-type='save']")));
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

        untilWithCountdown(wait, ExpectedConditions.urlContains("signin.aws.amazon.com"),
                MAIN_WAIT_TIMEOUT, "Waiting for redirect to AWS sign-in page");

        statusCallback.accept("Capturing SAML response...");

        // Try to find SAML response in form
        String samlResponse = null;
        try {
            WebElement samlResponseElement = until(
                wait, ExpectedConditions.presenceOfElementLocated(By.name("SAMLResponse"))
            );

            samlResponse = samlResponseElement.getAttribute("value");
            if (samlResponse != null && !samlResponse.isEmpty()) {
                logger.info("SAML response captured successfully");
            }
        } catch (TimeoutException e) {
            logger.warn("SAML response element not found, checking page source");
        }

        // Fallback: check page source for SAML response
        if (samlResponse == null || samlResponse.isEmpty()) {
            String pageSource = driver.getPageSource();
            if (pageSource != null && pageSource.contains("SAMLResponse")) {
                int startIndex = pageSource.indexOf("name=\"SAMLResponse\"");
                if (startIndex > 0) {
                    int valueStart = pageSource.indexOf("value=\"", startIndex);
                    if (valueStart > 0) {
                        valueStart += 7; // length of 'value="'
                        int valueEnd = pageSource.indexOf("\"", valueStart);
                        if (valueEnd > 0) {
                            samlResponse = pageSource.substring(valueStart, valueEnd);
                            logger.info("SAML response extracted from page source");
                        }
                    }
                }
            }
        }

        if (samlResponse == null || samlResponse.isEmpty()) {
            throw new RuntimeException("SAML response not found in AWS sign-in page");
        }

        if (showBrowser) {
            selectRoleAndSignIn();
        }

        return samlResponse;
    }

    /**
     * Builds a safe XPath string literal for an arbitrary value, avoiding XPath
     * injection when the value contains quote characters. XPath has no escape
     * character, so a value containing both quote types is split into
     * concat() segments.
     */
    static String toXPathLiteral(String value) {
        if (!value.contains("'")) {
            return "'" + value + "'";
        }
        if (!value.contains("\"")) {
            return "\"" + value + "\"";
        }
        String[] parts = value.split("'", -1);
        StringBuilder sb = new StringBuilder("concat(");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(", \"'\", ");
            }
            sb.append('\'').append(parts[i]).append('\'');
        }
        sb.append(")");
        return sb.toString();
    }

    private void selectRoleAndSignIn() {
        logger.info("Selecting role {}:{} on AWS SAML page", accountNumber, iamRole);
        statusCallback.accept("Selecting AWS role " + iamRole + " on AWS console...");

        driver.manage().window().maximize();

        // Role element ID is the full ARN; match by account number and role name.
        // Values are escaped into XPath string literals since accountNumber/iamRole
        // come from profile config and could otherwise break out of the quoted
        // contains(...) argument and alter the query (CWE-643).
        String roleXpath = String.format(
            "//*[contains(@id, %s) and contains(@id, %s)]",
            toXPathLiteral("::" + accountNumber + ":"), toXPathLiteral(iamRole)
        );

        try {
            WebElement roleElement = until(wait, ExpectedConditions.elementToBeClickable(By.xpath(roleXpath)));
            roleElement.click();
            logger.info("Role element clicked");
        } catch (TimeoutException e) {
            throw new RuntimeException(
                String.format("Could not find role element for account %s / role %s on AWS SAML page", accountNumber, iamRole), e
            );
        }

        // Design A: submit button present; Design B: click was sufficient
        try {
            WebElement signInButton = driver.findElement(By.id("signin_button"));
            signInButton.click();
            logger.info("Sign-in button clicked");
        } catch (NoSuchElementException e) {
            logger.info("No sign-in button found; assuming direct-link design");
        }
    }
}